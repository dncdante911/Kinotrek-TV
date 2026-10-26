package org.trackertv.player.ui.release

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.trackertv.player.ui.components.Badge
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.Images
import org.trackertv.player.ui.components.PosterArt
import org.trackertv.player.ui.components.RemoteImage
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.decor.LocalOccasion
import org.trackertv.player.ui.decor.cardDecor
import org.trackertv.player.ui.theme.Palette

/** Narrower pictures turn into mush when stretched over a TV, so they never become the backdrop. */
private const val MIN_BACKDROP_WIDTH = 1000

/** A row of the panel on the right of a page: icon, title and a hint under it. */
class PanelAction(val key: String, val title: String, val subtitle: String?, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The first of [candidates] (frames from the page) that is big enough to fill the screen sharply,
 * or null; most trackers only show small thumbnails, then the page uses a poster layout instead.
 */
@Composable
fun rememberBackdrop(candidates: List<String>): String? {
    val context = LocalContext.current
    return produceState<String?>(null, candidates) {
        for (url in candidates.take(3)) {
            Images.load(context, url, 1280) ?: continue
            if ((Images.sourceWidth(url) ?: 0) >= MIN_BACKDROP_WIDTH) {
                value = url
                return@produceState
            }
        }
    }.value
}

/**
 * Behind a page: the frame dimmed toward the text, or, without one, a soft light in the poster's
 * color, so the page still matches its film.
 */
@Composable
fun PageBackdrop(backdrop: String?, tint: Color) {
    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) {
            RemoteImage(backdrop, 1280, Modifier.fillMaxSize().alpha(0.6f))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to Palette.background.copy(alpha = 0.92f), 0.55f to Palette.background.copy(alpha = 0.35f), 1f to Palette.background.copy(alpha = 0.75f),
            )))
        } else {
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.25f), size.width * 0.75f))
            })
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.background)))
    }
}

/**
 * The first screen of a page, after the «Зеркало» TV app: title and facts at the bottom left,
 * actions in a panel on the right; the first action has focus, so OK twice still plays. With no
 * frame for the backdrop ([poster] given) a sharp poster stands beside the text.
 */
@Composable
fun ReleaseHero(
    kind: String,
    title: String,
    original: String?,
    facts: String,
    badges: List<String>,
    genres: List<String>,
    synopsis: String?,
    hint: String,
    actions: List<PanelAction>,
    memory: FocusMemory,
    firstAction: FocusRequester,
    modifier: Modifier = Modifier,
    poster: String? = null,
    showPoster: Boolean = false,
) {
    Row(modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxHeight().padding(start = 48.dp, end = 32.dp, bottom = 36.dp), verticalAlignment = Alignment.Bottom) {
            if (showPoster) {
                PosterArt(
                    title, title,
                    Modifier.width(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).cardDecor(LocalOccasion.current, title),
                    letterSize = 110, imageUrl = poster, imageWidthPx = 400,
                )
                Spacer(Modifier.width(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                Spacer(Modifier.height(6.dp))
                Text(title, style = if (showPoster) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                    color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    original?.let { OutlinedTag(it.uppercase()) }
                    if (facts.isNotEmpty()) OutlinedTag(facts)
                    badges.forEach { Badge(it, color = Palette.accentDim, contentColor = Palette.accent, modifier = Modifier.align(Alignment.CenterVertically)) }
                }
                if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(genres.take(5).joinToString(", ").replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
                synopsis?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.text, maxLines = if (showPoster) 3 else 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                Hint(hint)
            }
        }
        ActionPanel(actions, memory, firstAction, Modifier.fillMaxHeight())
    }
}

/** Grey caption on a dark plate, the way «Зеркало» tells what OK does here. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The panel on the right of a page; scrolls when the actions do not fit. */
@Composable
fun ActionPanel(actions: List<PanelAction>, memory: FocusMemory, firstAction: FocusRequester?, modifier: Modifier = Modifier, width: Dp = 380.dp) {
    Box(
        modifier.width(width)
            .background(Brush.horizontalGradient(listOf(Palette.surface.copy(alpha = 0.55f), Palette.surface.copy(alpha = 0.92f))))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEachIndexed { index, action ->
                PanelRow(action, Modifier.remembered(memory, action.key).let { if (index == 0 && firstAction != null) it.focusRequester(firstAction) else it })
            }
        }
    }
}

@Composable
fun OutlinedTag(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.border(1.dp, Palette.text.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun PanelRow(action: PanelAction, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = action.onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(action.icon, null, Modifier.size(26.dp))
            Spacer(Modifier.width(18.dp))
            Column {
                Text(action.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                action.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (focused) Palette.onFocus.copy(alpha = 0.6f) else Palette.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
