package org.trackertv.player.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.trackertv.player.app
import org.trackertv.player.net.TorrServerClient
import java.io.IOException

/** Finds a TorrServer to talk to: the user's server, ours if running, one on this TV, or starts ours. */
object TorrentEngine {
    private const val TAG = "TorrentEngine"
    private const val LOCAL = "http://127.0.0.1:8090"
    /** Disk left free for the system when the read-ahead cache is sized. */
    private const val DISK_RESERVE_BYTES = 1L shl 30
    private const val MIN_DISK_CACHE_BYTES = 256L shl 20

    suspend fun connect(context: Context, externalServer: String, onStep: (String) -> Unit): TorrServerClient {
        if (externalServer.isNotBlank()) {
            onStep("Подключаемся к TorrServer…")
            val client = TorrServerClient(externalServer)
            try {
                client.echo(timeoutMs = 6_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("TorrServer ${client.base} не отвечает. Проверьте адрес в настройках или очистите поле, чтобы использовать встроенный.", e)
            }
            return client
        }

        (LocalTorrentService.state.value as? EngineState.Running)?.let { running ->
            val client = TorrServerClient("http://127.0.0.1:${running.port}")
            if (responds(client)) return client.also { configure(context, it) }
            LocalTorrentService.markLost()
        }
        // Another TorrServer app on this TV is fine too, unless that port is our own idle engine.
        if (!LocalTorrentService.engineLoaded) TorrServerClient(LOCAL).let { if (responds(it, 1_500)) return it }

        onStep("Запускаем торрент-движок…")
        LocalTorrentService.start(context)
        val state = withTimeoutOrNull(25_000) {
            LocalTorrentService.state.first { it is EngineState.Running || it is EngineState.Failed }
        } ?: throw IOException("Встроенный торрент-движок не запустился за 25 секунд.")
        if (state is EngineState.Failed) throw IOException("Встроенный торрент-движок не запустился: ${state.message}")
        val client = TorrServerClient("http://127.0.0.1:${(state as EngineState.Running).port}")
        repeat(20) {
            if (responds(client)) return client.also { configure(context, it) }
            delay(300)
        }
        throw IOException("Торрент-движок запущен, но не отвечает.")
    }

    /** Only our own engine is tuned; an external server or another app keeps its owner's settings. */
    private suspend fun configure(context: Context, client: TorrServerClient) {
        val buffer = context.app.settings.current.pauseBuffer
        val dir = LocalTorrentService.cacheDir(context)
        val bytes = if (buffer.enabled && (dir.isDirectory || dir.mkdirs())) minOf(buffer.bytes, dir.usableSpace - DISK_RESERVE_BYTES) else 0
        try {
            if (bytes >= MIN_DISK_CACHE_BYTES) client.configureCache(bytes, dir.absolutePath) else client.configureCache(0, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Playback still works with the previous cache settings.
            Log.w(TAG, "Could not configure the cache", e)
        }
    }

    private suspend fun responds(client: TorrServerClient, timeoutMs: Int = 2_000) = try {
        client.echo(timeoutMs)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
