package org.trackertv.player.net

import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/**
 * Finds a trailer on YouTube: the release page often links one; otherwise the first result of a
 * "<title> <year> трейлер" search. The video opens in the YouTube app, the app never plays it itself.
 */
object Trailers {
    private val VIDEO_ID = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private val cache = HashMap<String, String>()

    fun query(title: String, year: String?): String = listOfNotNull(title, year, "трейлер").joinToString(" ")

    /** Video id, or null when YouTube cannot be reached or finds nothing. */
    suspend fun find(title: String, year: String?): String? {
        val query = query(title, year)
        synchronized(cache) { cache[query] }?.let { return it }
        return try {
            val response = Http.request(
                "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
                    "Accept-Language" to "ru,uk;q=0.9,en;q=0.8",
                    // Skips the cookie consent page YouTube shows in some countries.
                    "Cookie" to "SOCS=CAI; CONSENT=YES+1",
                ),
                maxBytes = 3_000_000,
            )
            if (!response.ok) return null
            VIDEO_ID.find(response.text())?.groupValues?.get(1)?.also { synchronized(cache) { cache[query] = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
