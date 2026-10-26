package org.trackertv.player.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.trackertv.player.data.AppSettings
import org.trackertv.player.data.StreamFit
import org.trackertv.player.data.StreamProfile
import org.trackertv.player.data.TrafficMode
import org.trackertv.player.torrent.EngineState
import org.trackertv.player.torrent.LocalTorrentService
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

enum class Link(val label: String) {
    ETHERNET("Кабель"),
    WIFI("Wi-Fi"),
    CELLULAR("Мобильный интернет"),
    OTHER("Сеть"),
    NONE("Нет сети"),
}

/** One speed measurement; [mbps] is null when no test server answered. */
data class SpeedTest(val mbps: Double?, val provider: String?, val link: Link, val time: Long)

data class NetworkStatus(
    val link: Link = Link.NONE,
    /** Mobile data, a phone's hotspot or a Wi-Fi the user marked as limited. */
    val metered: Boolean = false,
    val measuring: Boolean = false,
    /** Last measurement made on this kind of connection. */
    val test: SpeedTest? = null,
    val mode: TrafficMode = TrafficMode.AUTO,
) {
    val speedMbps: Double? get() = test?.mbps

    /** Profile search uses now; null when matching is off. */
    val profile: StreamProfile?
        get() = when (mode) {
            TrafficMode.OFF -> null
            TrafficMode.AUTO -> StreamFit.auto(metered, speedMbps)
            else -> mode.fixed
        }
}

/**
 * Watches the connection the TV is on and measures its real speed, so search can offer only the
 * releases this internet plays smoothly. A metered network is never measured on its own: it picks
 * the economy profile right away, without spending traffic on a test.
 */
