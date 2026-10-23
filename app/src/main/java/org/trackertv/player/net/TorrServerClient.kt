package org.trackertv.player.net

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

data class VideoFile(val id: Int, val path: String, val length: Long) {
    val fileName: String get() = path.substringAfterLast('/')
    val name: String get() = fileName.substringBeforeLast('.')
    val folder: String? get() = path.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { null }

    /** "S01E03" when the name has an episode marker, otherwise the file name. */
    val label: String
        get() = EPISODE.find(name)?.let { "S%02dE%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: name

    private companion object {
        val EPISODE = Regex("[Ss](\\d{1,2})[ ._-]?[Ee](\\d{1,3})")
    }
}

data class TorrentInfo(val hash: String, val title: String, val files: List<VideoFile>)

/** Status line shown while TorrServer looks for peers and metadata. */
data class TorrentStatus(val peers: Int, val seeders: Int, val speedBytes: Long)

/** HTTP API of TorrServer MatriX (https://github.com/YouROK/TorrServer). */
class TorrServerClient(address: String) {
    val base: String

    init {
        val uri = address.trim().toUri()
        require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()) {
            "Укажите полный адрес TorrServer, например http://192.168.1.10:8090"
        }
        base = address.trim().trimEnd('/')
    }

    suspend fun echo(timeoutMs: Int = 4_000): String {
        val response = Http.request("$base/echo", connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs)
        if (!response.ok) throw IOException("TorrServer ответил HTTP ${response.code}")
        return response.text().trim()
    }

    /** Adds the magnet and waits until TorrServer knows the file list. */
    suspend fun open(magnet: String, title: String, onStatus: (TorrentStatus) -> Unit): TorrentInfo {
        require(magnet.startsWith("magnet:?")) { "Нужна magnet-ссылка" }
        var json = call(JSONObject().put("action", "add").put("link", magnet).put("title", title).put("save_to_db", false))
        val hash = json.optString("hash").ifEmpty { throw IOException("TorrServer не вернул хэш раздачи") }
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while ((json.optJSONArray("file_stats")?.length() ?: 0) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("За ${METADATA_TIMEOUT_MS / 1000} секунд не удалось получить список файлов. У раздачи нет активных пиров или сеть блокирует торренты.")
            }
            onStatus(TorrentStatus(json.optInt("active_peers"), json.optInt("connected_seeders"), json.optLong("download_speed")))
            delay(1_000)
            json = call(JSONObject().put("action", "get").put("hash", hash))
        }
        return TorrentInfo(hash, json.optString("title", title), videoFiles(json.getJSONArray("file_stats")))
    }

    fun streamUrl(hash: String, file: VideoFile): String =
        "$base/stream/${Uri.encode(file.fileName)}?link=${Uri.encode(hash)}&index=${file.id}&play"

    private suspend fun call(body: JSONObject): JSONObject {
        val response = Http.request("$base/torrents", method = "POST", body = body.toString(), readTimeoutMs = 30_000)
        if (!response.ok) throw IOException("TorrServer ответил HTTP ${response.code}")
        val text = response.text().trim()
        return if (text.startsWith("[")) JSONArray(text).optJSONObject(0) ?: JSONObject() else JSONObject(text)
    }

    private fun videoFiles(stats: JSONArray): List<VideoFile> {
        val all = List(stats.length()) { i ->
            val file = stats.getJSONObject(i)
            VideoFile(file.optInt("id", i + 1), file.optString("path"), file.optLong("length"))
        }.filter { VIDEO.matches(it.path.lowercase(Locale.ROOT)) }
        // Trailers and samples hide the real video in packs; keep them only if nothing else is there.
        val main = all.filterNot { SAMPLE.containsMatchIn(it.name.lowercase(Locale.ROOT)) }
        return main.ifEmpty { all }.sortedWith { a, b -> naturalCompare(a.path, b.path) }
    }

    private companion object {
        const val METADATA_TIMEOUT_MS = 90_000L
        val VIDEO = Regex(".*\\.(mkv|mp4|m4v|webm|mov|avi|ts|m2ts|mts|mpeg|mpg|vob|flv|3gp)$")
        val SAMPLE = Regex("\\bsample\\b|\\btrailer\\b")
        val CHUNK = Regex("\\d+|\\D+")

        /** Orders "Episode 2" before "Episode 10". */
        fun naturalCompare(a: String, b: String): Int {
            val left = CHUNK.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
            val right = CHUNK.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val result = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else x.compareTo(y)
                if (result != 0) return result
            }
            return left.size - right.size
        }
    }
}
