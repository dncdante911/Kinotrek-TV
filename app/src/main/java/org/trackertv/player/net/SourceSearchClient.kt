package org.trackertv.player.net

import android.content.Context
import androidx.core.content.edit
import android.net.Uri
import android.text.Html
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.trackertv.player.data.Misfit
import org.trackertv.player.data.ReleaseInfo
import org.trackertv.player.data.StreamFit
import org.trackertv.player.data.StreamProfile
import java.io.IOException
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Rutor category ids, checked against the site's own category pages. */
enum class Category(val label: String, val rutor: List<Int>) {
    ALL("Всё видео", listOf(1, 5, 4, 16, 7, 10, 12)),
    MOVIES("Фильмы", listOf(1, 5)),
    SERIES("Сериалы", listOf(4, 16)),
    CARTOONS("Мультфильмы", listOf(7)),
    ANIME("Аниме", listOf(10)),
    DOCUMENTARY("Документальное", listOf(12)),
    TV("ТВ-шоу", listOf(6)),
    SPORT("Спорт", listOf(13)),
}

enum class Source(val label: String) { RUTOR("Rutor"), RUTRACKER("RuTracker") }

data class Hit(
    val id: String,
    val title: String,
    /** Where the page/magnet comes from; internal only, the UI does not show it. */
    val source: Source,
    /** Lower-case info hash, the identity used to merge the same torrent from several sites. */
    val hash: String?,
    val page: String,
    val magnet: String?,
    val size: String,
    val seeds: Int,
    val peers: Int,
    /** yyyymmdd for sorting, 0 when the site shows no date. */
    val day: Int,
    val dateLabel: String?,
    /** How many sites list this torrent. */
    val sites: Int = 1,
) {
    val info: ReleaseInfo = ReleaseInfo.parse(title)
    val bytes: Long? = StreamFit.sizeBytes(size)
    /** Rough bitrate from size; null when it cannot be told (a series without an episode count). */
    val mbps: Double? get() = StreamFit.mbps(info, bytes)

    fun misfit(profile: StreamProfile, lineMbps: Double?): Misfit? = StreamFit.check(info, bytes, seeds, profile, lineMbps)
}

data class SourceError(val source: Source, val message: String, val needsLogin: Boolean = false)

data class SearchResult(val hits: List<Hit>, val errors: List<SourceError>)

/**
 * Reads the public HTML of Rutor (three mirrors, first that answers wins) and RuTracker.
 * Seeder numbers are the site's last report, not a playback guarantee.
 */
class SourceSearchClient(private val context: Context) {
    @Volatile private var mirrors = listOf("https://rutor.info/", "https://rutor.org.in/", "https://rutor.org/")
    private val browseCache = ConcurrentHashMap<String, Pair<Long, List<Hit>>>()
    private val magnetCache = ConcurrentHashMap<String, String>()
    private val detailsCache = ConcurrentHashMap<String, ReleaseDetails>()
    private val posterGate = Semaphore(2)
    /** page URL → poster URL ("" = page has none), kept between launches. */
    private val posters = PosterIndex(context)

