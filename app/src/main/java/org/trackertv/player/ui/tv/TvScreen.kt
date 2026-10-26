package org.trackertv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.trackertv.player.net.Channel
import org.trackertv.player.net.ChannelList
import org.trackertv.player.net.Iptv
import org.trackertv.player.player.PlaybackEntry
import org.trackertv.player.player.PlaybackRequest
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.ui.components.LoadingBlock
import org.trackertv.player.ui.components.RemoteImage
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.TvChip
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

/** Lists, check results and focus survive leaving the tab: checking hundreds of streams takes a while. */
private object TvState {
    var list by mutableStateOf(ChannelList.UKRAINE)
    var onlyWorking by mutableStateOf(false)
    /** Bumped by "Повторить" to load the list again. */
    var attempt by mutableIntStateOf(0)
    val channels = mutableStateMapOf<ChannelList, List<Channel>>()
    val errors = mutableStateMapOf<ChannelList, String>()
    /** Stream url → whether it answered. */
    val working = mutableStateMapOf<String, Boolean>()
    val focus = FocusMemory()
}

private val GROUPS = mapOf(
    "general" to "Общие", "news" to "Новости", "movies" to "Кино", "entertainment" to "Развлечения", "series" to "Сериалы",
    "kids" to "Детям", "music" to "Музыка", "sports" to "Спорт", "documentary" to "Познавательные", "religious" to "Религия",
    "education" to "Образование", "culture" to "Культура", "business" to "Бизнес", "lifestyle" to "Стиль жизни", "travel" to "Путешествия",
    "cooking" to "Кулинария", "comedy" to "Юмор", "animation" to "Мультфильмы", "classic" to "Классика", "science" to "Наука",
)

/**
 * Free TV channels from open playlists. Every stream is checked in the background; the ones that
 * do not answer are dimmed and can be hidden. Channel up/down in the player switches channels.
 */
@Composable
fun TvScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val list = TvState.list
    val firstChip = remember { FocusRequester() }
    InitialFocus(TvState.focus, firstChip)

    LaunchedEffect(list, TvState.attempt) {
        val channels = TvState.channels[list] ?: try {
            TvState.errors.remove(list)
            Iptv.channels(context, list).also { TvState.channels[list] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvState.errors[list] = e.message ?: "Каталог каналов недоступен"
            return@LaunchedEffect
        }
        val gate = Semaphore(8)
        coroutineScope {
            channels.filter { TvState.working[it.url] == null }.forEach { channel ->
                launch { gate.withPermit { TvState.working[channel.url] = Iptv.check(channel) } }
            }
        }
    }

    val all = TvState.channels[list]
    val shown = all?.filter { !TvState.onlyWorking || TvState.working[it.url] == true }

    fun play(channel: Channel) {
        // Only channels that may work go into the player, so channel up/down never lands on a dead one.
        val playable = shown.orEmpty().filter { TvState.working[it.url] != false || it == channel }
        val index = playable.indexOf(channel).coerceAtLeast(0)
        // Intent extras have a size limit: take the channels around the chosen one.
        val from = (index - 50).coerceAtLeast(0)
        val window = playable.subList(from, (from + 100).coerceAtMost(playable.size))
        vm.send(UiEvent.Play(PlaybackRequest(
            itemUrl = null,
            entries = window.map { PlaybackEntry(it.name, it.url, "tv:${it.url}") },
            startIndex = index - from,
            live = true,
            userAgent = channel.userAgent,
            referer = channel.referer,
        )))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Телеканалы", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                Text(
                    "Бесплатные открытые трансляции из каталога iptv-org. Кинотрек проверяет, какие каналы отвечают прямо сейчас; " +
                        "в плеере каналы переключаются кнопками «следующий» и «предыдущий».",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(ChannelList.entries) { value ->
                        TvChip(value.title, selected = value == list, onClick = { TvState.list = value },
                            modifier = Modifier.remembered(TvState.focus, "list:${value.name}").let { if (value == ChannelList.entries.first()) it.focusRequester(firstChip) else it })
                    }
                    item {
                        TvChip("Только работающие", selected = TvState.onlyWorking, onClick = { TvState.onlyWorking = !TvState.onlyWorking },
                            modifier = Modifier.padding(start = 14.dp).remembered(TvState.focus, "working"))
                    }
                }
                if (all != null) {
                    val checked = all.count { TvState.working[it.url] != null }
                    val working = all.count { TvState.working[it.url] == true }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (checked < all.size) "Проверено $checked из ${all.size} · работают $working" else "Работают $working из ${all.size}",
                        style = MaterialTheme.typography.labelMedium, color = Palette.faint,
                    )
                }
            }
        }
        when {
            TvState.errors[list] != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(TvState.errors[list].orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    TvButton("Повторить", icon = Icons.Refresh, onClick = {
                        TvState.errors.remove(list)
                        TvState.attempt++
                    }, modifier = Modifier.remembered(TvState.focus, "retry"))
                }
            }
            shown == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LoadingBlock("Загружаю список каналов…") }
            else -> items(shown, key = { it.url }) { channel ->
                ChannelCard(channel, TvState.working[channel.url], onClick = { play(channel) },
                    modifier = Modifier.remembered(TvState.focus, "ch:${channel.url}"))
            }
        }
    }
}

@Composable
internal fun ChannelCard(channel: Channel, working: Boolean?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }.alpha(if (working == false && !focused) 0.45f else 1f),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.focus), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                Text(
                    channel.name.firstOrNull()?.uppercase() ?: "TV", fontSize = 44.sp, fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.12f),
                )
                RemoteImage(channel.logo, 320, Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 16.dp), contentScale = ContentScale.Fit, description = channel.name)
                channel.quality?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.text,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(
                        when (working) {
                            true -> Palette.good
                            false -> Palette.bad
                            null -> Palette.faint
                        }, CircleShape,
                    ))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(
                            when (working) {
                                true -> null
                                false -> "не отвечает"
                                null -> "проверяю…"
                            },
                            channel.group?.let { GROUPS[it.lowercase()] ?: it },
                            "не круглосуточно".takeIf { channel.partTime },
                        ).joinToString(" · ").ifEmpty { "в эфире" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Palette.onFocus.copy(alpha = 0.62f) else Palette.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
