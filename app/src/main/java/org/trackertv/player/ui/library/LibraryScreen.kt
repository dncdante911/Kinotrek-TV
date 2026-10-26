package org.trackertv.player.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.LibrarySnapshot
import org.trackertv.player.data.SourceKind
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.EmptyState
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.MediaCard
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.TvChip
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.remainingLabel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

private enum class LibraryFilter(val label: String) {
    ALL("Все"), FAVORITES("Избранное"), CONTINUE("Не досмотрено"), TORRENTS("Раздачи"), STREAMS("Ссылки"), FILES("Файлы");

    fun matches(item: LibraryItem, snapshot: LibrarySnapshot): Boolean = when (this) {
        ALL -> true
        FAVORITES -> item.favorite
        CONTINUE -> snapshot.resumeOf(item)?.let { !it.finished } == true
        TORRENTS -> item.kind == SourceKind.TORRENT
        STREAMS -> item.kind == SourceKind.STREAM
        FILES -> item.kind == SourceKind.FILE
    }
}

private enum class LibrarySort(val label: String) { RECENT("Недавние"), TITLE("По названию") }

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val snapshot by vm.library.snapshot.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    val memory = vm.libraryFocus
    val firstChip = remember { FocusRequester() }
    InitialFocus(memory, firstChip)

    val items = remember(snapshot, filter, sort) {
        snapshot.items.filter { filter.matches(it, snapshot) }.let { list ->
            when (sort) {
                LibrarySort.RECENT -> list.sortedByDescending { maxOf(it.addedAt, it.lastPlayedAt) }
                LibrarySort.TITLE -> list.sortedBy { it.info.title.lowercase() }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Моя библиотека", style = MaterialTheme.typography.headlineMedium, color = Palette.text)
                Text(plural(snapshot.items.size, "видео", "видео", "видео"), style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                Spacer(Modifier.weight(1f))
                TvButton("Ссылка", icon = Icons.Link, onClick = vm::addLink, modifier = Modifier.remembered(memory, "add:link"))
                TvButton("Файл", icon = Icons.Folder, onClick = { vm.send(UiEvent.OpenFile) }, modifier = Modifier.remembered(memory, "add:file"))
            }
        }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryFilter.entries.forEachIndexed { index, value ->
                    TvChip(value.label, selected = filter == value, onClick = { filter = value },
                        modifier = Modifier.remembered(memory, "filter:${value.name}").then(if (index == 0) Modifier.focusRequester(firstChip) else Modifier))
                }
                Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 36.dp).background(Palette.surfaceHigher))
                LibrarySort.entries.forEach { value ->
                    TvChip(value.label, selected = sort == value, onClick = { sort = value }, modifier = Modifier.remembered(memory, "sort:${value.name}"))
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                if (snapshot.items.isEmpty()) {
                    EmptyState(Icons.Library, "Здесь будут ваши видео", "Всё, что вы смотрите или отмечаете сердечком, попадает в библиотеку.") {
                        TvButton("Найти фильм", icon = Icons.Search, primary = true, onClick = { vm.open(Screen.Search) }, modifier = Modifier.remembered(memory, "empty:search"))
                        TvButton("Добавить ссылку", icon = Icons.Link, onClick = vm::addLink, modifier = Modifier.remembered(memory, "empty:link"))
                    }
                } else {
                    EmptyState(Icons.Info, "В этом разделе пусто", "Выберите другой фильтр.")
                }
            }
        } else {
            items(items, key = { it.id }) { item ->
                val progress = snapshot.resumeOf(item)
                MediaCard(
                    title = item.info.title,
                    subtitle = when {
                        progress != null && progress.finished -> "Просмотрено"
                        progress != null && progress.durationMs > 0 -> remainingLabel(progress.remainingMs)
                        else -> listOfNotNull(item.info.year, item.kind.label).joinToString(" · ")
                    },
                    seed = item.info.title,
                    badges = item.info.badges,
                    progress = progress?.takeIf { !it.finished && it.durationMs > 0 }?.fraction,
                    favorite = item.favorite,
                    width = 160.dp,
                    portrait = true,
                    imageUrl = item.poster,
                    onClick = { vm.openItem(item) },
                    onLongClick = { vm.itemActions(item) },
                    modifier = Modifier.fillMaxWidth().remembered(memory, item.id),
                )
            }
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text("Удерживайте OK на карточке, чтобы переименовать, удалить или сбросить прогресс.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.faint)
            }
        }
    }
}
