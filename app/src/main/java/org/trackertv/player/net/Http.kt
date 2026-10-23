package org.trackertv.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val body: ByteArray, val contentType: String?, val location: String?) {
    val ok: Boolean get() = code in 200..299
    fun text(charset: java.nio.charset.Charset = Charsets.UTF_8) = String(body, charset)
}

/**
 * Blocking HttpURLConnection wrapped for coroutines: cancelling the caller disconnects the socket
 * at once, so an abandoned search or torrent request never holds a thread until its timeout.
 */
object Http {
    private val executor = Dispatchers.IO.asExecutor()

    suspend fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 7_000,
        readTimeoutMs: Int = 15_000,
        maxBytes: Int = 4_000_000,
    ): HttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            continuation.resumeWith(Result.failure(e))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
        executor.execute {
            val result = runCatching { perform(connection, method, body, headers, connectTimeoutMs, readTimeoutMs, maxBytes) }
            runCatching { connection.disconnect() }
            continuation.resumeWith(result)
        }
    }

    private fun perform(
        c: HttpURLConnection, method: String, body: String?, headers: Map<String, String>,
        connectTimeoutMs: Int, readTimeoutMs: Int, maxBytes: Int,
    ): HttpResponse {
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false
        headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val bytes = ByteArrayOutputStream()
        stream?.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes.write(buffer, 0, n)
                if (bytes.size() > maxBytes) throw IOException("Слишком большой ответ сервера")
            }
        }
        return HttpResponse(code, bytes.toByteArray(), c.contentType, c.getHeaderField("Location"))
    }
}
