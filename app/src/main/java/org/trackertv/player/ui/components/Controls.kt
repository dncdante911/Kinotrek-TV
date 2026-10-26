package org.trackertv.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.trackertv.player.ui.theme.Palette

private val Pill = RoundedCornerShape(50)

/** Rounded TV button: white with dark text when focused, so the focus is visible from the sofa. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = ClickableSurfaceDefaults.shape(Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.accent else Palette.surfaceHigh,
            contentColor = if (primary) Palette.onFocus else Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
            pressedContainerColor = Palette.focus,
            pressedContentColor = Palette.onFocus,
            disabledContainerColor = Palette.surface,
            disabledContentColor = Palette.faint,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 52.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surfaceHigh,
            contentColor = tint ?: Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = tint ?: Palette.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** Filter chip: a selected chip is tinted with the accent, a focused one turns white. */
@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.accentDim else Palette.surface,
            contentColor = if (selected) Palette.accent else Palette.muted,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Palette.accent else Palette.surfaceHigher), shape = Pill),
            focusedBorder = Border(BorderStroke(2.dp, Palette.focus), shape = Pill),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected) Icon(org.trackertv.player.ui.theme.Icons.Check, null, Modifier.size(16.dp))
            else icon?.let { Icon(it, null, Modifier.size(16.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Palette.surfaceHigher, contentColor: Color = Palette.text) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        maxLines = 1,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun SeedsLabel(seeds: Int, modifier: Modifier = Modifier) {
    val color = when {
        seeds >= 20 -> Palette.good
        seeds >= 5 -> Palette.fair
        else -> Palette.bad
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(org.trackertv.player.ui.theme.Icons.ArrowUp, contentDescription = "Сиды", tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(3.dp))
        Text(seeds.toString(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * Text input that behaves on a remote: the field is a button until OK is pressed, so moving
 * through the screen never pops the keyboard by accident.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Search,
    focusRequester: FocusRequester = remember { FocusRequester() },
    startEditing: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(startEditing) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = { editing = true },
        modifier = modifier.focusRequester(focusRequester),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceHigh,
            focusedContentColor = Palette.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (editing) 2.dp else 1.dp, if (editing) Palette.accent else Palette.surfaceHigher), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, Palette.focus), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(24.dp)) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    keyboard?.hide()
                    runCatching { focusRequester.requestFocus() }
                    onSubmit()
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
        }
        trailing?.invoke()
    }
}
