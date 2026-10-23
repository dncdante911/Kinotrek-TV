package org.trackertv.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

private val CardShape = RoundedCornerShape(16.dp)

/**
 * Cover art: the release poster when [imageUrl] is known, over a gradient with the title's
 * initial that stays as the placeholder while it loads (or when there is no poster).
 */
@Composable
fun PosterArt(
    seed: String,
    title: String,
    modifier: Modifier = Modifier,
    letterSize: Int = 84,
    imageUrl: String? = null,
    imageWidthPx: Int = 360,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val brush = remember(seed) { Palette.poster(seed) }
    Box(modifier.background(brush)) {
        Text(
            title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "▶",
            fontSize = letterSize.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp),
        )
        RemoteImage(imageUrl, imageWidthPx, Modifier.matchParentSize())
        content()
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Palette.accent, RoundedCornerShape(2.dp)))
    }
}

/** Card used in every row and grid, 16:9 or poster-shaped. Long press (hold OK) opens the item's actions. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 248.dp,
    badges: List<String> = emptyList(),
    progress: Float? = null,
    favorite: Boolean = false,
    corner: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** Cover URL; null keeps the gradient placeholder. */
    imageUrl: String? = null,
    /** Movie-poster proportions (2:3) instead of the 16:9 thumbnail. */
    portrait: Boolean = false,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.focus), shape = CardShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
    ) {
        Column {
            PosterArt(
                seed, title,
                Modifier.fillMaxWidth().aspectRatio(if (portrait) 2f / 3f else 16f / 9f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                imageUrl = imageUrl,
                imageWidthPx = if (portrait) 320 else 480,
            ) {
                Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.take(3).forEach { Badge(it, color = Color.Black.copy(alpha = 0.55f)) }
                }
                if (favorite) {
                    Icon(Icons.Favorite, "В избранном", tint = Palette.heart, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(22.dp))
                }
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    corner?.invoke(this)
                }
            }
        }
    }
}

/** Placeholder card pulsing while a row loads. */
@Composable
fun SkeletonCard(width: Dp = 248.dp, portrait: Boolean = false) {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Column(Modifier.width(width).alpha(alpha).background(Palette.surface, CardShape)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (portrait) 2f / 3f else 16f / 9f).background(Palette.surfaceHigh, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)))
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(16.dp).background(Palette.surfaceHigh, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(12.dp).background(Palette.surfaceHigh, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = Palette.accent) {
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rotation",
    )
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() / 10
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
        drawArc(color, rotation, 100f, false, Offset(stroke / 2, stroke / 2), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(24.dp)).padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(Palette.accentDim, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.text)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), content = actions)
    }
}

/** Wide list row (search results, files, settings) with a white focus frame. */
@Composable
fun ListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.focus), shape = CardShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
    }
}

@Composable
fun LoadingBlock(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Spinner(size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
    }
}
