package org.trackertv.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.trackertv.player.data.ReleaseNotes
import org.trackertv.player.ui.theme.Palette

data class DialogAction(val label: String, val icon: ImageVector? = null, val primary: Boolean = false, val onClick: () -> Unit)

/** What the root shows on top of the current screen; one dialog at a time. */
sealed interface DialogSpec {
    data class Actions(
        val title: String,
        val message: String? = null,
        val actions: List<DialogAction>,
        /** Long Markdown text (release notes), shown in a box the remote can scroll. */
        val notes: String? = null,
    ) : DialogSpec
    data class Input(
        val title: String,
        val initial: String,
        val placeholder: String,
        val confirmLabel: String,
        val message: String? = null,
        val onConfirm: (String) -> Unit,
    ) : DialogSpec
    data class Info(val title: String, val rows: List<Triple<String, String, Boolean?>>, val actions: List<DialogAction>) : DialogSpec
}

@Composable
fun DialogHost(spec: DialogSpec, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(if ((spec as? DialogSpec.Actions)?.notes != null) 760.dp else 600.dp)
                .background(Palette.surface, RoundedCornerShape(28.dp))
                .padding(32.dp),
        ) {
            when (spec) {
                is DialogSpec.Actions -> ActionsContent(spec, onDismiss)
                is DialogSpec.Input -> InputContent(spec, onDismiss)
                is DialogSpec.Info -> InfoContent(spec, onDismiss)
            }
        }
    }
}

@Composable
private fun Title(text: String, message: String?) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Palette.text, maxLines = 3)
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ActionsContent(spec: DialogSpec.Actions, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    if (spec.notes != null) {
        // Release notes: the text gets the room, the buttons go in one row under it so all fits on a TV.
        Text(spec.title, style = MaterialTheme.typography.headlineSmall, color = Palette.text, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        NotesBox(spec.notes)
        spec.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            spec.actions.forEachIndexed { index, action ->
                TvButton(action.label, icon = action.icon, primary = action.primary, onClick = { onDismiss(); action.onClick() },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            TvButton("Отмена", onClick = onDismiss, modifier = if (spec.actions.isEmpty()) Modifier.focusRequester(first) else Modifier)
        }
    } else {
        Title(spec.title, spec.message)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            spec.actions.forEachIndexed { index, action ->
                TvButton(
                    text = action.label,
                    icon = action.icon,
                    primary = action.primary,
                    onClick = { onDismiss(); action.onClick() },
                    modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                )
            }
            TvButton("Отмена", onClick = onDismiss, modifier = Modifier.fillMaxWidth().let { if (spec.actions.isEmpty()) it.focusRequester(first) else it })
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * Long text in a box of limited height. A remote cannot scroll plain text, so the box takes focus
 * (Up from the buttons) and then ↑/↓ scroll it; at the end ↓ moves on to the buttons. A fade at the
 * bottom shows there is more.
 */
@Composable
fun NotesBox(markdown: String, modifier: Modifier = Modifier, maxHeight: Dp = 250.dp) {
    val lines = remember(markdown) { ReleaseNotes.lines(markdown) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 110.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val scrollable = scroll.maxValue > 0
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(Palette.background.copy(alpha = 0.55f), shape)
            .border(2.dp, if (focused) Palette.focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> scroll.canScrollForward.also { if (it) scope.launch { scroll.animateScrollBy(step) } }
                    Key.DirectionUp -> scroll.canScrollBackward.also { if (it) scope.launch { scroll.animateScrollBy(-step) } }
                    else -> false
                }
            }
            .focusable(enabled = scrollable),
    ) {
        Column(Modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                when (line.kind) {
                    ReleaseNotes.Kind.GAP -> Spacer(Modifier.height(4.dp))
                    ReleaseNotes.Kind.HEADING -> Text(line.text, style = MaterialTheme.typography.titleMedium, color = Palette.text)
                    ReleaseNotes.Kind.TEXT -> Text(line.text, style = MaterialTheme.typography.bodyMedium, color = Palette.text)
                    ReleaseNotes.Kind.BULLET, ReleaseNotes.Kind.SUB_BULLET -> {
                        val sub = line.kind == ReleaseNotes.Kind.SUB_BULLET
                        Row(Modifier.padding(start = if (sub) 26.dp else 0.dp)) {
                            Text(if (sub) "◦" else "•", style = MaterialTheme.typography.bodyMedium, color = Palette.accent, modifier = Modifier.width(18.dp))
                            Text(line.text, style = MaterialTheme.typography.bodyMedium, color = if (sub) Palette.muted else Palette.text)
                        }
                    }
                }
            }
        }
        if (scroll.canScrollForward) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.surface)), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)))
        }
        if (scrollable) {
            Text(
                if (focused) "↑ ↓ — листать" else "↑ — читать дальше",
                style = MaterialTheme.typography.labelMedium, color = if (focused) Palette.onFocus else Palette.muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .background(if (focused) Palette.focus else Palette.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InputContent(spec: DialogSpec.Input, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(spec.initial) }
    val field = remember { FocusRequester() }
    Title(spec.title, spec.message)
    TvTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = spec.placeholder,
        imeAction = ImeAction.Done,
        focusRequester = field,
        modifier = Modifier.fillMaxWidth(),
        startEditing = true,
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(spec.confirmLabel, primary = true, onClick = { onDismiss(); spec.onConfirm(value) })
        TvButton("Отмена", onClick = onDismiss)
    }
}

@Composable
private fun InfoContent(spec: DialogSpec.Info, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Title(spec.title, null)
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        spec.rows.forEach { (label, value, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.weight(1f))
                if (ok != null) {
                    Icon(
                        if (ok) org.trackertv.player.ui.theme.Icons.Check else org.trackertv.player.ui.theme.Icons.Close,
                        null,
                        tint = if (ok) Palette.good else Palette.faint,
                        modifier = Modifier.padding(end = 8.dp).height(18.dp),
                    )
                }
                Text(value, style = MaterialTheme.typography.bodyMedium, color = if (ok == false) Palette.faint else Palette.text)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Готово", primary = true, onClick = onDismiss, modifier = Modifier.focusRequester(first))
        spec.actions.forEach { action -> TvButton(action.label, icon = action.icon, onClick = { onDismiss(); action.onClick() }) }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Short confirmation at the bottom of the screen instead of a system toast. */
@Composable
fun MessageBar(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Palette.onFocus,
        modifier = modifier
            .background(Palette.focus, RoundedCornerShape(50))
            .padding(horizontal = 26.dp, vertical = 14.dp),
    )
}
