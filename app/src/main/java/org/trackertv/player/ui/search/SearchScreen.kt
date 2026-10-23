package org.trackertv.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.data.LibrarySnapshot
import org.trackertv.player.net.Category
import org.trackertv.player.net.Hit
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.Badge
import org.trackertv.player.ui.components.EmptyState
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.ListRow
import org.trackertv.player.ui.components.LoadingBlock
import org.trackertv.player.ui.components.PosterArt
import org.trackertv.player.ui.components.SeedsLabel
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.TvChip
import org.trackertv.player.ui.components.TvIconButton
import org.trackertv.player.ui.components.TvTextField
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.release.rememberPoster
import org.trackertv.player.ui.theme.Palette

@Composable
fun SearchScreen(vm: AppViewModel, search: SearchViewModel, voiceAvailable: Boolean) {
    val library by vm.library.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val memory = search.focus
    val field = remember { FocusRequester() }
    val firstMode = remember { FocusRequester() }
    InitialFocus(memory, if (search.mode == SearchMode.SEARCH) field else firstMode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "modes") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchMode.entries.forEachIndexed { index, mode ->
                    TvChip(
                        mode.label, selected = search.mode == mode, onClick = { search.selectMode(mode) },
                        icon = when (mode) { SearchMode.SEARCH -> Icons.Search; SearchMode.LATEST -> Icons.Star; SearchMode.POPULAR -> Icons.Trending },
                        modifier = Modifier.remembered(memory, "mode:${mode.name}").then(if (index == 0) Modifier.focusRequester(firstMode) else Modifier),
                    )
                }
                Spacer(Modifier.weight(1f))
                TvButton("Вход на RuTracker", icon = Icons.Globe, onClick = { vm.send(UiEvent.OpenRutracker) },
                    modifier = Modifier.remembered(memory, "rutracker"))
            }
        }

        if (search.mode == SearchMode.SEARCH) {
            item(key = "field") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(
                        value = search.query,
                        onValueChange = { search.query = it },
                        placeholder = "Название фильма или сериала",
                        icon = Icons.Search,
                        focusRequester = field,
                        onSubmit = { search.submit() },
                        modifier = Modifier.weight(1f).remembered(memory, "field"),
                    )
                    if (voiceAvailable) {
                        TvIconButton(Icons.Mic, "Голосовой поиск", onClick = { vm.send(UiEvent.VoiceSearch) },
                            modifier = Modifier.remembered(memory, "voice"), size = 56.dp)
                    }
                    TvButton("Найти", primary = true, onClick = { search.submit() }, modifier = Modifier.remembered(memory, "submit"))
                }
            }
            if (settings.history.isNotEmpty() && search.shownQuery == null) {
                item(key = "history") {
                    Column {
                        Text("Недавние запросы", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(settings.history, key = { it }) { query ->
                                TvChip(query, selected = false, icon = Icons.History, onClick = { search.submit(query) },
                                    modifier = Modifier.remembered(memory, "history:$query"))
                            }
                        }
                    }
                }
            }
        }

        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(Category.entries, key = { "c" + it.name }) { category ->
                    TvChip(category.label, selected = search.category == category, onClick = { search.selectCategory(category) },
                        modifier = Modifier.remembered(memory, "category:${category.name}"))
                }
                item { Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 36.dp).background(Palette.surfaceHigher)) }
                items(QualityFilter.entries, key = { "q" + it.name }) { quality ->
                    TvChip(quality.label, selected = search.quality == quality, onClick = { search.selectQuality(quality) },
                        modifier = Modifier.remembered(memory, "quality:${quality.name}"))
                }
            }
        }

        // Sites are an implementation detail: only say something when the user can act or results may be partial.
        val needsLogin = search.errors.any { it.needsLogin }
        val partial = search.errors.any { !it.needsLogin }
        if ((needsLogin || partial) && !search.loading) {
            item(key = "notice") {
                Row(
                    Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (needsLogin) "Войдите на RuTracker, чтобы искать и по нему — раздач станет больше."
                        else "Часть источников сейчас не ответила, результаты могут быть неполными.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.weight(1f),
                    )
                    if (needsLogin) TvButton("Войти", onClick = { vm.send(UiEvent.OpenRutracker) }, modifier = Modifier.remembered(memory, "login"))
                }
            }
        }

        when {
            search.loading -> item(key = "loading") {
                LoadingBlock(if (search.mode == SearchMode.SEARCH) "Ищем «${search.shownQuery}»…" else "Загружаем раздачи…")
            }
            search.mode == SearchMode.SEARCH && search.shownQuery == null -> item(key = "intro") {
                EmptyState(Icons.Search, "Найдите фильм или сериал", "Нажмите OK на поле ввода, наберите название и выберите раздачу с большим числом сидов.")
            }
            search.visible.isEmpty() -> item(key = "empty") {
                EmptyState(Icons.Info, "Ничего не нашлось",
                    if (search.hits.isEmpty()) "Попробуйте другое название, проверьте сеть или выберите другую категорию."
                    else "Под выбранное качество раздач нет — снимите фильтр качества.") {
                    TvButton("Повторить", icon = Icons.Refresh, onClick = { search.reload() }, modifier = Modifier.remembered(memory, "retry"))
                }
            }
            else -> {
                item(key = "count") {
                    Text(
                        plural(search.visible.size, "раздача", "раздачи", "раздач") + " · только живые, по числу сидов",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
                items(search.visible, key = { it.id }) { hit ->
                    ResultRow(hit, library, vm, Modifier.remembered(memory, hit.id), heartModifier = Modifier.remembered(memory, "fav:${hit.id}"))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(hit: Hit, library: LibrarySnapshot, vm: AppViewModel, modifier: Modifier = Modifier, heartModifier: Modifier = Modifier) {
    val favorite = library.find(hit)?.favorite == true
    val info = hit.info
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ListRow(onClick = { vm.open(Screen.Release(hit)) }, modifier = modifier.weight(1f)) {
            PosterArt(info.title, info.title, Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)), letterSize = 44,
                imageUrl = rememberPoster(hit), imageWidthPx = 160)
            Column(Modifier.weight(1f)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val secondary = listOfNotNull(info.original, info.year).joinToString(" · ")
                if (secondary.isNotEmpty()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.details.isNotEmpty()) Text(info.details, style = MaterialTheme.typography.bodySmall, color = Palette.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    info.badges.forEach { Badge(it, color = Palette.accentDim, contentColor = Palette.accent) }
                    if (info.hevc) Badge("HEVC")
                    if (hit.size.isNotEmpty()) Badge(hit.size)
                    hit.dateLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.faint) }
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                SeedsLabel(hit.seeds)
                if (hit.peers > 0) Text("пиры ${hit.peers}", style = MaterialTheme.typography.bodySmall, color = Palette.faint)
            }
        }
        TvIconButton(
            if (favorite) Icons.Favorite else Icons.FavoriteBorder,
            if (favorite) "Убрать из избранного" else "В избранное",
            onClick = { vm.favoriteHit(hit) },
            tint = if (favorite) Palette.heart else null,
            modifier = heartModifier,
        )
    }
}
