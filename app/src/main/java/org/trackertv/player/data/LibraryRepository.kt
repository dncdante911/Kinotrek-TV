package org.trackertv.player.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.trackertv.player.net.Hit
import java.io.File

data class LibrarySnapshot(
    val items: List<LibraryItem> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val loaded: Boolean = false,
) {
    fun find(url: String): LibraryItem? {
        val id = LibraryItem.identityOf(url)
        return items.firstOrNull { it.id == id }
    }

    /** A search hit in the library: by magnet when the list has it, else by the release page (RuTracker). */
    fun find(hit: Hit): LibraryItem? = hit.magnet?.let(::find) ?: items.firstOrNull { it.page == hit.page }

    fun resumeOf(item: LibraryItem): Progress? = item.resumeKey?.let(progress::get)

    /** Items with an unfinished file, most recently watched first. */
    val continueWatching: List<LibraryItem>
        get() = items.filter { item -> resumeOf(item)?.let { !it.finished && it.positionMs > 0 } == true }
            .sortedByDescending { it.lastPlayedAt }
}

/**
 * The library lives in memory and is written to library.json in the background, so screens never
 * wait on disk. Writes go through [AtomicFile]: a crash mid-write keeps the previous version.
 */
class LibraryRepository(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val legacy = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(LibrarySnapshot())
    private val writeLock = Mutex()
    private var pendingWrite: Job? = null

    val snapshot: StateFlow<LibrarySnapshot> = state.asStateFlow()
    val current: LibrarySnapshot get() = state.value

    init {
        scope.launch(Dispatchers.IO) {
            val stored = load()
            // Keep anything changed while the file was being read.
            state.update { early ->
                LibrarySnapshot(
                    items = (early.items + stored.items).distinctBy { it.id },
                    progress = stored.progress + early.progress,
                    loaded = true,
                )
            }
            if (stored.migrated) persist()
        }
    }

    suspend fun awaitLoaded(): LibrarySnapshot = state.first { it.loaded }

    /** Adds the item or refreshes an existing one, keeping the user's title, favorite flag and progress. */
    fun upsert(item: LibraryItem): LibraryItem {
        var result = item
        mutate { snapshot ->
            val existing = snapshot.items.firstOrNull { it.id == item.id }
            result = existing?.copy(
                favorite = existing.favorite || item.favorite,
                poster = item.poster ?: existing.poster,
                page = item.page ?: existing.page,
            ) ?: item
            snapshot.copy(items = listOf(result) + snapshot.items.filterNot { it.id == item.id })
        }
        return result
    }

    fun remove(url: String) = mutate { snapshot ->
        val item = snapshot.find(url) ?: return@mutate snapshot
        snapshot.copy(
            items = snapshot.items.filterNot { it.id == item.id },
            progress = snapshot.progress.filterKeys { !belongsTo(it, item.url) },
        )
    }

    fun setFavorite(url: String, favorite: Boolean) = edit(url) { it.copy(favorite = favorite) }

    fun rename(url: String, title: String) {
        val value = title.trim()
        if (value.isNotEmpty()) edit(url) { it.copy(title = value) }
    }

    fun clearProgress(url: String) = mutate { snapshot ->
        val item = snapshot.find(url) ?: return@mutate snapshot
        snapshot.copy(
            items = snapshot.items.map { if (it.id == item.id) it.copy(resumeKey = null, resumeLabel = null) else it },
            progress = snapshot.progress.filterKeys { !belongsTo(it, item.url) },
        )
    }

    fun recordProgress(itemUrl: String?, key: String, label: String, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0 && durationMs <= 0) return
        val now = System.currentTimeMillis()
        mutate { snapshot ->
            val progress = (snapshot.progress + (key to Progress(key, positionMs, durationMs, now)))
                .let { all -> if (all.size <= MAX_PROGRESS) all else all.entries.sortedByDescending { it.value.updatedAt }.take(MAX_PROGRESS).associate { it.key to it.value } }
            val id = itemUrl?.let(LibraryItem::identityOf)
            snapshot.copy(
                progress = progress,
                items = snapshot.items.map { if (it.id == id) it.copy(lastPlayedAt = now, resumeKey = key, resumeLabel = label) else it },
            )
        }
    }

    /** Writes pending changes now; used when the player is about to stop. */
    fun flush() {
        pendingWrite?.cancel()
        scope.launch(Dispatchers.IO) { write(state.value) }
    }

    private fun edit(url: String, transform: (LibraryItem) -> LibraryItem) = mutate { snapshot ->
        val id = LibraryItem.identityOf(url)
        snapshot.copy(items = snapshot.items.map { if (it.id == id) transform(it) else it })
    }

    private fun mutate(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        state.update(transform)
        persist()
    }

    private fun persist() {
        pendingWrite?.cancel()
        pendingWrite = scope.launch(Dispatchers.IO) {
            delay(WRITE_DELAY_MS)
            write(state.value)
        }
    }

    private suspend fun write(snapshot: LibrarySnapshot) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val json = encode(snapshot).toString()
            val stream = file.startWrite()
            try {
                stream.write(json.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                Log.w(TAG, "Library write failed", e)
            }
        }
    }

    private class Stored(val items: List<LibraryItem>, val progress: Map<String, Progress>, val migrated: Boolean)

    private fun load(): Stored {
        if (file.baseFile.exists()) {
            return try {
                decode(JSONObject(String(file.readFully(), Charsets.UTF_8)))
            } catch (e: Exception) {
                // Never overwrite a file we could not read: keep it aside for recovery.
                Log.e(TAG, "Library file is damaged", e)
                file.baseFile.copyTo(File(file.baseFile.parentFile, "library.damaged-${System.currentTimeMillis()}.json"), overwrite = true)
                Stored(emptyList(), emptyMap(), migrated = false)
            }
        }
        return migrateLegacy()
    }

    /** Converts the SharedPreferences library of versions before 1.0. */
    private fun migrateLegacy(): Stored {
        val raw = legacy.getString("items", null) ?: return Stored(emptyList(), emptyMap(), migrated = false)
        val now = System.currentTimeMillis()
        val items = mutableListOf<LibraryItem>()
        val progress = mutableMapOf<String, Progress>()
        val positions = legacy.all.filterKeys { it.startsWith("position:") }
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val url = json.optString("url").ifEmpty { continue }
                val kind = SourceKind.of(json.optString("kind"))
                var resumeKey: String? = null
                if (kind == SourceKind.TORRENT) {
                    positions.forEach { (key, value) ->
                        val fileKey = key.removePrefix("position:")
                        val position = value as? Long ?: return@forEach
                        if (fileKey.startsWith("$url|file:") && position > 0) {
                            progress[fileKey] = Progress(fileKey, position, 0, now)
                            resumeKey = fileKey
                        }
                    }
                } else if (json.optLong("position") > 0) {
                    progress[url] = Progress(url, json.optLong("position"), 0, now)
                    resumeKey = url
                }
                items += LibraryItem(
                    url = url,
                    title = json.optString("title").ifEmpty { kind.label },
                    kind = kind,
                    favorite = json.optBoolean("favorite"),
                    addedAt = now - i,
                    lastPlayedAt = if (resumeKey != null) now - i else 0,
                    resumeKey = resumeKey,
                )
            }
        }.onFailure { Log.w(TAG, "Legacy library could not be read", it) }
        return Stored(items.distinctBy { it.id }, progress, migrated = items.isNotEmpty())
    }

    private fun encode(snapshot: LibrarySnapshot) = JSONObject().apply {
        put("version", 2)
        put("items", JSONArray().apply {
            snapshot.items.forEach { item ->
                put(JSONObject().apply {
                    put("url", item.url)
                    put("title", item.title)
                    put("kind", item.kind.id)
                    put("favorite", item.favorite)
                    put("addedAt", item.addedAt)
                    put("lastPlayedAt", item.lastPlayedAt)
                    item.resumeKey?.let { put("resumeKey", it) }
                    item.resumeLabel?.let { put("resumeLabel", it) }
                    item.poster?.let { put("poster", it) }
                    item.page?.let { put("page", it) }
                })
            }
        })
        put("progress", JSONArray().apply {
            snapshot.progress.values.forEach { p ->
                put(JSONObject().apply {
                    put("key", p.key)
                    put("position", p.positionMs)
                    put("duration", p.durationMs)
                    put("updatedAt", p.updatedAt)
                })
            }
        })
    }

    private fun decode(json: JSONObject): Stored {
        val items = json.optJSONArray("items") ?: JSONArray()
        val progress = json.optJSONArray("progress") ?: JSONArray()
        return Stored(
            items = List(items.length()) { i ->
                val o = items.getJSONObject(i)
                LibraryItem(
                    url = o.getString("url"),
                    title = o.optString("title"),
                    kind = SourceKind.of(o.optString("kind")),
                    favorite = o.optBoolean("favorite"),
                    addedAt = o.optLong("addedAt"),
                    lastPlayedAt = o.optLong("lastPlayedAt"),
                    resumeKey = o.optString("resumeKey").ifEmpty { null },
                    resumeLabel = o.optString("resumeLabel").ifEmpty { null },
                    poster = o.optString("poster").ifEmpty { null },
                    page = o.optString("page").ifEmpty { null },
                )
            },
            progress = List(progress.length()) { i ->
                val o = progress.getJSONObject(i)
                Progress(o.getString("key"), o.optLong("position"), o.optLong("duration"), o.optLong("updatedAt"))
            }.associateBy { it.key },
            migrated = false,
        )
    }

    private fun belongsTo(key: String, url: String) = key == url || key.startsWith("$url|file:")

    private companion object {
        const val TAG = "LibraryRepository"
        const val WRITE_DELAY_MS = 400L
        const val MAX_PROGRESS = 600
    }
}
