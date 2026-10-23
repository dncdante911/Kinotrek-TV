package org.trackertv.player.ui.torrent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.trackertv.player.PlayerActivity
import org.trackertv.player.TrackerApp
import org.trackertv.player.app
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.SourceKind
import org.trackertv.player.net.TorrServerClient
import org.trackertv.player.net.TorrentInfo
import org.trackertv.player.net.TorrentStatus
import org.trackertv.player.player.PlaybackEntry
import org.trackertv.player.player.PlaybackRequest
import org.trackertv.player.torrent.LocalTorrentService
import org.trackertv.player.torrent.TorrentEngine
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.TorrentRequest
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.Badge
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.ListRow
import org.trackertv.player.ui.components.PosterArt
import org.trackertv.player.ui.components.ProgressLine
import org.trackertv.player.ui.components.Spinner
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.remainingLabel
import org.trackertv.player.ui.sizeLabel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

private sealed interface TorrentState {
    data class Preparing(val step: String, val status: TorrentStatus? = null) : TorrentState
    data class Ready(val client: TorrServerClient, val info: TorrentInfo, val item: LibraryItem) : TorrentState
    data class Failed(val message: String) : TorrentState
}

/** Opens a torrent: magnet, engine, metadata, then the file list. Leaving the screen cancels it all. */
private class TorrentSession(private val app: TrackerApp, private val request: TorrentRequest, private val scope: CoroutineScope) {
    var state by mutableStateOf<TorrentState>(TorrentState.Preparing("Подготовка…"))
        private set
    var autoPlayed = false
    private var job: Job? = null

