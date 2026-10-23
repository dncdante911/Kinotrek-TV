package org.trackertv.player.torrent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.trackertv.player.net.TorrServerClient
import java.io.IOException

/** Finds a TorrServer to talk to: the user's server, ours if running, one on this TV, or starts ours. */
object TorrentEngine {
    private const val LOCAL = "http://127.0.0.1:8090"

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
            if (responds(client)) return client
            LocalTorrentService.markLost()
        }
        // Another TorrServer app on this TV is fine too.
        TorrServerClient(LOCAL).let { if (responds(it, 1_500)) return it }

        onStep("Запускаем торрент-движок…")
        LocalTorrentService.start(context)
        val state = withTimeoutOrNull(25_000) {
            LocalTorrentService.state.first { it is EngineState.Running || it is EngineState.Failed }
        } ?: throw IOException("Встроенный торрент-движок не запустился за 25 секунд.")
        if (state is EngineState.Failed) throw IOException("Встроенный торрент-движок не запустился: ${state.message}")
        val client = TorrServerClient("http://127.0.0.1:${(state as EngineState.Running).port}")
        repeat(20) {
            if (responds(client)) return client
            delay(300)
        }
        throw IOException("Торрент-движок запущен, но не отвечает.")
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
