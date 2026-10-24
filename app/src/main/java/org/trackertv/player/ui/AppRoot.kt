package org.trackertv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.trackertv.player.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.trackertv.player.data.Seasons
import org.trackertv.player.VoiceState
import org.trackertv.player.ui.components.DialogHost
import org.trackertv.player.ui.decor.DecorLayer
import org.trackertv.player.ui.decor.LocalOccasion
import org.trackertv.player.ui.components.MessageBar
import org.trackertv.player.ui.home.HomeScreen
import org.trackertv.player.ui.library.LibraryScreen
import org.trackertv.player.ui.release.ReleaseScreen
import org.trackertv.player.ui.search.SearchScreen
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.settings.SettingsScreen
import org.trackertv.player.ui.support.SupportScreen
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette
import org.trackertv.player.ui.torrent.TorrentScreen
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(vm: AppViewModel, search: SearchViewModel, voiceAvailable: Boolean) {
    val states = rememberSaveableStateHolder()
    val screen = vm.current
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60 * 60_000L)
            value = LocalDate.now()
        }
    }
    val occasion = vm.decorPreview ?: Seasons.occasion(today, settings.decor)

    CompositionLocalProvider(LocalOccasion provides occasion) {
    Box(Modifier.fillMaxSize().background(Palette.canvas)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(selected = screen.tab, onSelect = { tab ->
                vm.open(when (tab) {
                    Tab.HOME -> Screen.Home
                    Tab.SEARCH -> Screen.Search
                    Tab.LIBRARY -> Screen.Library
                    Tab.SETTINGS -> Screen.Settings
                })
            })
            Box(Modifier.weight(1f)) {
                states.SaveableStateProvider(screen.toString()) {
                    when (screen) {
                        Screen.Home -> HomeScreen(vm, search, voiceAvailable)
                        Screen.Search -> SearchScreen(vm, search, voiceAvailable)
                        Screen.Library -> LibraryScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.Torrent -> TorrentScreen(vm, screen.request)
                        is Screen.Release -> ReleaseScreen(vm, search, screen.hit)
                        Screen.Support -> SupportScreen()
                    }
                }
            }
        }
        occasion?.let { DecorLayer(it, celebrate = screen == Screen.Home) }
        if (vm.voice != VoiceState.Off) VoiceOverlay(vm.voice) { vm.send(UiEvent.CancelVoice) }
        vm.message?.let { MessageBar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)) }
        vm.dialog?.let { DialogHost(it, vm::dismissDialog) }
    }
    }
}

@Composable
private fun TopBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Play, null, tint = Palette.onFocus, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = Palette.text)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { tab ->
                NavTab(tab.label, iconFor(tab), tab == selected) { onSelect(tab) }
            }
        }
        Spacer(Modifier.width(28.dp))
        Clock()
    }
}

private fun iconFor(tab: Tab): ImageVector = when (tab) {
    Tab.HOME -> Icons.Home
    Tab.SEARCH -> Icons.Search
    Tab.LIBRARY -> Icons.Library
    Tab.SETTINGS -> Icons.Settings
}

@Composable
private fun NavTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) Palette.text else Palette.muted,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Clock() {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(now, style = MaterialTheme.typography.titleMedium, color = Palette.muted)
}