class NetworkMonitor(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)
    @Volatile private var last: SpeedTest? = readTest()
    private val mutableState = MutableStateFlow(NetworkStatus(mode = settings.current.trafficMode))
    val state: StateFlow<NetworkStatus> = mutableState.asStateFlow()
    private var autoJob: Job? = null
    private var running: Deferred<SpeedTest>? = null

    fun start() {
        update(connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) })
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = update(null)
            })
        }
        scope.launch {
            settings.state.map { it.trafficMode }.distinctUntilChanged().collect { mode ->
                mutableState.update { it.copy(mode = mode) }
                scheduleAuto()
            }
        }
    }

    /** Measures now, or joins a measurement already running. */
    suspend fun measure(): SpeedTest {
        val job = synchronized(this) {
            running?.takeIf { it.isActive } ?: scope.async { runTest() }.also { running = it }
        }
        return job.await()
    }

    private fun update(caps: NetworkCapabilities?) {
        val link = when {
            caps == null -> Link.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.CELLULAR
            else -> Link.OTHER
        }
        val unmetered = caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (Build.VERSION.SDK_INT >= 30 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)))
        val metered = link != Link.NONE && !unmetered
        val before = mutableState.value
        if (before.link == link && before.metered == metered) return
        mutableState.update { it.copy(link = link, metered = metered, test = last?.takeIf { test -> test.link == link }) }
        scheduleAuto()
    }

    /** In [TrafficMode.AUTO], measures an unmetered connection once in a while, after it settles. */
    private fun scheduleAuto() {
        autoJob?.cancel()
        val s = mutableState.value
        if (s.mode != TrafficMode.AUTO || s.metered || s.link == Link.NONE) return
        val test = s.test
        val age = test?.let { System.currentTimeMillis() - it.time }
        if (age != null && age < (if (test.mbps == null) RETRY_FAILED_MS else RETEST_MS)) return
        autoJob = scope.launch {
            delay(SETTLE_MS)
            // While a torrent plays, a test would take its bandwidth and measure only what is left.
            if (LocalTorrentService.state.value is EngineState.Running) return@launch
            runCatching { measure() }
        }
    }

    private suspend fun runTest(): SpeedTest {
        val s = mutableState.value
        mutableState.update { it.copy(measuring = true) }
        try {
            val test = coroutineScope {
                val provider = async { provider() }
                val mbps = throughput(if (s.metered) METERED_BUDGET else BUDGET)
                SpeedTest(mbps, provider.await(), s.link, System.currentTimeMillis())
            }
            last = test
            saveTest(test)
            mutableState.update { it.copy(test = test.takeIf { t -> t.link == it.link } ?: it.test) }
            return test
        } finally {
            mutableState.update { it.copy(measuring = false) }
        }
    }

    /** Name of the internet provider as the test server sees it, e.g. "Kyivstar". */
    private suspend fun provider(): String? {
        for ((url, field) in PROVIDER_SOURCES) {
            val name = withTimeoutOrNull(6_000) {
                runCatching {
                    val response = Http.request(url, headers = mapOf("User-Agent" to USER_AGENT), connectTimeoutMs = 5_000, readTimeoutMs = 5_000, maxBytes = 64_000)
                    if (!response.ok) null else JSONObject(response.text()).optString(field).replace(ASN_PREFIX, "").trim().ifEmpty { null }
                }.getOrNull()
            }
            if (name != null) return name
        }
        return null
    }

    /**
     * Downloads from a speed-test server over several connections, like browser speed tests do,
     * until [budget] bytes or [TEST_MS] run out. The first [WARMUP_MS] are left out: TCP is still
     * speeding up then and would make a fast line look slow.
     */
    private suspend fun throughput(budget: Long): Double? {
        for (url in TEST_URLS) {
            val mbps = runCatching { download(url, budget) }.getOrNull()
            if (mbps != null) return mbps
        }
        return null
    }

    private suspend fun download(url: String, budget: Long): Double? = withContext(Dispatchers.IO) {
        val total = AtomicLong()
        val start = SystemClock.elapsedRealtime()
        val deadline = start + TEST_MS
        val streams = List(STREAMS) { launch { stream(url, total, budget, deadline) } }
        var warmBytes = -1L
        var warmAt = 0L
        while (streams.any { it.isActive }) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            if (warmBytes < 0 && now - start >= WARMUP_MS) {
                warmBytes = total.get()
                warmAt = now
            }
            // A server that sends nothing for a few seconds is unreachable: try the next one.
            if (now - start > 4_000 && total.get() == 0L) streams.forEach { it.cancel() }
        }
        val end = SystemClock.elapsedRealtime()
        val bytes = total.get()
        if (bytes < MIN_BYTES) return@withContext null
        val (counted, millis) = if (warmBytes >= 0 && end - warmAt >= 1_000) bytes - warmBytes to end - warmAt else bytes to end - start
        counted * 8.0 / millis.coerceAtLeast(1) / 1_000
    }

    private suspend fun stream(url: String, total: AtomicLong, budget: Long, deadline: Long) = coroutineScope {
        val buffer = ByteArray(64 * 1024)
        while (isActive && total.get() < budget && SystemClock.elapsedRealtime() < deadline) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (connection.responseCode !in 200..299) return@coroutineScope
                connection.inputStream.use { input ->
                    while (isActive && total.get() < budget && SystemClock.elapsedRealtime() < deadline) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@coroutineScope
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun readTest(): SpeedTest? {
        val time = prefs.getLong("time", 0)
        if (time == 0L) return null
        val link = Link.entries.firstOrNull { it.name == prefs.getString("link", null) } ?: return null
        val mbps = prefs.getFloat("mbps", -1f).takeIf { it >= 0 }?.toDouble()
        return SpeedTest(mbps, prefs.getString("provider", null), link, time)
    }

    private fun saveTest(test: SpeedTest) = prefs.edit {
        putLong("time", test.time)
        putString("link", test.link.name)
        putFloat("mbps", test.mbps?.toFloat() ?: -1f)
        putString("provider", test.provider)
    }

    private companion object {
        const val USER_AGENT = "Kinotrek-SpeedTest"
        const val STREAMS = 4
        const val TEST_MS = 12_000L
        const val WARMUP_MS = 1_500L
        const val MIN_BYTES = 256L * 1024
        /** About 10 seconds of a 50 Mbit/s line; a faster one fills it sooner and is "fast" anyway. */
        const val BUDGET = 60L shl 20
        /** On mobile data a test runs only when asked for, and stays small. */
        const val METERED_BUDGET = 10L shl 20
        const val SETTLE_MS = 5_000L
        const val RETEST_MS = 12 * 60 * 60_000L
        const val RETRY_FAILED_MS = 60 * 60_000L
        val TEST_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://proof.ovh.net/files/100Mb.dat",
        )
        val PROVIDER_SOURCES = listOf(
            "https://speed.cloudflare.com/meta" to "asOrganization",
            "https://ipinfo.io/json" to "org",
        )
        val ASN_PREFIX = Regex("^AS\\d+\\s*")
    }
}
