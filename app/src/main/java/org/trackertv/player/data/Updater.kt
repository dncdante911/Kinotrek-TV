package org.trackertv.player.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.trackertv.player.BuildConfig
import org.trackertv.player.UpdateInstallReceiver
import org.trackertv.player.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** [error] is the installer's message after a cancelled or rejected attempt. */
    data class Ready(val release: Release, val file: File, val error: String? = null) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates from GitHub Releases: checks at most every 12 hours, downloads the APK in the
 * background and verifies its SHA-256. Installing always waits for the user on the home screen:
 * replacing the app closes it, which must never cut a film short.
 */
class Updater(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var job: Job? = null
    private val settings get() = context.app.settings
    private val folder get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Called when the home screen opens. */
    fun checkIfDue() {
        if (!settings.current.autoUpdate || job?.isActive == true) return
        if (mutableState.value is UpdateState.Ready || mutableState.value is UpdateState.Available) return
        if (System.currentTimeMillis() - settings.current.lastUpdateCheck < CHECK_INTERVAL_MS) return
        check(userAsked = false)
    }

    fun check(userAsked: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            mutableState.value = UpdateState.Checking
            try {
                cleanOldDownloads()
                val release = latestRelease()
                settings.setLastUpdateCheck(System.currentTimeMillis())
                if (release == null || !isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    mutableState.value = UpdateState.UpToDate
                    return@launch
                }
                val existing = apkFile(release)
                mutableState.value = when {
                    existing.exists() && matches(existing, release) -> UpdateState.Ready(release, existing)
                    settings.current.autoUpdate || userAsked -> { download(release); return@launch }
                    else -> UpdateState.Available(release)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                mutableState.value = if (userAsked) UpdateState.Failed("Не удалось проверить обновления: ${e.message ?: "нет сети"}") else UpdateState.Idle
            }
        }
    }

    fun downloadNow() {
        val release = (mutableState.value as? UpdateState.Available)?.release ?: return
        if (job?.isActive == true) return
        job = scope.launch { download(release) }
    }

    private suspend fun download(release: Release) {
        val target = apkFile(release)
        val part = File(target.path + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            mutableState.value = UpdateState.Downloading(release, 0f)
            open(release.apkUrl, readTimeoutMs = 30_000).let { connection ->
                try {
                    if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (!scope.isActive) throw IOException("Отменено")
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                if (total > 0) mutableState.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !hash.equals(release.sha256, ignoreCase = true)) throw IOException("файл повреждён (SHA-256 не совпал)")
            if (release.size > 0 && done != release.size) throw IOException("файл скачан не полностью")
            target.delete()
            if (!part.renameTo(target)) throw IOException("не удалось сохранить файл")
            mutableState.value = UpdateState.Ready(release, target)
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "Update download failed", e)
            mutableState.value = UpdateState.Failed("Не удалось скачать обновление: ${e.message ?: "нет сети"}")
        }
    }

    /** False when the system first has to allow installing apps from Kinotrek. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Streams the verified APK into a PackageInstaller session; the system takes over from here. */
    fun install() {
        val ready = mutableState.value as? UpdateState.Ready ?: return
        mutableState.value = UpdateState.Installing(ready.release)
        scope.launch {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(ready.file.length())
                    // Android 12+: once the app installed itself, later updates need no confirmation.
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("kinotrek.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update install failed", e)
                mutableState.value = UpdateState.Failed("Не удалось начать установку: ${e.message}")
            }
        }
    }

    /** Reported by [UpdateInstallReceiver] when the system rejects or the user cancels. */
    fun installFinished(error: String?) {
        if (error == null) return
        val release = (mutableState.value as? UpdateState.Installing)?.release
        mutableState.value = if (release != null && apkFile(release).exists()) UpdateState.Ready(release, apkFile(release), error)
        else UpdateState.Failed(error)
    }

    fun dismissError() {
        if (mutableState.value is UpdateState.Failed) mutableState.value = UpdateState.Idle
    }

    private fun latestRelease(): Release? {
        val connection = open(API, readTimeoutMs = 15_000)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 404) return null
            if (connection.responseCode !in 200..299) throw IOException("GitHub ответил HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
            return Release(
                version = json.optString("tag_name").trimStart('v', 'V'),
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Kinotrek/${BuildConfig.VERSION_NAME}")
    }

    private fun apkFile(release: Release) = File(folder, "Kinotrek-${release.version}.apk")

    private fun matches(file: File, release: Release): Boolean {
        if (release.size > 0 && file.length() != release.size) return false
        val expected = release.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    /** APKs of versions already installed are no longer needed. */
    private fun cleanOldDownloads() {
        folder.listFiles()?.forEach { file ->
            val version = file.name.removePrefix("Kinotrek-").removeSuffix(".apk").removeSuffix(".part")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) file.delete()
        }
    }

    companion object {
        private const val TAG = "KinotrekUpdater"
        private const val API = "https://api.github.com/repos/dncdante911/Kinotrek-TV/releases/latest"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** "3.2.0" > "3.1.10" > "3.1.3"; anything that is not a version is never newer. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parts(candidate) ?: return false
            val b = parts(current) ?: return true
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(version: String): List<Int>? =
            Regex("\\d+(?:\\.\\d+)*").find(version)?.value?.split('.')?.map { it.toInt() }
    }
}