    /** RuTracker sits behind Cloudflare: its clearance cookie only works with the WebView's user agent. */
    private val webUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(DEFAULT_USER_AGENT)
    }

    // Parsing ~100 KB pages with regexes is CPU work: keep it off the main thread.
    suspend fun search(query: String, category: Category): SearchResult = withContext(Dispatchers.Default) {
        val q = Uri.encode(query.trim())
        val rutorCategories = if (category == Category.ALL) listOf(0) else category.rutor
        val rutor = rutorCategories.map { cat -> async { attempt(Source.RUTOR) { parseRutor(rutorPage("search/0/$cat/000/0/$q")) } } }
        val tracker = if (category in TRACKER_CATEGORIES) {
            async { attempt(Source.RUTRACKER) { parseTracker(fetchText("${TRACKER}tracker.php?nm=$q", tracker = true)) } }
        } else null
        val outcomes = (rutor + listOfNotNull(tracker)).awaitAll()
        val alive = outcomes.flatMap { it.first }
            .filter { hit -> hit.seeds >= MIN_SEEDS && (hit.source == Source.RUTOR || matchesCategory(hit.info, category)) }
        val hits = merge(withHashes(alive)).sortedByDescending { it.seeds }
        SearchResult(hits, outcomes.mapNotNull { it.second }.distinct())
    }

    /** Fresh releases ([popular] = false) or the most seeded ones in the category. */
    suspend fun browse(category: Category, popular: Boolean): SearchResult = withContext(Dispatchers.Default) {
        val key = "${category.name}:$popular"
        browseCache[key]?.let { (time, hits) -> if (System.currentTimeMillis() - time < CACHE_MS) return@withContext SearchResult(hits, emptyList()) }
        val outcomes = category.rutor.map { cat ->
            async { attempt(Source.RUTOR) { parseRutor(rutorPage("browse/0/$cat/0/${if (popular) 2 else 0}")) } }
        }.awaitAll()
        val merged = merge(outcomes.flatMap { it.first }.filter { it.seeds >= MIN_SEEDS })
        val hits = (if (popular) merged.sortedByDescending { it.seeds } else merged.sortedWith(compareByDescending<Hit> { it.day }.thenByDescending { it.seeds }))
            .take(BROWSE_LIMIT)
        if (hits.isNotEmpty()) browseCache[key] = System.currentTimeMillis() to hits
        SearchResult(hits, outcomes.mapNotNull { it.second }.distinct())
    }

    /** Description of a release page, cached; null when the page has none or cannot be read. */
    suspend fun details(hit: Hit): ReleaseDetails? {
        detailsCache[hit.page]?.let { return it }
        val html = fetchText(hit.page, tracker = hit.source == Source.RUTRACKER)
        val parsed = withContext(Dispatchers.Default) {
            if (hit.magnet == null) MAGNET.find(html)?.groupValues?.get(1)?.let(::decodeEntities)
                ?.takeIf { it.startsWith("magnet:?xt=urn:btih:") }?.let { magnetCache[hit.page] = it }
            if (hit.source == Source.RUTRACKER) ReleaseDetails.parseRutracker(html, hit.page) else ReleaseDetails.parseRutor(html, hit.page)
        }
        parsed?.let { detailsCache[hit.page] = it }
        parsed?.poster?.let { posters.put(hit.page, it) }
        return parsed
    }

    /**
     * Cover of a release for cards: remembered across launches, otherwise read from the page.
     * At most two pages are read at once so scrolling a row never floods the network.
     */
    suspend fun poster(hit: Hit): String? {
        posters.get(hit.page)?.let { return it.ifEmpty { null } }
        return posterGate.withPermit {
            posters.get(hit.page)?.let { return@withPermit it.ifEmpty { null } }
            val found = runCatching { details(hit)?.poster }.getOrNull()
            posters.put(hit.page, found.orEmpty())
            found
        }
    }

    /** Cover already known for this release, without any network. */
    fun knownPoster(hit: Hit): String? = posters.get(hit.page)?.ifEmpty { null } ?: detailsCache[hit.page]?.poster

    suspend fun magnet(hit: Hit): String {
        hit.magnet?.let { return it }
        magnetCache[hit.page]?.let { return it }
        val html = fetchText(hit.page, tracker = hit.source == Source.RUTRACKER)
        val magnet = withContext(Dispatchers.Default) { MAGNET.find(html)?.groupValues?.get(1)?.let(::decodeEntities) }
            ?: throw IOException("На странице раздачи нет magnet-ссылки. Возможно, нужен вход на сайт.")
        if (!magnet.startsWith("magnet:?xt=urn:btih:")) throw IOException("Неверная magnet-ссылка")
        magnetCache[hit.page] = magnet
        return magnet
    }

    /**
     * RuTracker result pages carry no magnet, so the hash is read from the topic page of the
     * best-seeded results (in parallel, a few at a time); the magnet is cached for playback too.
     */
    private suspend fun withHashes(hits: List<Hit>): List<Hit> = coroutineScope {
        val gate = Semaphore(4)
        val wanted = hits.filter { it.hash == null }.sortedByDescending { it.seeds }.take(HASH_LOOKUPS).map { it.id }.toSet()
        hits.map { hit ->
            if (hit.id !in wanted) return@map async { hit }
            async {
                val magnet = gate.withPermit { withTimeoutOrNull(8_000) { runCatching { magnet(hit) }.getOrNull() } }
                if (magnet == null) hit else hit.copy(magnet = magnet, hash = hashOf(magnet))
            }
        }.awaitAll()
    }

    /**
     * One entry per torrent: the same info hash found on several sites becomes a single result.
     * Rutor mirrors share one tracker, so per site the best number counts; different sites add up.
     */
    private fun merge(hits: List<Hit>): List<Hit> = hits
        .groupBy { it.hash?.let { hash -> "btih:$hash" } ?: it.id }
        .map { (key, group) ->
            if (group.size == 1) return@map group[0].copy(id = key)
            val perSite = group.groupBy { it.source }.mapValues { (_, same) -> same.maxBy { it.seeds } }
            val best = group.sortedWith(compareByDescending<Hit> { it.magnet != null }.thenByDescending { it.seeds }).first()
            best.copy(
                id = key,
                seeds = perSite.values.sumOf { it.seeds },
                peers = perSite.values.sumOf { it.peers },
                size = best.size.ifEmpty { group.firstNotNullOfOrNull { it.size.ifEmpty { null } }.orEmpty() },
                day = group.maxOf { it.day },
                dateLabel = group.maxBy { it.day }.dateLabel,
                sites = perSite.size,
            )
        }

    private suspend fun attempt(source: Source, block: suspend () -> List<Hit>): Pair<List<Hit>, SourceError?> = try {
        withTimeout(SOURCE_TIMEOUT_MS) { block() to null }
    } catch (e: TimeoutCancellationException) {
        emptyList<Hit>() to SourceError(source, "не ответил вовремя")
    } catch (e: CancellationException) {
        throw e
    } catch (e: TrackerLockedException) {
        emptyList<Hit>() to SourceError(source, "нужен вход на сайт", needsLogin = true)
    } catch (e: Exception) {
        emptyList<Hit>() to SourceError(source, e.message ?: "ошибка сети")
    }

    /** Tries the mirrors in order and remembers the one that answered. */
    private suspend fun rutorPage(path: String): Pair<String, String> {
        var failure: Exception? = null
        for (base in mirrors) {
            try {
                val html = fetchText(base + path, tracker = false)
                if (base != mirrors.first()) mirrors = listOf(base) + mirrors.filterNot { it == base }
                return base to html
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IOException("сайт недоступен")
    }

    private suspend fun fetchText(address: String, tracker: Boolean): String {
        var url = address
        repeat(MAX_REDIRECTS + 1) {
            val host = URL(url).host.lowercase(Locale.ROOT)
            if (host !in ALLOWED_HOSTS) throw IOException("Недопустимый адрес источника")
            val headers = buildMap {
                put("User-Agent", if (tracker) webUserAgent else DEFAULT_USER_AGENT)
                put("Accept-Language", "ru,en;q=0.8")
                if (tracker) CookieManager.getInstance().getCookie(TRACKER)?.let { put("Cookie", it) }
            }
            val response = Http.request(url, headers = headers, connectTimeoutMs = 6_000, readTimeoutMs = 9_000)
            when {
                response.code in 300..399 && response.location != null -> url = URL(URL(url), response.location).toString()
                response.ok -> return withContext(Dispatchers.Default) { response.text(charsetOf(response)) }
                tracker && (response.code == 403 || response.code == 503) -> throw TrackerLockedException()
                else -> throw IOException("HTTP ${response.code}")
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    private fun charsetOf(response: HttpResponse): Charset {
        val declared = response.contentType?.lowercase(Locale.ROOT).orEmpty() +
            String(response.body, 0, minOf(2048, response.body.size), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        return if ("windows-1251" in declared) Charset.forName("windows-1251") else Charsets.UTF_8
    }

    private fun parseRutor(page: Pair<String, String>): List<Hit> {
        val (base, html) = page
        return RUTOR_ROW.findAll(html).mapNotNull { row ->
            val cells = row.groupValues[1]
            val link = RUTOR_LINK.find(cells) ?: return@mapNotNull null
            val title = plain(link.groupValues[3])
            if (title.length < 3 || !looksLikeVideo(title)) return@mapNotNull null
            val seeds = RUTOR_SEEDS.find(cells)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (seeds < 1) return@mapNotNull null
            val date = FIRST_CELL.find(cells)?.groupValues?.get(1)?.let(::plain)
            val magnet = MAGNET.find(cells)?.groupValues?.get(1)?.let(::decodeEntities)
            Hit(
                id = "rutor:${link.groupValues[2]}",
                title = title,
                source = Source.RUTOR,
                page = URL(URL(base), decodeEntities(link.groupValues[1])).toString(),
                hash = magnet?.let(::hashOf),
                magnet = magnet,
                size = SIZE.find(plain(cells))?.value.orEmpty(),
                seeds = seeds,
                peers = RUTOR_PEERS.find(cells)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                day = date?.let(::dayOf) ?: 0,
                dateLabel = date?.let(::dateLabel),
            )
        }.toList()
    }

    private fun parseTracker(html: String): List<Hit> = ANY_ROW.findAll(html).mapNotNull { row ->
        val cells = row.groupValues[1]
        val link = TRACKER_LINK.find(cells) ?: return@mapNotNull null
        val title = plain(link.groupValues[2])
        if (title.length < 3 || !looksLikeVideo(title)) return@mapNotNull null
        val seeds = TRACKER_SEEDS.find(cells)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (seeds < 1) return@mapNotNull null
        val topic = link.groupValues[1]
        Hit(
            id = "rutracker:$topic",
            title = title,
            source = Source.RUTRACKER,
            page = "${TRACKER}viewtopic.php?t=$topic",
            hash = null,
            magnet = null,
            size = SIZE.find(plain(cells))?.value.orEmpty(),
            seeds = seeds,
            peers = TRACKER_PEERS.find(cells)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            day = 0,
            dateLabel = null,
        )
    }.toList()

    private fun matchesCategory(info: ReleaseInfo, category: Category) = when (category) {
        Category.MOVIES -> !info.series
        Category.SERIES -> info.series
        else -> true
    }

    private fun looksLikeVideo(title: String) = !NOT_VIDEO.containsMatchIn(title.lowercase(Locale.ROOT))

    private fun dayOf(date: String): Int {
        val parts = date.split(' ')
        if (parts.size != 3) return 0
        val month = MONTHS.indexOf(parts[1].lowercase(Locale.ROOT).take(3)) + 1
        val day = parts[0].toIntOrNull() ?: return 0
        val year = parts[2].toIntOrNull() ?: return 0
        return if (month == 0) 0 else (2000 + year) * 10000 + month * 100 + day
    }

    private fun dateLabel(date: String): String? {
        val parts = date.split(' ')
        return if (parts.size == 3) "${parts[0]} ${parts[1].lowercase(Locale.ROOT)}" else null
    }

    private class TrackerLockedException : IOException("RuTracker требует вход")

    private companion object {
        const val TRACKER = "https://rutracker.org/forum/"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val CACHE_MS = 10 * 60_000L
        const val BROWSE_LIMIT = 100
        /** Fewer seeders than this usually means the torrent will not start or will stall. */
        const val MIN_SEEDS = 2
        const val HASH_LOOKUPS = 20
        const val MAX_REDIRECTS = 3
        val TRACKER_CATEGORIES = setOf(Category.ALL, Category.MOVIES, Category.SERIES)
        val ALLOWED_HOSTS = setOf("rutor.info", "rutor.org.in", "rutor.org", "rutracker.org")
        val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

        val RUTOR_ROW = Regex("<tr class=\"(?:gai|tum)\">(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val ANY_ROW = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val FIRST_CELL = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        val RUTOR_LINK = Regex("<a\\b[^>]*href=[\"']([^\"']*/torrent/(\\d+)[^\"']*)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val RUTOR_SEEDS = Regex("alt=[\"']S[\"'][^>]*>(?:&nbsp;|\\s|<[^>]+>)*(\\d+)", RegexOption.IGNORE_CASE)
        val RUTOR_PEERS = Regex("alt=[\"']L[\"'][^>]*>(?:&nbsp;|\\s|<[^>]+>)*(\\d+)", RegexOption.IGNORE_CASE)
        val TRACKER_LINK = Regex("<a\\b[^>]*href=[\"'][^\"']*viewtopic\\.php\\?t=(\\d+)[^\"']*[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val TRACKER_SEEDS = Regex("class=[\"'][^\"']*seedmed[^\"']*[\"'][^>]*>\\s*(?:<b>)?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val TRACKER_PEERS = Regex("class=[\"'][^\"']*leechmed[^\"']*[\"'][^>]*>\\s*(?:<b>)?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val MAGNET = Regex("(magnet:\\?xt=urn:btih:[^\"'<>\\s]+)", RegexOption.IGNORE_CASE)
        val SIZE = Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:TB|GB|MB|ТБ|ГБ|МБ)\\b", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]+>")
        val NOT_VIDEO = Regex("\\b(mp3|flac|fb2|epub|pdf|djvu|portable|pc game|lossless|windows|office|android game|ps4|ps5|xbox|apk)\\b|аудиокниг")

        fun plain(html: String): String =
            Html.fromHtml(html.replace(TAG, " "), Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

        val BTIH = Regex("xt=urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)

        fun hashOf(magnet: String): String? = BTIH.find(magnet)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        fun decodeEntities(value: String) = value.replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&")
    }
}

/** Small persistent map of release page → cover URL, so known covers appear instantly. */
private class PosterIndex(context: Context) {
    private val prefs = context.getSharedPreferences("posters", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, String>()

    fun get(page: String): String? = memory[page] ?: prefs.getString(page, null)?.also { memory[page] = it }

    fun put(page: String, poster: String) {
        if (memory.put(page, poster) == poster) return
        prefs.edit { putString(page, poster) }
        if (memory.size > 4_000) memory.clear()
    }
}
