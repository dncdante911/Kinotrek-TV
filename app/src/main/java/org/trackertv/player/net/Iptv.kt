package org.trackertv.player.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A TV channel of an open playlist. */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    /** "1080p", "720p"… when the playlist says. */
    val quality: String?,
    /** Marked "Not 24/7": the stream is on only part of the day. */
    val partTime: Boolean,
    val userAgent: String?,
    val referer: String?,
)

/**
 * Free channels from iptv-org (https://github.com/iptv-org/iptv), a public catalogue of streams that
 * broadcasters themselves make available. The app reads its playlists and plays the streams; it
 * stores nothing and drops channels the catalogue marks as blocked outside their country.
 */
enum class ChannelList(val title: String, val path: String) {
    UKRAINE("Украина", "countries/ua"),
    RUSSIAN("На русском", "languages/rus"),
    NEWS("Новости мира", "categories/news"),
    MOVIES("Кино", "categories/movies"),
    KIDS("Детям", "categories/kids"),
    MUSIC("Музыка", "categories/music"),
    ;

    val url: String get() = "https://iptv-org.github.io/iptv/$path.m3u"
}

object Iptv {
    private const val CACHE_MS = 12 * 60 * 60_000L
    private val memory = HashMap<ChannelList, List<Channel>>()

    /** Groups shown first: what people zap through most. */
    private val GROUP_ORDER = listOf("general", "news", "movies", "entertainment", "series", "kids", "music", "sports", "documentary")

    /** The channels of [list]; a playlist up to 12 hours old is reused, an older one is the fallback offline. */
    suspend fun channels(context: Context, list: ChannelList): List<Channel> {
        synchronized(memory) { memory[list] }?.let { return it }
        val file = File(File(context.cacheDir, "iptv").apply { mkdirs() }, "${list.name.lowercase(Locale.ROOT)}.m3u")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS
        val text = (if (fresh) withContext(Dispatchers.IO) { file.readText() } else null) ?: try {
            val response = Http.request(list.url, readTimeoutMs = 20_000, maxBytes = 6_000_000)
            if (!response.ok) throw java.io.IOException("Каталог каналов недоступен (HTTP ${response.code})")
            response.text().also { withContext(Dispatchers.IO) { runCatching { file.writeText(it) } } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (file.exists()) withContext(Dispatchers.IO) { file.readText() } else throw e
        }
        val channels = withContext(Dispatchers.Default) { sort(parse(text)) }
        synchronized(memory) { memory[list] = channels }
        return channels
    }

    private val EXTINF = Regex("^#EXTINF:[^ ,]*((?:\\s+[\\w-]+=\"[^\"]*\")*)\\s*,(.*)$")
    private val ATTRIBUTE = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val QUALITY = Regex("\\((\\d{3,4}p)\\)")
    private val TAGS = Regex("\\[[^\\]]*]")

    /** Reads an M3U playlist with iptv-org's attributes; channels blocked by region are left out. */
    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var attrs: Map<String, String> = emptyMap()
        var title: String? = null
        var userAgent: String? = null
        var referer: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val match = EXTINF.find(line)
                    attrs = match?.groupValues?.get(1)?.let { ATTRIBUTE.findAll(it).associate { a -> a.groupValues[1] to a.groupValues[2] } }.orEmpty()
                    title = match?.groupValues?.get(2)?.trim() ?: line.substringAfterLast(',').trim()
                    userAgent = attrs["http-user-agent"]?.ifBlank { null }
                    referer = attrs["http-referrer"]?.ifBlank { null }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> userAgent = line.substringAfter('=').ifBlank { null }
                line.startsWith("#EXTVLCOPT:http-referrer=") -> referer = line.substringAfter('=').ifBlank { null }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val name = title
                    if (name != null && !name.contains("[Geo-blocked]", ignoreCase = true) && (line.startsWith("http://") || line.startsWith("https://"))) {
                        result += Channel(
                            name = name.replace(QUALITY, "").replace(TAGS, "").replace(Regex("\\s+"), " ").trim(),
                            url = line,
                            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
                            group = attrs["group-title"]?.substringBefore(';')?.takeIf { it.isNotBlank() && !it.equals("Undefined", ignoreCase = true) },
                            quality = QUALITY.find(name)?.groupValues?.get(1),
                            partTime = name.contains("Not 24/7", ignoreCase = true),
                            userAgent = userAgent,
                            referer = referer,
                        )
                    }
                    title = null
                    attrs = emptyMap()
                    userAgent = null
                    referer = null
                }
            }
        }
        return result
    }

    /** One entry per channel (the best quality), the most watched kinds of channels first. */
    private fun sort(channels: List<Channel>): List<Channel> = channels
        .groupBy { it.name.lowercase(Locale.ROOT) }
        .map { (_, same) -> same.maxBy { it.quality?.removeSuffix("p")?.toIntOrNull() ?: 0 } }
        .sortedWith(compareBy<Channel>({ GROUP_ORDER.indexOf(it.group?.lowercase(Locale.ROOT)).let { i -> if (i < 0) GROUP_ORDER.size else i } }, { it.partTime }, { it.name.lowercase(Locale.ROOT) }))

    /**
     * Whether the stream answers now: a playlist or manifest must come back, anything else that
     * starts downloading counts as working. Redirects are followed, a few at most.
     */
    suspend fun check(channel: Channel): Boolean = try {
        var url = channel.url
        var ok = false
        for (hop in 0..3) {
            val headers = buildMap {
                put("User-Agent", channel.userAgent ?: "Mozilla/5.0 (Linux; Android TV) Kinotrek")
                channel.referer?.let { put("Referer", it) }
            }
            val response = Http.request(url, headers = headers, connectTimeoutMs = 5_000, readTimeoutMs = 6_000, maxBytes = 400_000)
            val next = response.location
            if (response.code in 300..399 && next != null) {
                url = java.net.URL(java.net.URL(url), next).toString()
                continue
            }
            val text = if (response.ok) response.text() else ""
            ok = response.ok && (text.contains("#EXTM3U") || text.contains("<MPD") || !looksLikeManifest(url))
            break
        }
        ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        // "Too large" means the server is streaming video straight away: it works.
        e.message?.contains("Слишком большой") == true
    } catch (e: Exception) {
        false
    }

    private fun looksLikeManifest(url: String) = url.substringBefore('?').let { it.endsWith(".m3u8", true) || it.endsWith(".m3u", true) || it.endsWith(".mpd", true) }
}
