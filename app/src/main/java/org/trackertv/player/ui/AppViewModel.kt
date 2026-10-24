package org.trackertv.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.trackertv.player.VoiceState
import org.trackertv.player.app
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.Occasion
import org.trackertv.player.data.SourceKind
import org.trackertv.player.data.Support
import org.trackertv.player.data.UpdateState
import org.trackertv.player.net.Category
import org.trackertv.player.net.Hit
import org.trackertv.player.player.PlaybackEntry
import org.trackertv.player.player.PlaybackRequest
import org.trackertv.player.ui.components.DialogAction
import org.trackertv.player.ui.components.DialogSpec
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.theme.Icons
import java.util.Locale

enum class Tab(val label: String) { HOME("Главная"), SEARCH("Поиск"), LIBRARY("Библиотека"), SETTINGS("Настройки") }

/** A torrent to open: either a known magnet or a search hit whose page has it. */
data class TorrentRequest(val title: String, val magnet: String? = null, val hit: Hit? = null, val poster: String? = null, val page: String? = null)

sealed interface Screen {
    val tab: Tab
    data object Home : Screen { override val tab = Tab.HOME }
    data object Search : Screen { override val tab = Tab.SEARCH }
    data object Library : Screen { override val tab = Tab.LIBRARY }
    data object Settings : Screen { override val tab = Tab.SETTINGS }
    data class Torrent(val request: TorrentRequest) : Screen { override val tab = Tab.SEARCH }
    /** Description page of a release, styled by the app; "Смотреть" leads to [Torrent]. */
    data class Release(val hit: Hit) : Screen { override val tab = Tab.SEARCH }
    data object Support : Screen { override val tab = Tab.SETTINGS }
}

/** Things only the activity can do. */
sealed interface UiEvent {
    data class Play(val request: PlaybackRequest) : UiEvent
    data object OpenFile : UiEvent
    data object OpenRutracker : UiEvent
    data object VoiceSearch : UiEvent
    data object OpenDisplaySettings : UiEvent
    data object ShowDeviceCaps : UiEvent
    data object AllowInstalls : UiEvent
    data object CancelVoice : UiEvent
}

/** Home rows filled from Rutor; releases of the same video are grouped into one card. */
enum class Shelf(val title: String, val category: Category, val popular: Boolean) {
    POPULAR_MOVIES("Популярные фильмы", Category.MOVIES, true),
    POPULAR_SERIES("Популярные сериалы", Category.SERIES, true),
    NEW_MOVIES("Новинки кино", Category.MOVIES, false),
    CARTOONS("Мультфильмы", Category.CARTOONS, true),
}

data class ShelfEntry(val key: String, val title: String, val year: String?, val badges: List<String>, val seeds: Int, val releases: Int, val query: String, val hit: Hit)

