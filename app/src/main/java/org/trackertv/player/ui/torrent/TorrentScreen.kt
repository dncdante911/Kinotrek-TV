package org.trackertv.player.ui.torrent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import org.trackertv.player.data.LibrarySnapshot
import org.trackertv.player.data.Progress
import org.trackertv.player.net.VideoFile
import org.trackertv.player.ui.components.Images
import org.trackertv.player.ui.components.TvChip
import org.trackertv.player.ui.release.PageBackdrop
import org.trackertv.player.ui.release.PanelAction
import org.trackertv.player.ui.release.PanelRow
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

/** A file of the torrent with the season and episode read from its name, when it has them. */
internal class Episode(val index: Int, val file: VideoFile) {
    private val match = SEASON_EPISODE.find(file.label)
    val season: Int = match?.groupValues?.get(1)?.toInt() ?: NO_SEASON
    val number: Int? = match?.groupValues?.get(2)?.toInt()
}

private val SEASON_EPISODE = Regex("^S(\\d{2})E(\\d{2,3})$")
private const val NO_SEASON = -1

/**
 * Opens a torrent and lists its files, after the «Зеркало» season screen: the release and what to
 * do on the left, seasons and episodes on the right. A single file, or "Продолжить" from the
 * item page, starts playing as soon as the torrent is open.
 */
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
    val ready = state as? TorrentState.Ready
    val info = ready?.item?.info ?: org.trackertv.player.data.ReleaseInfo.parse(request.title)
    val poster = ready?.item?.poster ?: request.poster ?: request.hit?.let(context.app.sources::knownPoster)
        ?: request.magnet?.let { library.find(it)?.poster }

    fun play(ready: TorrentState.Ready, index: Int) {
        val files = ready.info.files
        val entries = files.map { file ->
            val title = if (files.size > 1) "${ready.item.info.title} · ${file.label}" else ready.item.info.title
            PlaybackEntry(title, ready.client.streamUrl(ready.info.hash, file), LibraryItem.fileKey(ready.item.url, file.id))
        }
        val progress = library.progress[entries[index].key]?.takeUnless { it.finished }
        vm.send(UiEvent.Play(PlaybackRequest(ready.item.url, entries, index, progress?.positionMs ?: 0, torrent = true)))
    }

    // Where the user stopped, and what "OK" should start: that file again, or the next one.
    val resumeKey = ready?.let { library.find(it.item.url)?.resumeKey }
    val resumeIndex = ready?.info?.files?.indexOfFirst { LibraryItem.fileKey(ready.item.url, it.id) == resumeKey } ?: -1
    val resumeProgress = resumeKey?.let(library.progress::get)
    val target = when {
        ready == null -> -1
        resumeIndex >= 0 && resumeProgress?.finished != true -> resumeIndex
        resumeIndex >= 0 && resumeIndex + 1 < ready.info.files.size -> resumeIndex + 1
        else -> 0
    }

    LaunchedEffect(state) {
        if (state !is TorrentState.Ready || session.autoPlayed) return@LaunchedEffect
        if (state.info.files.size == 1 || (request.resume && resumeIndex >= 0)) {
            session.autoPlayed = true
            play(state, if (state.info.files.size == 1) 0 else target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        PageBackdrop(null, Images.tint(poster) ?: Palette.posterTint(info.title))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(430.dp).fillMaxHeight().padding(start = 48.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    PosterArt(info.title, info.title, Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                        letterSize = 64, imageUrl = poster, imageWidthPx = 240)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (info.series) "СЕРИАЛ" else "РАЗДАЧА", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                        Text(info.title, style = MaterialTheme.typography.headlineSmall, color = Palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        listOfNotNull(info.original, info.year).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            info.badges.forEach { Badge(it, color = Palette.accentDim, contentColor = Palette.accent) }
                            if (info.hevc) Badge("HEVC")
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val actions = when (state) {
                    is TorrentState.Preparing -> listOf(PanelAction("cancel", "Отмена", null, Icons.Close) { vm.back() })
                    is TorrentState.Failed -> listOf(
                        PanelAction("retry", "Повторить", null, Icons.Refresh) { session.start() },
                        PanelAction("others", "Другие раздачи", null, Icons.Search) {
                            vm.back()
                            vm.open(Screen.Search)
                        },
                        PanelAction("server", "Настройки сервера", null, Icons.Settings) { vm.open(Screen.Settings) },
                    )
                    is TorrentState.Ready -> {
                        val files = state.info.files
                        val favorite = library.find(state.item.url)?.favorite == true
                        listOfNotNull(
                            when {
                                resumeIndex >= 0 && resumeProgress != null && !resumeProgress.finished -> PanelAction("resume", "Продолжить",
                                    "${files[resumeIndex].label} · ${PlayerActivity.formatTime(resumeProgress.positionMs)}", Icons.Play) { play(state, resumeIndex) }
                                target > 0 -> PanelAction("resume", "Следующая", files[target].label, Icons.Play) { play(state, target) }
                                else -> PanelAction("resume", if (files.size > 1) "С первой серии" else "Смотреть", files.first().label, Icons.Play) { play(state, 0) }
                            },
                            PanelAction("trailer", "Трейлер", "откроется в YouTube", Icons.Movie) { vm.trailer(info.title, info.year, null) },
                            PanelAction("favorite", if (favorite) "В избранном" else "В избранное", null, if (favorite) Icons.Favorite else Icons.FavoriteBorder) {
                                vm.toggleFavorite(library.find(state.item.url) ?: state.item)
                            },
                            PanelAction("back", "Назад", null, Icons.Back) { vm.back() },
                        )
                    }
                }
                actions.forEachIndexed { index, action ->
                    PanelRow(action, Modifier.remembered(memory, action.key).let { if (index == 0) it.focusRequester(primary) else it })
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(end = 48.dp)) {
                when (state) {
                    is TorrentState.Preparing -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    }
                    is TorrentState.Failed -> Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.85f)) {
                        Text("Раздача не открылась", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                    }
                    is TorrentState.Ready -> Episodes(state, library, memory, resumeIndex, ::play)
                }
            }
        }
    }
    // While preparing or after an error the first action has focus; the file list restores its own.
    if (state is TorrentState.Ready) InitialFocus(memory, primary) else LaunchedEffect(state::class) { runCatching { primary.requestFocus() } }
}

