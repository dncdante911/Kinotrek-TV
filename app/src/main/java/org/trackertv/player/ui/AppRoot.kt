package org.trackertv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
import org.trackertv.player.ui.components.LocalAmbient
import org.trackertv.player.ui.decor.DecorLayer
import org.trackertv.player.ui.decor.LocalOccasion
import org.trackertv.player.ui.components.MessageBar
import org.trackertv.player.ui.home.HomeScreen
import org.trackertv.player.ui.library.LibraryScreen
import org.trackertv.player.ui.release.ItemScreen
import org.trackertv.player.ui.release.ReleaseScreen
import org.trackertv.player.ui.search.SearchScreen
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.settings.SettingsScreen
import org.trackertv.player.ui.support.SupportScreen
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette
import org.trackertv.player.ui.torrent.TorrentScreen
import org.trackertv.player.ui.tv.TvScreen
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
    val ambient = remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(screen) { ambient.value = null }
    val light by animateColorAsState(ambient.value?.copy(alpha = 0.16f) ?: Color.Transparent, tween(600), label = "ambient")

    CompositionLocalProvider(LocalOccasion provides occasion, LocalAmbient provides ambient) {
    Box(Modifier.fillMaxSize().background(Palette.canvas).drawBehind { ambientLight(light) }) {
        Column(Modifier.fillMaxSize()) {
            TopBar(selected = screen.tab, support = screen == Screen.Support, onSupport = { vm.open(Screen.Support) }, onSelect = { tab ->
                vm.open(when (tab) {
                    Tab.HOME -> Screen.Home
                    Tab.SEARCH -> Screen.Search
                    Tab.TV -> Screen.Tv
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
                        Screen.Tv -> TvScreen(vm)
                        is Screen.Item -> ItemScreen(vm, search, screen.url)
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

/** The focused poster's color spilling over the top of the screen, like light from a TV in a dark room. */
private fun DrawScope.ambientLight(color: Color) {
    if (color.alpha == 0f) return
    val center = Offset(size.width * 0.7f, -size.height * 0.15f)
    drawRect(Brush.radialGradient(listOf(color, Color.Transparent), center, size.width * 0.75f))
}

@Composable
private fun TopBar(selected: Tab, support: Boolean, onSupport: () -> Unit, onSelect: (Tab) -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                // Settings is a gear only: five labels do not fit next to the clock.
                NavTab(tab.label.takeIf { tab != Tab.SETTINGS }, iconFor(tab), tab == selected && !support, tab.label) { onSelect(tab) }
            }
            Spacer(Modifier.width(6.dp))
            SupportHeart(support, onSupport)
        }
        Spacer(Modifier.width(22.dp))
        Clock()
    }
}

private fun iconFor(tab: Tab): ImageVector = when (tab) {
    Tab.HOME -> Icons.Home
    Tab.SEARCH -> Icons.Search
    Tab.TV -> Icons.Tv
    Tab.LIBRARY -> Icons.Library
    Tab.SETTINGS -> Icons.Settings
}

@Composable
private fun NavTab(label: String?, icon: ImageVector, selected: Boolean, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.surfaceHigh else Color.Transparent,
            contentColor = if (selected) Palette.text else Palette.muted,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = if (label != null) 15.dp else 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, description.takeIf { label == null }, Modifier.size(20.dp))
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Always in the top bar: a pink heart that beats twice every ten seconds, so it is noticed
 * without ever getting in the way. Opens the support page.
 */
@Composable
private fun SupportHeart(selected: Boolean, onClick: () -> Unit) {
    val beat by rememberInfiniteTransition(label = "heart").animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 10_000
            1f at 0
            1.22f at 180
            1f at 360
            1.22f at 540
            1f at 760
        }),
        label = "beat",
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.heart.copy(alpha = 0.25f) else Palette.heart.copy(alpha = 0.12f),
            contentColor = Palette.heart,
            focusedContainerColor = Palette.heart,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Favorite, "Поддержать автора", Modifier.size(20.dp).graphicsLayer { scaleX = beat; scaleY = beat })
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
