package org.trackertv.player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

enum class AudioLanguage(val code: String, val label: String) {
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "Английский"),
    ORIGINAL("", "Как в файле");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: RUSSIAN
    }
}

/** How far the built-in engine downloads ahead, kept on disk, so a pause on slow internet fills up the buffer. */
enum class PauseBuffer(val bytes: Long, val label: String) {
    OFF(0, "Выключено"),
    SMALL(1L shl 30, "1 ГБ"),
    MEDIUM(2L shl 30, "2 ГБ"),
    LARGE(4L shl 30, "4 ГБ");

    val enabled: Boolean get() = this != OFF
}

/** Upload to other peers. Without it some releases fill slower, since peers prefer those who share back. */
enum class UploadLimit(val kbps: Int, val label: String) {
    UNLIMITED(0, "Без ограничений"),
    MB2(2048, "2 МБ/с"),
    MB1(1024, "1 МБ/с"),
    KB300(300, "300 КБ/с"),
    OFF(0, "Выключена"),
}

/** Subtitle size as a multiple of Media3's default. */
enum class SubtitleSize(val scale: Float, val label: String) {
    SMALL(0.8f, "Мелкие"),
    NORMAL(1f, "Обычные"),
    LARGE(1.25f, "Крупные"),
    HUGE(1.5f, "Очень крупные"),
}

data class SettingsState(
    /** Empty means the TorrServer bundled with the app. */
    val server: String = "",
    val autoFrameRate: Boolean = true,
    val audioLanguage: AudioLanguage = AudioLanguage.RUSSIAN,
    val autoplayNext: Boolean = true,
    val pauseBuffer: PauseBuffer = PauseBuffer.MEDIUM,
    val upload: UploadLimit = UploadLimit.UNLIMITED,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val history: List<String> = emptyList(),
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val watchedMinutes: Int = 0,
    /** Watched minutes when the support note was last shown; it returns every [Support.REMINDER_AFTER_MINUTES]. */
    val supportShownAtMinutes: Int = 0,
    val decor: DecorMode = DecorMode.AUTO,
    val trafficMode: TrafficMode = TrafficMode.AUTO,
)

/** User preferences; the "settings" file and its keys are shared with versions before 1.0. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()
    val current: SettingsState get() = mutableState.value

    fun setServer(address: String) = change { it.copy(server = address.trim()) }
    fun setAutoFrameRate(enabled: Boolean) = change { it.copy(autoFrameRate = enabled) }
    fun setAudioLanguage(language: AudioLanguage) = change { it.copy(audioLanguage = language) }
    fun setAutoplayNext(enabled: Boolean) = change { it.copy(autoplayNext = enabled) }
    fun setPauseBuffer(buffer: PauseBuffer) = change { it.copy(pauseBuffer = buffer) }
    fun setUpload(limit: UploadLimit) = change { it.copy(upload = limit) }
    fun setSubtitleSize(size: SubtitleSize) = change { it.copy(subtitleSize = size) }
    fun clearHistory() = change { it.copy(history = emptyList()) }
    fun setAutoUpdate(enabled: Boolean) = change { it.copy(autoUpdate = enabled) }
    fun setLastUpdateCheck(time: Long) = change { it.copy(lastUpdateCheck = time) }
    fun addWatchedMinute() = change { it.copy(watchedMinutes = it.watchedMinutes + 1) }
    fun setDecor(mode: DecorMode) = change { it.copy(decor = mode) }
    fun setTrafficMode(mode: TrafficMode) = change { it.copy(trafficMode = mode) }
    fun markSupportReminderShown() = change { it.copy(supportShownAtMinutes = it.watchedMinutes) }

    fun remember(query: String) {
        val value = query.trim()
        if (value.length < 2) return
        change { state -> state.copy(history = (listOf(value) + state.history.filterNot { it.equals(value, ignoreCase = true) }).take(MAX_HISTORY)) }
    }

    private fun change(transform: (SettingsState) -> SettingsState) {
        mutableState.update(transform)
        write(mutableState.value)
    }

    private fun read(): SettingsState {
        val history = runCatching {
            val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        return SettingsState(
            server = prefs.getString(KEY_SERVER, "").orEmpty(),
            autoFrameRate = prefs.getBoolean(KEY_AUTO_FPS, true),
            audioLanguage = AudioLanguage.of(prefs.getString(KEY_AUDIO, AudioLanguage.RUSSIAN.code)),
            autoplayNext = prefs.getBoolean(KEY_AUTOPLAY, true),
            pauseBuffer = PauseBuffer.entries.firstOrNull { it.name == prefs.getString("pause_buffer", null) } ?: PauseBuffer.MEDIUM,
            upload = UploadLimit.entries.firstOrNull { it.name == prefs.getString("upload", null) } ?: UploadLimit.UNLIMITED,
            subtitleSize = SubtitleSize.entries.firstOrNull { it.name == prefs.getString("subtitle_size", null) } ?: SubtitleSize.NORMAL,
            history = history,
            autoUpdate = prefs.getBoolean("auto_update", true),
            lastUpdateCheck = prefs.getLong("last_update_check", 0),
            watchedMinutes = prefs.getInt("watched_minutes", 0),
            supportShownAtMinutes = prefs.getInt("support_shown_at", 0),
            decor = DecorMode.entries.firstOrNull { it.name == prefs.getString("decor", null) } ?: DecorMode.AUTO,
            trafficMode = TrafficMode.entries.firstOrNull { it.name == prefs.getString("traffic_mode", null) } ?: TrafficMode.AUTO,
        )
    }

    private fun write(state: SettingsState) = prefs.edit {
        putString(KEY_SERVER, state.server)
        putBoolean(KEY_AUTO_FPS, state.autoFrameRate)
        putString(KEY_AUDIO, state.audioLanguage.code)
        putBoolean(KEY_AUTOPLAY, state.autoplayNext)
        putString("pause_buffer", state.pauseBuffer.name)
        putString("upload", state.upload.name)
        putString("subtitle_size", state.subtitleSize.name)
        putString(KEY_HISTORY, JSONArray(state.history).toString())
        putBoolean("auto_update", state.autoUpdate)
        putLong("last_update_check", state.lastUpdateCheck)
        putInt("watched_minutes", state.watchedMinutes)
        putInt("support_shown_at", state.supportShownAtMinutes)
        putString("decor", state.decor.name)
        putString("traffic_mode", state.trafficMode.name)
    }

    private companion object {
        const val MAX_HISTORY = 12
        const val KEY_SERVER = "server"
        const val KEY_AUTO_FPS = "auto_fps"
        const val KEY_AUDIO = "audio_language"
        const val KEY_AUTOPLAY = "autoplay_next"
        const val KEY_HISTORY = "search_history"
    }
}
