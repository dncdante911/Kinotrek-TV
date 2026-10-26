package org.trackertv.player.data

import java.util.Locale

enum class Quality(val label: String) { HD("720p"), FULL_HD("1080p"), UHD("4K") }

/** Where the video was taken from, as the release title names it; ordered from heaviest to lightest. */
enum class Rip(val label: String) {
    DISC("Blu-ray"),
    REMUX("Remux"),
    BDRIP("BDRip"),
    WEB_DL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDTV("HDTV"),
    HDRIP("HDRip"),
    DVD("DVD"),
    CAM("CAMRip"),
}

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
    val rip: Rip? = null,
    /** Episodes in the torrent when the title says it ("Серии 1-7 из 10" → 7). */
    val episodes: Int? = null,
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
        private val RIPS = listOf(
            Rip.REMUX to Regex("remux"),
            Rip.DISC to Regex("blu-?ray\\s*(?:disc|cee)|\\bbdmv\\b|\\bbd-?(?:25|50|66|100)\\b|\\buhd\\s*blu-?ray\\b(?!\\s*-?rip)"),
            Rip.BDRIP to Regex("bdrip|bd-rip|blu-?ray\\s*-?rip|brrip"),
            Rip.WEB_DL to Regex("web-?dl"),
            Rip.WEBRIP to Regex("web-?rip"),
            Rip.HDTV to Regex("hdtv"),
            Rip.HDRIP to Regex("hdrip"),
            Rip.DVD to Regex("dvd-?rip|\\bdvd-?[59]\\b"),
            Rip.CAM to Regex("camrip|telesync|\\bts-?rip\\b"),
        )
        /** "1-7 из 10", "Серии: 01-08", "E01-E08". */
        private val EPISODE_RANGE = Regex("(?:серии|эпизоды|episodes?)?\\s*:?\\s*\\b0*(\\d{1,3})\\s*[-–]\\s*e?0*(\\d{1,3})\\b(?=\\s*(?:из|of|\\]|\\)|$|\\s*\\|))|(?<=\\d)e0*(\\d{1,3})\\s*-\\s*e?0*(\\d{1,3})\\b")

        fun parse(raw: String): ReleaseInfo {
            val text = raw.replace(SPACES, " ").trim()
            val cut = text.indexOfAny(charArrayOf('(', '[')).takeIf { it > 0 } ?: text.length
            val head = text.substring(0, cut).trim().trimEnd('/', '|', '-', ' ').ifEmpty { text }
            val names = head.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
            val lower = text.lowercase(Locale.ROOT)
            val series = SERIES.containsMatchIn(lower)
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
                series = series,
                rip = RIPS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(lower) }?.first,
                episodes = if (series) episodesOf(lower) else null,
            )
        }

        private fun episodesOf(lower: String): Int? = EPISODE_RANGE.findAll(lower).firstNotNullOfOrNull { match ->
            val g = match.groupValues
            val from = (g[1].ifEmpty { g[3] }).toIntOrNull() ?: return@firstNotNullOfOrNull null
            val to = (g[2].ifEmpty { g[4] }).toIntOrNull() ?: return@firstNotNullOfOrNull null
            // Years ("2019-2024") and resolutions are not episode ranges.
            (to - from + 1).takeIf { from in 0..to && to < 1000 && it in 1..500 }
        }
    }
}
