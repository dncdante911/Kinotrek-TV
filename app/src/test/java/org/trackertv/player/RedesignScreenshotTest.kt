package org.trackertv.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.MediaCard
import org.trackertv.player.ui.components.SeedsLabel
import org.trackertv.player.ui.release.PanelAction
import org.trackertv.player.ui.release.ReleaseHero
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette
import org.trackertv.player.ui.theme.TrackerTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import org.trackertv.player.data.Progress
import org.trackertv.player.net.Channel
import org.trackertv.player.net.VideoFile
import org.trackertv.player.ui.release.PageBackdrop
import org.trackertv.player.ui.torrent.Episode
import org.trackertv.player.ui.torrent.EpisodeRow
import org.trackertv.player.ui.tv.ChannelCard
import androidx.test.core.app.ApplicationProvider
import org.trackertv.player.ui.AppRoot
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.search.SearchViewModel
import androidx.compose.ui.test.isDialog
import org.trackertv.player.ui.components.DialogAction
import org.trackertv.player.ui.components.DialogHost
import org.trackertv.player.ui.components.DialogSpec
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import java.io.File

/**
 * The home rows with a focused card and the first screen of a release, at 1920×1080, to check
 * the look. Run with KINO_SCREENSHOTS=1; images go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@OptIn(ExperimentalTestApi::class)
class RedesignScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val titles = listOf("Интерстеллар", "Дюна: Часть вторая", "Оппенгеймер", "Начало", "Бегущий по лезвию 2049", "Марсианин")

    /** [animated]: the screen never stops moving (the heart, loading rows), so the test drives the clock itself. */
    private fun shoot(name: String, animated: Boolean = false, dialog: Boolean = false, keys: List<Key> = emptyList(), content: @Composable () -> Unit) {
        assumeTrue("Set KINO_SCREENSHOTS=1 to render screenshots", System.getenv("KINO_SCREENSHOTS") != null)
        compose.mainClock.autoAdvance = !animated
        compose.setContent { TrackerTheme { Box(Modifier.fillMaxSize().background(Palette.canvas)) { content() } } }
        if (animated) repeat(40) { compose.mainClock.advanceTimeBy(50) } else {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        keys.forEach { key ->
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).performKeyInput { pressKey(key) }
            repeat(10) { compose.mainClock.advanceTimeBy(50) }
        }
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("redesign-home") {
        val focus = remember { FocusRequester() }
        // Focus later than the first frame, as the app does: a surface misses a focus it gets before it listens.
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        Column(Modifier.padding(top = 24.dp)) {
            listOf("Популярные фильмы", "Новинки кино").forEachIndexed { row, header ->
                Text(header, style = MaterialTheme.typography.titleLarge, color = Palette.text, modifier = Modifier.padding(start = 48.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(if (row == 0) titles else titles.reversed()) { index, title ->
                        MediaCard(
                            title, "2024 · 3 раздачи", title, onClick = {}, width = 170.dp, portrait = true,
                            badges = listOf("4K"), corner = { SeedsLabel(240) },
                            modifier = if (row == 0 && index == 1) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
            }
        }
    }

    @Test fun release() = shoot("redesign-release") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        ReleaseHero(
            kind = "ФИЛЬМ",
            title = "Интерстеллар",
            original = "Interstellar",
            facts = "2014 · фильм · США · 2:49",
            badges = listOf("1080p", "HDR", "HEVC"),
            genres = listOf("фантастика", "драма", "приключения"),
            synopsis = "Когда засуха, пыльные бури и вымирание растений приводят человечество к продовольственному кризису, " +
                "коллектив исследователей и учёных отправляется сквозь червоточину в путешествие, чтобы найти планету с подходящими для человечества условиями.",
            hint = "Вниз — описание, актёры и данные раздачи",
            actions = listOf(
                PanelAction("watch", "Смотреть", "14.2 GB · 312 сидов", Icons.Play) {},
                PanelAction("favorite", "В избранное", "появится на главной", Icons.FavoriteBorder) {},
                PanelAction("others", "Другие раздачи", "другое качество и озвучка", Icons.Search) {},
                PanelAction("back", "Назад", null, Icons.Back) {},
                PanelAction("home", "На главную", null, Icons.Home) {},
            ),
            memory = remember { FocusMemory() },
            firstAction = focus,
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** A release whose page has only small thumbnails: colored light and a sharp poster instead of a blurry backdrop. */
    @Test fun releasePoster() = shoot("redesign-release-poster") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        Box(Modifier.fillMaxSize()) {
            PageBackdrop(null, Palette.posterTint("Шерлок"))
            ReleaseHero(
                kind = "СЕРИАЛ",
                title = "Шерлок",
                original = "Sherlock",
                facts = "2010 · сериал · Великобритания",
                badges = listOf("1080p"),
                genres = listOf("детектив", "драма", "криминал"),
                synopsis = "События разворачиваются в наши дни. Он прошел Афганистан, остался инвалидом. По возвращении в родные края встречается с загадочным, но своеобразным гениальным человеком.",
                hint = "Остановились: S01E02 · осталось 34 минуты",
                actions = listOf(
                    PanelAction("resume", "Продолжить", "S01E02 · осталось 34 минуты", Icons.Play) {},
                    PanelAction("files", "Серии", "список серий и отметки просмотра", Icons.Episodes) {},
                    PanelAction("trailer", "Трейлер", "откроется в YouTube", Icons.Movie) {},
                    PanelAction("favorite", "В избранном", "OK — убрать", Icons.Favorite) {},
                    PanelAction("reset", "Сбросить прогресс", "смотреть как впервые", Icons.History) {},
                    PanelAction("back", "Назад", null, Icons.Back) {},
                ),
                memory = remember { FocusMemory() },
                firstAction = focus,
                showPoster = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test fun episodes() = shoot("redesign-episodes") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        val now = System.currentTimeMillis()
        Column(Modifier.padding(start = 400.dp, end = 48.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..6).forEach { n ->
                val file = VideoFile(n, "Sherlock.S01/Sherlock.S01E0$n.1080p.mkv", 2_300_000_000L)
                val progress = when (n) {
                    1 -> Progress("k", 5_400_000, 5_400_000, now)
                    2 -> Progress("k", 2_000_000, 5_400_000, now)
                    else -> null
                }
                EpisodeRow(Episode(n - 1, file), progress, current = n == 2, multiple = true, onClick = {},
                    modifier = if (n == 3) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }

    @Test fun tv() = shoot("redesign-tv") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        val names = listOf("1+1 Marafon", "Суспільне Новини", "Еспресо TV", "Rada TV", "Kultura", "FilmBox", "Music Box UA", "Пiксель", "Kyiv TV", "Ukraine 24")
        Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            names.chunked(5).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    chunk.forEachIndexed { index, name ->
                        val channel = Channel(name, "https://example.org/$name.m3u8", null, if (index % 2 == 0) "News" else "General",
                            if (index == 1) "1080p" else null, partTime = index == 3, userAgent = null, referer = null)
                        ChannelCard(channel, working = when (index) { 4 -> false; 2 -> null; else -> true }, onClick = {},
                            modifier = Modifier.width(150.dp).let { if (row == 0 && index == 1) it.focusRequester(focus) else it })
                    }
                }
            }
        }
    }

    /** The whole app on the home screen (no network here, so the shelves show their error). */
    @Test fun app() = shoot("redesign-app", animated = true) {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = remember { AppViewModel(application) }
        val search = remember { SearchViewModel(application) }
        AppRoot(vm, search, voiceAvailable = true)
    }

    /** Robolectric has a real network: this loads the real iptv-org list and checks the first channels. */
    @Test fun appTv() = shoot("redesign-app-tv", animated = true) {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = remember { AppViewModel(application).also { it.open(Screen.Tv) } }
        val search = remember { SearchViewModel(application) }
        AppRoot(vm, search, voiceAvailable = true)
    }

    /** The update dialog with the real notes of this release: they must fit on a TV and be readable. */
    @Test fun updateDialog() = updateDialog("redesign-update-dialog", emptyList())

    /** Up from the buttons into the text, then two presses down scroll it. */
    @Test fun updateDialogScrolled() = updateDialog("redesign-update-dialog-scrolled", listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionDown))

    private fun updateDialog(name: String, keys: List<Key>) {
        val notes = File("../release/notes/1.8.0.md").readText() + "\n\n**Full Changelog**: https://github.com/dncdante911/Kinotrek-TV/compare/1.7.0...1.8.0"
        shoot(name, animated = true, dialog = true, keys = keys) {
            DialogHost(DialogSpec.Actions(
                title = "Обновить до 1.8.0?",
                message = "Приложение закроется на пару секунд. Библиотека, история и настройки сохранятся.",
                notes = notes,
                actions = listOf(DialogAction("Установить", Icons.ArrowUp, primary = true) {}),
            )) {}
        }
    }
}
