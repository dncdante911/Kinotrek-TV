package org.trackertv.player.data

import java.util.Locale

/** Settings → "Подбор раздач под интернет". [AUTO] follows the network the TV is on now. */
enum class TrafficMode(val label: String) {
    AUTO("Авто"),
    ECONOMY("Экономия трафика"),
    BALANCED("Обычный"),
    MAXIMUM("Без ограничений"),
    OFF("Выключено");

    val fixed: StreamProfile?
        get() = when (this) {
            ECONOMY -> StreamProfile.ECONOMY
            BALANCED -> StreamProfile.BALANCED
            MAXIMUM -> StreamProfile.MAXIMUM
            AUTO, OFF -> null
        }
}

/** Which releases the current internet plays smoothly; search hides the rest. */
enum class StreamProfile(val label: String, val summary: String) {
    ECONOMY("Экономия трафика", "только 720p и 1080p в HEVC или WEB-DL, фильм до 5 ГБ, небольшой битрейт"),
    BALANCED("Обычный", "без ремуксов и дисков, только то, что сеть успевает докачивать"),
    MAXIMUM("Без ограничений", "все форматы и качества, включая 4K и ремуксы"),
}

/** Why a release does not suit the profile. */
enum class Misfit(val label: String) {
    WEAK("Мало сидов"),
    HEAVY("Сеть не потянет"),
    TOO_BIG("Слишком большая"),
    QUALITY("Не 720p/1080p"),
    CODEC("Не HEVC и не WEB-DL"),
    REMUX("Ремукс или диск"),
}

/**
 * Release vs. internet, judged from what the tracker list shows: title, size and seeders.
 * Bitrate is estimated from size over a typical runtime, so it is a rough "~" figure, but good
 * enough to tell a 3 GB WEB-DL from an 80 GB remux.
 */
object StreamFit {
    const val MOVIE_SECONDS = 110 * 60
    const val EPISODE_SECONDS = 45 * 60
    /** Fewer seeders than this make a stream start slowly and stall; the seeds label paints them red. */
    const val MIN_SEEDS = 5
    /** Large torrents need more peers to keep up with playback. */
    const val MIN_SEEDS_LARGE = 10
    const val LARGE_BYTES = 20L shl 30

    const val ECONOMY_MOVIE_BYTES = 5L shl 30
    const val ECONOMY_EPISODE_BYTES = 3L shl 29
    const val ECONOMY_MBPS = 6.0
    /** Without a measurement, "Обычный" keeps up to a good 4K WEB-DL (~15 GB per film). */
    const val BALANCED_MBPS = 20.0
    /** Torrent speed swings: a release must fit in this share of the measured line. */
    const val HEADROOM = 0.7

    /** Unmetered line speeds that pick the profile in [TrafficMode.AUTO]. */
    const val SLOW_MBPS = 15.0
    const val FAST_MBPS = 100.0

    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB|KB|ТБ|ГБ|МБ|КБ)", RegexOption.IGNORE_CASE)

    /** "1.46 GB", "700 МБ" → bytes. */
    fun sizeBytes(label: String): Long? {
        val match = SIZE.find(label) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val shift = when (match.groupValues[2].uppercase(Locale.ROOT)) {
            "TB", "ТБ" -> 40
            "GB", "ГБ" -> 30
            "MB", "МБ" -> 20
            else -> 10
        }
        return (number * (1L shl shift)).toLong()
    }

    /** Bytes of one film or one episode; null for a series whose episode count is unknown. */
    fun itemBytes(info: ReleaseInfo, bytes: Long?): Long? = when {
        bytes == null || bytes <= 0 -> null
        !info.series -> bytes
        else -> info.episodes?.let { bytes / it }
    }

    /** Rough average bitrate in Mbit/s. */
    fun mbps(info: ReleaseInfo, bytes: Long?): Double? {
        val item = itemBytes(info, bytes) ?: return null
        val seconds = if (info.series) EPISODE_SECONDS else MOVIE_SECONDS
        return item * 8.0 / seconds / 1_000_000
    }

    /** First reason the release does not suit [profile]; null when it fits. [lineMbps] is the measured speed, if any. */
    fun check(info: ReleaseInfo, bytes: Long?, seeds: Int, profile: StreamProfile, lineMbps: Double?): Misfit? {
        val large = bytes != null && bytes >= LARGE_BYTES
        if (seeds < (if (large) MIN_SEEDS_LARGE else MIN_SEEDS)) return Misfit.WEAK
        if (profile == StreamProfile.MAXIMUM) return null

        if (info.rip == Rip.REMUX || info.rip == Rip.DISC) return Misfit.REMUX
        val rate = mbps(info, bytes)
        val line = lineMbps?.takeIf { it > 0 }?.times(HEADROOM)
        if (profile == StreamProfile.ECONOMY) {
            if (info.quality != Quality.HD && info.quality != Quality.FULL_HD) return Misfit.QUALITY
            if (!info.hevc && info.rip != Rip.WEB_DL && info.rip != Rip.WEBRIP) return Misfit.CODEC
            val item = itemBytes(info, bytes)
            if (item != null && item > (if (info.series) ECONOMY_EPISODE_BYTES else ECONOMY_MOVIE_BYTES)) return Misfit.TOO_BIG
            if (rate != null && rate > minOf(ECONOMY_MBPS, line ?: ECONOMY_MBPS)) return Misfit.HEAVY
            return null
        }
        if (rate != null && rate > (line ?: BALANCED_MBPS)) return Misfit.HEAVY
        return null
    }

    /**
     * Profile for [TrafficMode.AUTO]: metered networks (mobile data, a phone's hotspot) always save
     * traffic; otherwise the measured speed decides, and an unknown speed stays in the middle.
     */
    fun auto(metered: Boolean, lineMbps: Double?): StreamProfile = when {
        metered -> StreamProfile.ECONOMY
        lineMbps == null -> StreamProfile.BALANCED
        lineMbps < SLOW_MBPS -> StreamProfile.ECONOMY
        lineMbps >= FAST_MBPS -> StreamProfile.MAXIMUM
        else -> StreamProfile.BALANCED
    }

    fun mbpsLabel(value: Double): String =
        if (value >= 10) "${value.toInt()} Мбит/с" else String.format(Locale.ROOT, "%.1f Мбит/с", value).replace('.', ',')
}
