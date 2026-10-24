package org.trackertv.player.ui.release

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import org.trackertv.player.app
import org.trackertv.player.net.Hit
import org.trackertv.player.net.ReleaseDetails
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.TorrentRequest
import org.trackertv.player.ui.components.Badge
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.LoadingBlock
import org.trackertv.player.ui.components.PosterArt
import org.trackertv.player.ui.components.RemoteImage
import org.trackertv.player.ui.components.SeedsLabel
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.decor.LocalOccasion
import org.trackertv.player.ui.decor.cardDecor
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

private val CardShape = RoundedCornerShape(18.dp)

private sealed interface Page {
    data object Loading : Page
    data class Ready(val details: ReleaseDetails) : Page
    data object Failed : Page
}

/**
 * The release as the app shows it: poster, facts and story from the tracker page, laid out in
 * the app's own style. "Смотреть" is focused first, so OK twice still starts the video.
 */
@Composable
fun ReleaseScreen(vm: AppViewModel, search: SearchViewModel, hit: Hit) {
    val context = LocalContext.current
    val sources = context.app.sources
    val page by produceState<Page>(Page.Loading, hit.page) {
        value = try {
            sources.details(hit)?.let { Page.Ready(it) } ?: Page.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Page.Failed
        }
    }
    val details = (page as? Page.Ready)?.details
    val poster = details?.poster ?: sources.knownPoster(hit)
    val library by vm.library.snapshot.collectAsStateWithLifecycle()
    val favorite = library.find(hit)?.favorite == true
    val info = hit.info

    val memory = remember(hit.page) { FocusMemory() }
    val watch = remember { FocusRequester() }
    InitialFocus(memory, watch)

    Box(Modifier.fillMaxSize()) {
        // The poster as a faint backdrop fading into the canvas, the way streaming apps do it.
        if (poster != null) {
            RemoteImage(poster, 640, Modifier.fillMaxWidth(0.55f).fillMaxSize().align(Alignment.TopEnd).alpha(0.22f))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.35f to Palette.background, 1f to Color.Transparent)))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.background)))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "header") {
                Row {
                    Box(Modifier.width(210.dp).aspectRatio(2f / 3f).clip(CardShape).border(1.dp, Palette.surfaceHigher, CardShape)
                        .cardDecor(LocalOccasion.current, info.title)) {
                        PosterArt(info.title, info.title, Modifier.fillMaxSize(), letterSize = 120, imageUrl = poster, imageWidthPx = 420)
                    }
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (info.series) "СЕРИАЛ" else "ФИЛЬМ", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                        Text(info.title, style = MaterialTheme.typography.headlineLarge, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(info.original, info.year, details?.country, details?.duration).joinToString(" · ")
                        if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            info.badges.forEach { Badge(it, color = Palette.accentDim, contentColor = Palette.accent) }
                            if (info.hevc) Badge("HEVC")
                            if (hit.size.isNotEmpty()) Badge(hit.size)
                            SeedsLabel(hit.seeds)
                        }
                        details?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                genres.take(6).forEach { Badge(it.replaceFirstChar(Char::uppercase)) }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TvButton(
                                "Смотреть", primary = true, icon = Icons.Play,
                                onClick = { vm.open(Screen.Torrent(TorrentRequest(hit.title, hit.magnet, hit, poster, hit.page))) },
                                modifier = Modifier.focusRequester(watch).remembered(memory, "watch"),
                            )
                            TvButton(
                                if (favorite) "В избранном" else "В избранное",
                                icon = if (favorite) Icons.Favorite else Icons.FavoriteBorder,
                                onClick = { vm.favoriteHit(hit) },
                                modifier = Modifier.remembered(memory, "favorite"),
                            )
                            TvButton("Другие раздачи", icon = Icons.Search, modifier = Modifier.remembered(memory, "others"), onClick = {
                                search.query = info.searchQuery
                                search.submit(info.searchQuery)
                                vm.open(Screen.Search)
                            })
                        }
                        details?.synopsis?.let {
                            Spacer(Modifier.height(18.dp))
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            when (val state = page) {
                Page.Loading -> item(key = "loading") { LoadingBlock("Загружаю описание…") }
                Page.Failed -> item(key = "failed") {
                    Text(
                        "Описание раздачи не загрузилось — смотреть можно и без него.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                }
                is Page.Ready -> sections(state.details, memory)
            }
        }
    }
}

private fun LazyListScope.sections(details: ReleaseDetails, memory: FocusMemory) {
    details.synopsis?.takeIf { it.length > 260 }?.let { story ->
        item(key = "story") { InfoCard("О фильме", memory) { Text(story, style = MaterialTheme.typography.bodyLarge, color = Palette.text) } }
    }
    val people = listOfNotNull(details.director?.let { "Режиссёр" to it }, details.cast?.let { "В ролях" to it })
    if (people.isNotEmpty()) item(key = "people") { InfoCard("Создатели", memory) { Facts(people) } }
    val tech = listOfNotNull(
        details.quality?.let { "Качество" to it },
        details.video?.let { "Видео" to it },
        details.translation?.let { "Перевод" to it },
    ) + details.audio.mapIndexed { index, value -> (if (details.audio.size > 1) "Звук ${index + 1}" else "Звук") to value } +
        listOfNotNull(details.subtitles?.let { "Субтитры" to it })
    if (tech.isNotEmpty()) item(key = "tech") { InfoCard("О раздаче", memory) { Facts(tech) } }
    if (details.screenshots.isNotEmpty()) item(key = "shots") {
        Column {
            Text("Кадры", style = MaterialTheme.typography.titleLarge, color = Palette.text)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(details.screenshots) { index, url ->
                    Surface(
                        onClick = {},
                        modifier = Modifier.width(300.dp).aspectRatio(16f / 9f).remembered(memory, "shot$index"),
                        shape = ClickableSurfaceDefaults.shape(CardShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surface, focusedContainerColor = Palette.surfaceHigh),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.focus), shape = CardShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    ) {
                        RemoteImage(url, 600, Modifier.fillMaxSize(), description = "Кадр ${index + 1}")
                    }
                }
            }
        }
    }
}

/** A focusable card, so the remote can scroll down to it; it does nothing on OK. */
@Composable
private fun InfoCard(title: String, memory: FocusMemory, content: @Composable () -> Unit) {
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().remembered(memory, title),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface.copy(alpha = 0.85f), contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceHigh, focusedContentColor = Palette.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), shape = CardShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.accent)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.width(150.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Keeps a small poster for lists: the same lazy lookup the cards use. */
@Composable
fun rememberPoster(hit: Hit): String? {
    val sources = LocalContext.current.app.sources
    return produceState(sources.knownPoster(hit), hit.page) {
        if (value != null) return@produceState
        value = try {
            sources.poster(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }.value
}
