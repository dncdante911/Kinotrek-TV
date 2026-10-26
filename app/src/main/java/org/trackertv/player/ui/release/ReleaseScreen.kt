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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
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
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.remainingLabel
import org.trackertv.player.ui.components.Badge
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.Images
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.RemoteImage
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

private val CardShape = RoundedCornerShape(8.dp)

private sealed interface Page {
    data object Loading : Page
    data class Ready(val details: ReleaseDetails) : Page
    data object Failed : Page
}

/**
 * The release as the app shows it: facts and story from the tracker page, laid out in the app's
 * own style. The first action is focused, so OK twice still starts the video.
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
    val saved = library.find(hit)
    val favorite = saved?.favorite == true
    val progress = saved?.let(library::resumeOf)?.takeIf { it.durationMs > 0 && !it.finished }
    val info = hit.info

    val memory = remember(hit.page) { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)

    val backdrop = rememberBackdrop(details?.screenshots.orEmpty())
    val facts = listOfNotNull(info.year, if (info.series) "сериал" else "фильм", details?.country, details?.duration).joinToString(" · ")
    val torrent = TorrentRequest(hit.title, hit.magnet ?: saved?.url, hit, poster, hit.page)
    val actions = listOfNotNull(
        if (saved != null && progress != null) PanelAction("resume", "Продолжить",
            listOfNotNull(saved.resumeLabel?.takeIf { it != saved.title }, remainingLabel(progress.remainingMs)).joinToString(" · "), Icons.Play) {
            vm.play(saved, resume = true)
        } else null,
        PanelAction(
            "watch",
            when {
                progress != null && info.series -> "Серии"
                progress != null -> "Смотреть сначала"
                else -> "Смотреть"
            },
            listOfNotNull(hit.size.takeIf { it.isNotEmpty() }, hit.seeds.takeIf { it > 0 }?.let { plural(it, "сид", "сида", "сидов") }).joinToString(" · ").ifEmpty { null },
            if (progress != null && info.series) Icons.Episodes else Icons.Play,
        ) { vm.open(Screen.Torrent(torrent)) },
        PanelAction("trailer", "Трейлер", "откроется в YouTube", Icons.Movie) { vm.trailer(info.title, info.year, details?.trailer) },
        PanelAction("favorite", if (favorite) "В избранном" else "В избранное", if (favorite) "OK — убрать" else "появится на главной",
            if (favorite) Icons.Favorite else Icons.FavoriteBorder) { vm.favoriteHit(hit) },
        PanelAction("others", "Другие раздачи", "другое качество и озвучка", Icons.Search) {
            search.query = info.searchQuery
            search.submit(info.searchQuery)
            vm.open(Screen.Search)
        },
        PanelAction("back", "Назад", null, Icons.Back) { vm.back() },
    )

    Box(Modifier.fillMaxSize()) {
        PageBackdrop(backdrop, Images.tint(poster) ?: Palette.posterTint(info.title))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "hero") {
                ReleaseHero(
                    kind = if (info.series) "СЕРИАЛ" else "ФИЛЬМ",
                    title = info.title,
                    original = info.original,
                    facts = facts,
                    badges = info.badges + listOfNotNull("HEVC".takeIf { info.hevc }),
                    genres = details?.genres.orEmpty(),
                    synopsis = details?.synopsis,
                    hint = when {
                        page == Page.Loading -> "Загружаю описание…"
                        page == Page.Failed -> "Описание не загрузилось — смотреть можно и без него"
                        progress != null -> "Вы уже начали смотреть — OK, чтобы продолжить"
                        else -> "Вниз — описание, актёры и данные раздачи"
                    },
                    actions = actions,
                    memory = memory,
                    firstAction = first,
                    poster = poster,
                    showPoster = backdrop == null,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
            (page as? Page.Ready)?.let { sections(it.details, memory) }
        }
    }
}

internal fun LazyListScope.sections(details: ReleaseDetails, memory: FocusMemory) {
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
        Column(Modifier.padding(start = 48.dp)) {
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
        modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().remembered(memory, title),
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
