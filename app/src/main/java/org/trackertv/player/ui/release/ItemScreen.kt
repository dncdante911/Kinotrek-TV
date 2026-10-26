package org.trackertv.player.ui.release

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import org.trackertv.player.app
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.SourceKind
import org.trackertv.player.net.Hit
import org.trackertv.player.net.ReleaseDetails
import org.trackertv.player.net.Source
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.Images
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.remainingLabel
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

/**
 * Something already in the library, shown like a release instead of starting at once: where it
 * stopped, the next episode, the trailer, and what else can be done with it.
 */
@Composable
fun ItemScreen(vm: AppViewModel, search: SearchViewModel, url: String) {
    val library by vm.library.snapshot.collectAsStateWithLifecycle()
    val item = library.find(url)
    if (item == null) {
        // Deleted from here or elsewhere: nothing left to show.
        if (library.loaded) LaunchedEffect(Unit) { vm.back() }
        return
    }
    val sources = LocalContext.current.app.sources
    val details by produceState<ReleaseDetails?>(null, item.page) {
        val hit = pageHit(item) ?: return@produceState
        value = try {
            sources.details(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val info = item.info
    val poster = item.poster ?: details?.poster
    val progress = library.resumeOf(item)?.takeIf { it.durationMs > 0 }
    val unfinished = progress?.takeUnless { it.finished }
    val torrent = item.kind == SourceKind.TORRENT

    val memory = remember(url) { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)

    val actions = listOfNotNull(
        when {
            unfinished != null -> PanelAction("resume", "Продолжить",
                listOfNotNull(item.resumeLabel?.takeIf { it != item.title }, remainingLabel(unfinished.remainingMs)).joinToString(" · "), Icons.Play) {
                vm.play(item, resume = true)
            }
            progress != null && torrent && info.series -> PanelAction("next", "Следующая серия", item.resumeLabel?.let { "после $it" }, Icons.Play) {
                vm.play(item, resume = true)
            }
            else -> null
        },
        if (torrent) {
            PanelAction("files", if (info.series) "Серии" else if (progress != null) "Смотреть сначала" else "Смотреть",
                if (info.series) "список серий и отметки просмотра" else null, if (info.series) Icons.Episodes else Icons.Play) { vm.play(item) }
        } else {
            PanelAction("watch", if (progress != null) "Смотреть сначала" else "Смотреть", item.kind.label, Icons.Play) { vm.play(item, fromStart = true) }
        },
        PanelAction("trailer", "Трейлер", "откроется в YouTube", Icons.Movie) { vm.trailer(info.title, info.year, details?.trailer) },
        PanelAction("favorite", if (item.favorite) "В избранном" else "В избранное", if (item.favorite) "OK — убрать" else "появится на главной",
            if (item.favorite) Icons.Favorite else Icons.FavoriteBorder) { vm.toggleFavorite(item) },
        if (torrent) PanelAction("others", "Другие раздачи", "другое качество и озвучка", Icons.Search) {
            search.query = info.searchQuery
            search.submit(info.searchQuery)
            vm.open(Screen.Search)
        } else null,
        if (progress != null) PanelAction("reset", "Сбросить прогресс", "смотреть как впервые", Icons.History) {
            vm.library.clearProgress(item.url)
            vm.toast("Прогресс сброшен")
        } else null,
        PanelAction("delete", "Удалить из библиотеки", null, Icons.Delete) { vm.confirmDelete(item) { vm.back() } },
        PanelAction("back", "Назад", null, Icons.Back) { vm.back() },
    )

    Box(Modifier.fillMaxSize()) {
        val backdrop = rememberBackdrop(details?.screenshots.orEmpty())
        PageBackdrop(backdrop, Images.tint(poster) ?: Palette.posterTint(info.title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "hero") {
                ReleaseHero(
                    kind = if (torrent) (if (info.series) "СЕРИАЛ" else "ФИЛЬМ") else item.kind.label.uppercase(),
                    title = info.title,
                    original = info.original,
                    facts = listOfNotNull(info.year, details?.country, details?.duration).joinToString(" · "),
                    badges = info.badges + listOfNotNull("HEVC".takeIf { info.hevc }),
                    genres = details?.genres.orEmpty(),
                    synopsis = details?.synopsis,
                    hint = when {
                        unfinished != null -> "Остановились: " + listOfNotNull(item.resumeLabel?.takeIf { it != item.title }, remainingLabel(unfinished.remainingMs)).joinToString(" · ")
                        progress != null -> "Досмотрено: ${item.resumeLabel ?: info.title}"
                        details != null -> "Вниз — описание, актёры и данные раздачи"
                        else -> "Удерживайте OK на карточке в библиотеке — быстрые действия"
                    },
                    actions = actions,
                    memory = memory,
                    firstAction = first,
                    poster = poster,
                    showPoster = backdrop == null,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
            details?.let { sections(it, memory) }
        }
    }
}

/** The tracker page of a library item as a search hit, so its description can be read again. */
private fun pageHit(item: LibraryItem): Hit? {
    val page = item.page?.takeIf { it.startsWith("http") } ?: return null
    return Hit(
        id = page, title = item.title, source = if ("rutracker" in page) Source.RUTRACKER else Source.RUTOR,
        hash = null, page = page, magnet = item.url.takeIf { item.kind == SourceKind.TORRENT },
        size = "", seeds = 0, peers = 0, day = 0, dateLabel = null,
    )
}