    fun start() {
        job?.cancel()
        state = TorrentState.Preparing("Подготовка…")
        job = scope.launch {
            try {
                val magnet = request.magnet ?: run {
                    state = TorrentState.Preparing("Получаем magnet-ссылку…")
                    app.sources.magnet(requireNotNull(request.hit))
                }
                val poster = request.poster ?: request.hit?.let(app.sources::knownPoster)
                val item = app.library.upsert(LibraryItem(magnet, request.title, SourceKind.TORRENT, poster = poster, page = request.page ?: request.hit?.page))
                val client = TorrentEngine.connect(app, app.settings.current.server) { state = TorrentState.Preparing(it) }
                state = TorrentState.Preparing("Получаем список файлов…")
                val info = client.open(item.url, item.title) { state = TorrentState.Preparing("Ищем пиров и получаем список файлов…", it) }
                state = if (info.files.isEmpty()) {
                    TorrentState.Failed("В раздаче нет видеофайлов, которые можно воспроизвести.")
                } else TorrentState.Ready(client, info, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = TorrentState.Failed(e.message ?: "Не удалось открыть раздачу.")
            }
        }
    }
}

@Composable
fun TorrentScreen(vm: AppViewModel, request: TorrentRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(request) { TorrentSession(context.app, request, scope).also { it.start() } }
    val library by vm.library.snapshot.collectAsStateWithLifecycle()
    val memory = remember(request) { FocusMemory() }
    val primary = remember { FocusRequester() }

    // Keep the engine alive while the user picks a file.
    DisposableEffect(Unit) {
        LocalTorrentService.acquire()
        onDispose { LocalTorrentService.release() }
    }

    val state = session.state
    val info = (state as? TorrentState.Ready)?.item?.info ?: org.trackertv.player.data.ReleaseInfo.parse(request.title)

    fun play(ready: TorrentState.Ready, index: Int) {
        val files = ready.info.files
        val entries = files.map { file ->
            val title = if (files.size > 1) "${ready.item.info.title} · ${file.label}" else ready.item.info.title
            PlaybackEntry(title, ready.client.streamUrl(ready.info.hash, file), LibraryItem.fileKey(ready.item.url, file.id))
        }
        val progress = library.progress[entries[index].key]?.takeUnless { it.finished }
        vm.send(UiEvent.Play(PlaybackRequest(ready.item.url, entries, index, progress?.positionMs ?: 0, torrent = true)))
    }

    // A single-file release needs no choice: start it right away, once.
    LaunchedEffect(state) {
        if (state is TorrentState.Ready && state.info.files.size == 1 && !session.autoPlayed) {
            session.autoPlayed = true
            play(state, 0)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val poster = (state as? TorrentState.Ready)?.item?.poster ?: request.poster ?: request.hit?.let(context.app.sources::knownPoster)
                    ?: request.magnet?.let { library.find(it)?.poster }
                if (poster != null) {
                    PosterArt(info.title, info.title, Modifier.size(width = 100.dp, height = 150.dp).clip(RoundedCornerShape(14.dp)), letterSize = 72,
                        imageUrl = poster, imageWidthPx = 240)
                } else {
                    PosterArt(info.title, info.title, Modifier.size(width = 240.dp, height = 135.dp).clip(RoundedCornerShape(18.dp)), letterSize = 96)
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.title, style = MaterialTheme.typography.headlineMedium, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(info.original, info.year).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, maxLines = 1)
                    }
                    if (info.details.isNotEmpty()) Text(info.details, style = MaterialTheme.typography.bodySmall, color = Palette.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        info.badges.forEach { Badge(it, color = Palette.accentDim, contentColor = Palette.accent) }
                        if (info.hevc) Badge("HEVC")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        when (state) {
            is TorrentState.Preparing -> item(key = "preparing") {
                Column(
                    Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(24.dp)).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spinner(size = 56.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(state.step, style = MaterialTheme.typography.titleLarge, color = Palette.text)
                    state.status?.let { status ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Пиров: ${status.peers} · сидов: ${status.seeders}" + if (status.speedBytes > 0) " · ${sizeLabel(status.speedBytes)}/с" else "",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Обычно это занимает 5–30 секунд.", style = MaterialTheme.typography.bodySmall, color = Palette.faint)
                    Spacer(Modifier.height(22.dp))
                    TvButton("Отмена", icon = Icons.Close, onClick = { vm.back() }, modifier = Modifier.focusRequester(primary))
                }
                LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
            }

            is TorrentState.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(24.dp)).padding(32.dp)) {
                    Text("Раздача не открылась", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Повторить", icon = Icons.Refresh, primary = true, onClick = session::start, modifier = Modifier.focusRequester(primary))
                        TvButton("Другие раздачи", icon = Icons.Search, onClick = {
                            vm.back()
                            vm.open(Screen.Search)
                        })
                        TvButton("Настройки сервера", icon = Icons.Settings, onClick = { vm.open(Screen.Settings) })
                    }
                }
                LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
            }

            is TorrentState.Ready -> {
                val files = state.info.files
                val resumeKey = library.find(state.item.url)?.resumeKey
                val resumeIndex = files.indexOfFirst { LibraryItem.fileKey(state.item.url, it.id) == resumeKey }
                val resumeProgress = resumeKey?.let(library.progress::get)
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (resumeIndex >= 0 && resumeProgress != null && !resumeProgress.finished) {
                            TvButton(
                                "Продолжить: ${files[resumeIndex].label.take(32)} · ${PlayerActivity.formatTime(resumeProgress.positionMs)}",
                                icon = Icons.Play, primary = true, onClick = { play(state, resumeIndex) },
                                modifier = Modifier.focusRequester(primary).remembered(memory, "resume"),
                            )
                        } else {
                            val next = if (resumeIndex >= 0 && resumeIndex + 1 < files.size) resumeIndex + 1 else 0
                            TvButton(
                                if (next > 0) "Следующий: ${files[next].label.take(32)}" else if (files.size > 1) "Смотреть с первого файла" else "Смотреть",
                                icon = Icons.Play, primary = true, onClick = { play(state, next) },
                                modifier = Modifier.focusRequester(primary).remembered(memory, "resume"),
                            )
                        }
                        val favorite = library.find(state.item.url)?.favorite == true
                        TvButton(
                            if (favorite) "В избранном" else "В избранное",
                            icon = if (favorite) Icons.Favorite else Icons.FavoriteBorder,
                            onClick = { vm.toggleFavorite(library.find(state.item.url) ?: state.item) },
                            modifier = Modifier.remembered(memory, "favorite"),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(plural(files.size, "видеофайл", "видеофайла", "видеофайлов") + " · " + sizeLabel(files.sumOf { it.length }),
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                    val progress = library.progress[LibraryItem.fileKey(state.item.url, file.id)]
                    ListRow(onClick = { play(state, index) }, modifier = Modifier.remembered(memory, "file:${file.id}")) {
                        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = Palette.faint, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val folder = file.folder?.takeIf { files.size > 1 }
                            Text(
                                listOfNotNull(if (file.label != file.name) file.name else null, folder).joinToString(" · ").ifEmpty { file.fileName },
                                style = MaterialTheme.typography.bodySmall, color = Palette.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (progress != null && progress.durationMs > 0 && !progress.finished) {
                                Spacer(Modifier.height(6.dp))
                                ProgressLine(progress.fraction, Modifier.fillMaxWidth(0.6f))
                            }
                        }
                        when {
                            progress?.finished == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Check, null, tint = Palette.good, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Просмотрено", style = MaterialTheme.typography.bodySmall, color = Palette.good)
                            }
                            progress != null && progress.durationMs > 0 -> Text(remainingLabel(progress.remainingMs), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                        }
                        Text(sizeLabel(file.length), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    }
                }
                item(key = "focus") { InitialFocus(memory, primary) }
            }
        }
    }
}
