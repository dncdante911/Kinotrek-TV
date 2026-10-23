package org.trackertv.player.data

import java.util.Locale

enum class SourceKind(val id: String, val label: String) {
    TORRENT("torrent", "Раздача"),
    STREAM("stream", "Поток"),
    FILE("file", "Файл");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: STREAM
        fun forUrl(url: String) = when {
            url.startsWith("magnet:", ignoreCase = true) -> TORRENT
            url.startsWith("content:") || url.startsWith("file:") -> FILE
            else -> STREAM
        }
    }
}

/** Playback progress of one playable file; [key] is the item url or "url|file:index". */
data class Progress(val key: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

data class LibraryItem(
    val url: String,
    val title: String,
    val kind: SourceKind,
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0,
    /** The file played last inside this item, used for "continue watching". */
    val resumeKey: String? = null,
    val resumeLabel: String? = null,
    /** Cover from the release page, when the video came from search. */
    val poster: String? = null,
    /** Release page, to show the description again from the library. */
    val page: String? = null,
) {
    /** Stable identity: the info hash for magnets (trackers and names vary), the url otherwise. */
    val id: String get() = identityOf(url)
    val info: ReleaseInfo by lazy { ReleaseInfo.parse(title) }

    companion object {
        private val BTIH = Regex("xt=urn:btih:([a-zA-Z0-9]+)")

        fun identityOf(url: String): String =
            BTIH.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { "btih:$it" } ?: url

        fun fileKey(itemUrl: String, fileId: Int) = "$itemUrl|file:$fileId"
    }
}