data class ShelfState(val loading: Boolean = true, val entries: List<ShelfEntry> = emptyList(), val failed: Boolean = false)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    val library = app.library
    val settings = app.settings

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    var dialog by mutableStateOf<DialogSpec?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var messageJob: Job? = null

    val shelves = mutableStateMapOf<Shelf, ShelfState>()
    private var shelvesJob: Job? = null

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    val homeFocus = FocusMemory()
    var voice by mutableStateOf<VoiceState>(VoiceState.Off)
    val updater = app.updater
    private var remindedThisLaunch = false

    /** Settings → "Посмотреть оформление": shows any decoration until the app restarts. */
    var decorPreview by mutableStateOf<Occasion?>(null)

    /**
     * A thank-you note on the home screen after every ~10 hours of watching, at most once per app
     * launch. The player never shows it: this runs only when the home screen opens.
     */
    fun maybeRemindSupport() {
        if (remindedThisLaunch) return
        remindedThisLaunch = true
        val s = settings.current
        if (!Support.available || dialog != null) return
        if (s.watchedMinutes - s.supportShownAtMinutes < Support.REMINDER_AFTER_MINUTES) return
        settings.markSupportReminderShown()
        showDialog(DialogSpec.Actions(
            title = "Нравится Кинотрек?",
            message = "С приложением уже ${s.watchedMinutes / 60} ч просмотра — спасибо! Кинотрек бесплатный и без рекламы. " +
                "Если хотите, можно поддержать автора: QR-код откроется на экране, оплатить можно телефоном.",
            actions = listOf(DialogAction("Поддержать автора", Icons.Favorite, primary = true) { open(Screen.Support) }),
        ))
    }

    /** "Обновить": confirm with the release notes, ask for the install permission once if needed. */
    fun installUpdate() {
        val ready = updater.state.value as? UpdateState.Ready ?: return
        if (!updater.canInstall()) {
            showDialog(DialogSpec.Actions(
                title = "Разрешите установку обновлений",
                message = "Android один раз попросит разрешить Кинотреку устанавливать приложения. Включите переключатель и вернитесь — обновление продолжится.",
                actions = listOf(DialogAction("Открыть настройки", Icons.Settings, primary = true) { send(UiEvent.AllowInstalls) }),
            ))
            return
        }
        showDialog(DialogSpec.Actions(
            title = "Обновить до ${ready.release.version}?",
            message = ready.release.notes.ifBlank { "Новая версия Кинотрека." }.take(900) +
                "\n\nПриложение закроется на пару секунд. Библиотека, история и настройки сохранятся.",
            actions = listOf(DialogAction("Установить", Icons.ArrowUp, primary = true) { updater.install() }),
        ))
    }

    val libraryFocus = FocusMemory()

    init {
        loadShelves()
    }

    fun open(screen: Screen) {
        if (screen == current) return
        when (screen) {
            is Screen.Torrent, is Screen.Release, Screen.Support -> stack.add(screen)
            else -> {
                stack.clear()
                stack.add(Screen.Home)
                if (screen != Screen.Home) stack.add(screen)
            }
        }
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun showDialog(spec: DialogSpec) { dialog = spec }
    fun dismissDialog() { dialog = null }

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3_000)
            message = null
        }
    }

    fun send(event: UiEvent) { events.trySend(event) }

    fun loadShelves(force: Boolean = false) {
        if (shelvesJob?.isActive == true && !force) return
        shelvesJob = viewModelScope.launch {
            Shelf.entries.forEach { if (force || shelves[it]?.entries.isNullOrEmpty()) shelves[it] = ShelfState() }
            Shelf.entries.map { shelf ->
                launch {
                    val result = app.sources.browse(shelf.category, shelf.popular)
                    shelves[shelf] = ShelfState(loading = false, entries = group(result.hits, shelf.popular), failed = result.hits.isEmpty())
                }
            }
        }
    }

    private fun group(hits: List<Hit>, popular: Boolean): List<ShelfEntry> = hits
        .groupBy { "${it.info.title.lowercase(Locale.ROOT)}|${it.info.year}" }
        .map { (key, releases) ->
            val best = releases.maxBy { it.seeds }
            val quality = releases.mapNotNull { it.info.quality }.maxOrNull()
            ShelfEntry(
                key = key,
                title = best.info.title,
                year = best.info.year,
                badges = listOfNotNull(quality?.label, if (releases.any { it.info.hdr || it.info.dolbyVision }) "HDR" else null),
                seeds = releases.sumOf { it.seeds },
                releases = releases.size,
                query = best.info.searchQuery,
                hit = best,
            )
        }
        .let { entries -> if (popular) entries.sortedByDescending { it.seeds } else entries }
        .take(SHELF_SIZE)

    // Library actions

    fun play(item: LibraryItem) {
        if (item.kind == SourceKind.TORRENT) {
            open(Screen.Torrent(TorrentRequest(item.title, magnet = item.url, poster = item.poster, page = item.page)))
            return
        }
        val progress = library.current.progress[item.url]?.takeUnless { it.finished }
        send(UiEvent.Play(PlaybackRequest(item.url, listOf(PlaybackEntry(item.title, item.url, item.url)), startPositionMs = progress?.positionMs ?: 0)))
    }

    fun toggleFavorite(item: LibraryItem) {
        library.setFavorite(item.url, !item.favorite)
        toast(if (item.favorite) "Убрано из избранного" else "Добавлено в избранное")
    }

    fun itemActions(item: LibraryItem) {
        val hasProgress = item.resumeKey != null
        showDialog(DialogSpec.Actions(
            title = item.title,
            message = item.kind.label,
            actions = listOfNotNull(
                DialogAction("Смотреть", Icons.Play, primary = true) { play(item) },
                DialogAction(if (item.favorite) "Убрать из избранного" else "В избранное", if (item.favorite) Icons.FavoriteBorder else Icons.Favorite) { toggleFavorite(item) },
                if (hasProgress) DialogAction("Сбросить прогресс", Icons.History) { library.clearProgress(item.url); toast("Прогресс сброшен") } else null,
                DialogAction("Переименовать", Icons.Edit) { rename(item) },
                DialogAction("Удалить из библиотеки", Icons.Delete) { confirmDelete(item) },
            ),
        ))
    }

    private fun rename(item: LibraryItem) = showDialog(DialogSpec.Input(
        title = "Название",
        initial = item.title,
        placeholder = "Название видео",
        confirmLabel = "Сохранить",
    ) { library.rename(item.url, it) })

    private fun confirmDelete(item: LibraryItem) = showDialog(DialogSpec.Actions(
        title = "Удалить «${item.info.title}»?",
        message = "Видео исчезнет из библиотеки вместе с прогрессом просмотра.",
        actions = listOf(DialogAction("Удалить", Icons.Delete) {
            library.remove(item.url)
            toast("Удалено")
        }),
    ))

    /** Search results only have a page link for RuTracker, so the magnet may need a request first. */
    fun favoriteHit(hit: Hit) {
        viewModelScope.launch {
            try {
                val magnet = app.sources.magnet(hit)
                val existing = library.current.find(magnet) ?: library.current.find(hit)
                if (existing?.favorite == true) {
                    library.setFavorite(existing.url, false)
                    toast("Убрано из избранного")
                } else {
                    library.upsert(LibraryItem(magnet, hit.title, SourceKind.TORRENT, favorite = true, poster = app.sources.knownPoster(hit), page = hit.page))
                    toast("Добавлено в избранное")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Не удалось сохранить: ${e.message}")
            }
        }
    }

    fun addLink() = showDialog(DialogSpec.Input(
        title = "Добавить по ссылке",
        message = "Magnet-ссылка, прямая ссылка на видео или поток HLS/DASH.",
        initial = "",
        placeholder = "magnet:?xt=… или https://…",
        confirmLabel = "Добавить",
    ) { value -> openLink(value.trim(), null) })

    /** Links typed by the user or sent by other apps. */
    fun openLink(url: String, title: String?) {
        val kind = when {
            url.startsWith("magnet:?", ignoreCase = true) -> SourceKind.TORRENT
            url.startsWith("http://") || url.startsWith("https://") -> SourceKind.STREAM
            url.startsWith("content:") || url.startsWith("file:") -> SourceKind.FILE
            else -> {
                toast("Нужна magnet-ссылка или адрес http(s)")
                return
            }
        }
        val name = title?.takeIf { it.isNotBlank() } ?: nameFromUrl(url, kind)
        val item = library.upsert(LibraryItem(url, name, kind))
        if (kind == SourceKind.TORRENT) open(Screen.Torrent(TorrentRequest(item.title, magnet = item.url))) else play(item)
    }

    fun addFile(uri: Uri, name: String) {
        val item = library.upsert(LibraryItem(uri.toString(), name.substringBeforeLast('.').ifEmpty { name }, SourceKind.FILE))
        toast("Файл добавлен в библиотеку")
        play(item)
    }

    private fun nameFromUrl(url: String, kind: SourceKind): String = when (kind) {
        SourceKind.TORRENT -> url.replace("magnet:?", "magnet:/?").toUri().getQueryParameter("dn")?.takeIf { it.isNotBlank() && !it.contains("rutor") } ?: "Раздача"
        else -> url.toUri().lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Видео"
    }

    companion object {
        private const val SHELF_SIZE = 20
    }
}
