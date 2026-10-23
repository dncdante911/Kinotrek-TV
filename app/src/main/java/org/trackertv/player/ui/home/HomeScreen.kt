package org.trackertv.player.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.LibrarySnapshot
import org.trackertv.player.data.UpdateState
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.Shelf
import org.trackertv.player.ui.ShelfEntry
import org.trackertv.player.ui.ShelfState
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.MediaCard
import org.trackertv.player.ui.components.SectionHeader
import org.trackertv.player.ui.components.SeedsLabel
import org.trackertv.player.ui.components.SkeletonCard
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.remainingLabel
import org.trackertv.player.ui.search.SearchMode
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.release.rememberPoster
import org.trackertv.player.ui.theme.Palette
import java.util.Calendar

private val RowPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp)
private val CardWidth = 170.dp

@Composable
fun HomeScreen(vm: AppViewModel, search: SearchViewModel, voiceAvailable: Boolean) {
    val library by vm.library.snapshot.collectAsStateWithLifecycle()
    val searchButton = remember { FocusRequester() }
    val memory = vm.homeFocus
    InitialFocus(memory, searchButton)
    val update by vm.updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.maybeRemindSupport()
        vm.updater.checkIfDue()
    }

    fun openSearch(mode: SearchMode) {
        search.selectMode(mode)
        vm.open(Screen.Search)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        if (update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Ready || update is UpdateState.Installing) item(key = "update") {
            UpdateBanner(update, onDownload = vm.updater::downloadNow, onInstall = vm::installUpdate,
                modifier = Modifier.padding(horizontal = 48.dp), buttonModifier = Modifier.remembered(memory, "update"))
        }
        item(key = "hero") {
            Column(
                Modifier
                    .padding(horizontal = 48.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.hero)
                    .drawBehind { heroArt() }
                    .padding(horizontal = 36.dp, vertical = 30.dp),
            ) {
                Text(greeting(), style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                Spacer(Modifier.height(8.dp))
                Text("Что посмотрим сегодня?", style = MaterialTheme.typography.displaySmall, color = Palette.text)
                Spacer(Modifier.height(6.dp))
                Text("Фильмы и сериалы с торрент-трекеров — просмотр начинается сразу, без скачивания.", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Найти фильм или сериал", icon = Icons.Search, primary = true,
                        onClick = { openSearch(SearchMode.SEARCH) },
                        modifier = Modifier.focusRequester(searchButton).remembered(memory, "hero:search"))
                    if (voiceAvailable) {
                        TvButton("Голосом", icon = Icons.Mic, onClick = { vm.send(UiEvent.VoiceSearch) },
                            modifier = Modifier.remembered(memory, "hero:voice"))
                    }
                    TvButton("Новинки", icon = Icons.Star, onClick = { openSearch(SearchMode.LATEST) },
                        modifier = Modifier.remembered(memory, "hero:latest"))
                    TvButton("Популярное", icon = Icons.Trending, onClick = { openSearch(SearchMode.POPULAR) },
                        modifier = Modifier.remembered(memory, "hero:popular"))
                }
            }
        }

        val continueWatching = library.continueWatching
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                LibraryRow("Продолжить просмотр", null, continueWatching, library, vm, "continue")
            }
        }
        val favorites = library.items.filter { it.favorite }
        if (favorites.isNotEmpty()) {
            item(key = "favorites") { LibraryRow("Избранное", null, favorites, library, vm, "favorites") }
        }

        Shelf.entries.forEach { shelf ->
            item(key = shelf.name) {
                ShelfRow(shelf, vm.shelves[shelf] ?: ShelfState(), vm) { entry -> vm.open(Screen.Release(entry.hit)) }
            }
        }

        val recent = library.items.filter { !it.favorite && it.resumeKey == null }.sortedByDescending { it.addedAt }.take(15)
        if (recent.isNotEmpty()) {
            item(key = "recent") { LibraryRow("Недавно добавленные", null, recent, library, vm, "recent") }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String?, items: List<LibraryItem>, library: LibrarySnapshot, vm: AppViewModel, row: String) {
    Column {
        SectionHeader(title, Modifier.padding(horizontal = 48.dp), subtitle)
        LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(items.take(20), key = { it.id }) { item ->
                val progress = library.resumeOf(item)
                val caption = when {
                    progress != null && !progress.finished && progress.durationMs > 0 ->
                        listOfNotNull(item.resumeLabel?.takeIf { it != item.title }, remainingLabel(progress.remainingMs)).joinToString(" · ")
                    progress != null && progress.finished -> "Просмотрено"
                    else -> listOfNotNull(item.info.year, item.kind.label).joinToString(" · ")
                }
                MediaCard(
                    title = item.info.title,
                    subtitle = caption,
                    seed = item.info.title,
                    badges = item.info.badges,
                    progress = progress?.takeIf { !it.finished && it.durationMs > 0 }?.fraction,
                    favorite = item.favorite,
                    onClick = { vm.play(item) },
                    onLongClick = { vm.itemActions(item) },
                    modifier = Modifier.remembered(vm.homeFocus, "$row:${item.id}"),
                    width = CardWidth,
                    portrait = true,
                    imageUrl = item.poster,
                )
            }
        }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, state: ShelfState, vm: AppViewModel, onOpen: (ShelfEntry) -> Unit) {
    Column {
        SectionHeader(shelf.title, Modifier.padding(horizontal = 48.dp), if (shelf.popular) "Больше всего сидов прямо сейчас" else "Свежие раздачи")
        when {
            state.loading -> LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp), userScrollEnabled = false) {
                items(7) { SkeletonCard(CardWidth, portrait = true) }
            }
            state.failed -> Row(Modifier.padding(RowPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Не удалось загрузить: сайт недоступен или нет сети.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                TvButton("Повторить", icon = Icons.Refresh, onClick = { vm.loadShelves(force = true) },
                    modifier = Modifier.remembered(vm.homeFocus, "${shelf.name}:retry"))
            }
            else -> LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.entries, key = { it.key }) { entry ->
                    MediaCard(
                        title = entry.title,
                        subtitle = listOfNotNull(entry.year, plural(entry.releases, "раздача", "раздачи", "раздач")).joinToString(" · "),
                        seed = entry.title,
                        badges = entry.badges,
                        onClick = { onOpen(entry) },
                        corner = { SeedsLabel(entry.seeds) },
                        modifier = Modifier.remembered(vm.homeFocus, "${shelf.name}:${entry.key}"),
                        width = CardWidth,
                        portrait = true,
                        imageUrl = rememberPoster(entry.hit),
                    )
                }
            }
        }
    }
}