@Composable
private fun Episodes(
    state: TorrentState.Ready,
    library: LibrarySnapshot,
    memory: FocusMemory,
    resumeIndex: Int,
    play: (TorrentState.Ready, Int) -> Unit,
) {
    val files = state.info.files
    val episodes = remember(files) { files.mapIndexed { index, file -> Episode(index, file) } }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sortedWith(compareBy { if (it == NO_SEASON) Int.MAX_VALUE else it }) }
    var season by rememberSaveable(state.info.hash) {
        mutableIntStateOf(episodes.getOrNull(resumeIndex)?.season ?: seasons.first())
    }
    val shown = if (seasons.size > 1) episodes.filter { it.season == season } else episodes

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (seasons.size > 1) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    items(seasons) { value ->
                        TvChip(if (value == NO_SEASON) "Другое" else "Сезон $value", selected = value == season, onClick = { season = value },
                            modifier = Modifier.remembered(memory, "season:$value"))
                    }
                }
            } else {
                Text(if (state.item.info.series) "Серии" else "Файлы", style = MaterialTheme.typography.titleLarge, color = Palette.text, modifier = Modifier.weight(1f))
            }
            Text(plural(files.size, "файл", "файла", "файлов") + " · " + sizeLabel(files.sumOf { it.length }),
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
            items(shown, key = { it.file.id }) { episode ->
                EpisodeRow(
                    episode,
                    library.progress[LibraryItem.fileKey(state.item.url, episode.file.id)],
                    current = episode.index == resumeIndex,
                    multiple = files.size > 1,
                    onClick = { play(state, episode.index) },
                    modifier = Modifier.remembered(memory, "file:${episode.file.id}"),
                )
            }
        }
    }
}

@Composable
internal fun EpisodeRow(episode: Episode, progress: Progress?, current: Boolean, multiple: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val file = episode.file
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Palette.surfaceHigh.copy(alpha = 0.8f) else Palette.surface.copy(alpha = 0.55f),
            contentColor = Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
    ) {
        val dim = if (focused) Palette.onFocus.copy(alpha = 0.6f) else Palette.muted
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val finished = progress?.finished == true
            // Where the user stopped, or what is already seen; the title carries the number.
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    finished -> Icon(Icons.Check, "Просмотрено", tint = if (focused) Palette.onFocus else Palette.good, modifier = Modifier.size(22.dp))
                    current -> Icon(Icons.Play, "Остановились здесь", tint = if (focused) Palette.onFocus else Palette.accent, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    episode.number?.let { "$it серия" } ?: "${episode.index + 1}. ${file.label}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (finished && !focused) Palette.muted else LocalContentColor.current,
                )
                val folder = file.folder?.takeIf { multiple }
                Text(
                    listOfNotNull(if (episode.number != null || file.label != file.name) file.name else null, folder).joinToString(" · ").ifEmpty { file.fileName },
                    style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (progress != null && progress.durationMs > 0 && !finished) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(progress.fraction, Modifier.fillMaxWidth(0.6f))
                }
            }
            Spacer(Modifier.width(14.dp))
            when {
                finished -> Text("Просмотрено", style = MaterialTheme.typography.bodySmall, color = if (focused) Palette.onFocus else Palette.good)
                progress != null && progress.durationMs > 0 -> Text(remainingLabel(progress.remainingMs), style = MaterialTheme.typography.bodySmall, color = dim)
            }
            Spacer(Modifier.width(18.dp))
            Text(sizeLabel(file.length), style = MaterialTheme.typography.bodyMedium, color = dim)
        }
    }
}
