package org.trackertv.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.data.UpdateState
import org.trackertv.player.ui.components.ProgressLine
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

/**
 * Slim strip above the hero when a newer version exists. It never takes focus on its own:
 * the remote reaches it with one press up from the hero buttons.
 */
@Composable
fun UpdateBanner(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    val (title, detail, action) = when (state) {
        is UpdateState.Available -> Triple("Доступна Кинотрек ${state.release.version}", firstLine(state.release.notes), "Скачать")
        is UpdateState.Downloading -> Triple("Скачиваю Кинотрек ${state.release.version}", "${(state.progress * 100).toInt()}% · можно продолжать смотреть", null)
        is UpdateState.Ready -> Triple("Кинотрек ${state.release.version} готова к установке", state.error ?: firstLine(state.release.notes), "Обновить")
        is UpdateState.Installing -> Triple("Устанавливаю Кинотрек ${state.release.version}", "Приложение закроется на пару секунд — откройте его снова", null)
        else -> return
    }
    Row(
        modifier.fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .border(1.dp, Palette.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(Palette.accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.ArrowUp, null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.text, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Ready && state.error != null) Palette.fair else Palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state is UpdateState.Downloading) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(state.progress, Modifier.fillMaxWidth(0.6f))
            }
        }
        action?.let { label ->
            TvButton(label, onClick = if (state is UpdateState.Available) onDownload else onInstall, icon = Icons.ArrowUp, primary = true, modifier = buttonModifier)
        }
    }
}

private fun firstLine(notes: String): String =
    notes.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }.firstOrNull { it.isNotEmpty() } ?: "Новая версия на GitHub"