/** Soft glows and a large play ring on the right of the hero; vector, so sharp from 720p to 4K. */
private fun DrawScope.heroArt() {
    val center = Offset(size.width * 0.86f, size.height * 0.5f)
    val radius = size.height * 0.62f
    drawCircle(Brush.radialGradient(listOf(Palette.accent.copy(alpha = 0.28f), Color.Transparent), center, radius * 1.6f), radius * 1.6f, center)
    drawCircle(Brush.radialGradient(listOf(Color(0x664C8DFF), Color.Transparent), Offset(size.width, 0f), size.height), size.height, Offset(size.width, 0f))
    drawCircle(Palette.accent.copy(alpha = 0.22f), radius, center, style = Stroke(width = radius * 0.06f))
    drawCircle(Palette.accent.copy(alpha = 0.10f), radius * 0.78f, center)
    val t = radius * 0.34f
    val play = Path().apply {
        moveTo(center.x - t * 0.6f, center.y - t)
        lineTo(center.x - t * 0.6f, center.y + t)
        lineTo(center.x + t, center.y)
        close()
    }
    drawPath(play, Palette.accent.copy(alpha = 0.55f))
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "ДОБРОЕ УТРО"
    in 12..17 -> "ДОБРЫЙ ДЕНЬ"
    in 18..22 -> "ДОБРЫЙ ВЕЧЕР"
    else -> "ДОБРОЙ НОЧИ"
}
