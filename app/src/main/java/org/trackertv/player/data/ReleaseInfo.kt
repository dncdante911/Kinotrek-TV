package org.trackertv.player.data

import java.util.Locale

enum class Quality(val label: String) { HD("720p"), FULL_HD("1080p"), UHD("4K") }

/**
 * What a release title tells about the video. Tracker titles look like
 * "Русское / Original (2026) WEB-DL 1080p от Группы | D, P" or "Название [2026, BDRip 1080p]".
 */
data class ReleaseInfo(
    val title: String,
    val original: String?,
    val year: String?,
    val details: String,
    val quality: Quality?,
    val hdr: Boolean,
    val dolbyVision: Boolean,
    val hevc: Boolean,
    val series: Boolean,
) {
    val badges: List<String>
        get() = buildList {
            quality?.let { add(it.label) }
            if (dolbyVision) add("DV") else if (hdr) add("HDR")
            if (series) add("Сериал")
        }

    /** Title with the year, good as a search query for other releases of the same video. */
    val searchQuery: String get() = listOfNotNull(title, year).joinToString(" ")

    companion object {
        private val SPACES = Regex("\\s+")
        private val YEAR = Regex("[(\\[]\\s*((?:19|20)\\d{2})")
        private val UHD = Regex("2160p|\\b4k\\b|\\buhd\\b")
        private val HDR = Regex("\\bhdr")
        private val DOLBY_VISION = Regex("dolby vision|\\bdv\\b|\\bdovi\\b")
        private val HEVC = Regex("hevc|x265|h\\.?265")
        private val SERIES = Regex("\\bs\\d{1,2}(e\\d{1,3})?\\b|сезон|серии|серия|\\bseason|\\bepisodes?\\b|\\d+\\s*из\\s*\\d+")

        fun parse(raw: String): ReleaseInfo {
            val text = raw.replace(SPACES, " ").trim()
            val cut = text.indexOfAny(charArrayOf('(', '[')).takeIf { it > 0 } ?: text.length
            val head = text.substring(0, cut).trim().trimEnd('/', '|', '-', ' ').ifEmpty { text }
            val names = head.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
            val lower = text.lowercase(Locale.ROOT)
            return ReleaseInfo(
                title = names.firstOrNull() ?: text,
                original = names.getOrNull(1),
                year = YEAR.find(text)?.groupValues?.get(1),
                details = text.substring(cut).trim(),
                quality = when {
                    UHD.containsMatchIn(lower) -> Quality.UHD
                    lower.contains("1080") -> Quality.FULL_HD
                    lower.contains("720p") -> Quality.HD
                    else -> null
                },
                hdr = HDR.containsMatchIn(lower),
                dolbyVision = DOLBY_VISION.containsMatchIn(lower),
                hevc = HEVC.containsMatchIn(lower),
                series = SERIES.containsMatchIn(lower),
            )
        }
    }
}
