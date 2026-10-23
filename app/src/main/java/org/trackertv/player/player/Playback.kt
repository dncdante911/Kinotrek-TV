package org.trackertv.player.player

import android.content.Context
import android.content.Intent
import org.trackertv.player.PlayerActivity

/** One playable file; [key] identifies its saved position. */
data class PlaybackEntry(val title: String, val url: String, val key: String)

data class PlaybackRequest(
    /** Library item the progress belongs to, null for one-off links. */
    val itemUrl: String?,
    val entries: List<PlaybackEntry>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0,
    /** Streams come from TorrServer, so the engine must stay alive while playing. */
    val torrent: Boolean = false,
) {
    fun start(context: Context) = context.startActivity(toIntent(context))

    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putExtra(EXTRA_ITEM, itemUrl)
        .putStringArrayListExtra(EXTRA_TITLES, ArrayList(entries.map { it.title }))
        .putStringArrayListExtra(EXTRA_URLS, ArrayList(entries.map { it.url }))
        .putStringArrayListExtra(EXTRA_KEYS, ArrayList(entries.map { it.key }))
        .putExtra(EXTRA_INDEX, startIndex)
        .putExtra(EXTRA_POSITION, startPositionMs)
        .putExtra(EXTRA_TORRENT, torrent)

    companion object {
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_TORRENT = "torrent"

        fun from(intent: Intent): PlaybackRequest? {
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: return null
            val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: return null
            val keys = intent.getStringArrayListExtra(EXTRA_KEYS) ?: return null
            if (urls.isEmpty() || titles.size != urls.size || keys.size != urls.size) return null
            return PlaybackRequest(
                itemUrl = intent.getStringExtra(EXTRA_ITEM),
                entries = urls.indices.map { PlaybackEntry(titles[it], urls[it], keys[it]) },
                startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.lastIndex),
                startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0),
                torrent = intent.getBooleanExtra(EXTRA_TORRENT, false),
            )
        }
    }
}
