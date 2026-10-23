package org.trackertv.player.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.ui.theme.Palette

data class DialogAction(val label: String, val icon: ImageVector? = null, val primary: Boolean = false, val onClick: () -> Unit)

/** What the root shows on top of the current screen; one dialog at a time. */
sealed interface DialogSpec {
    data class Actions(val title: String, val message: String? = null, val actions: List<DialogAction>) : DialogSpec
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
                .width(600.dp)
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
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
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
