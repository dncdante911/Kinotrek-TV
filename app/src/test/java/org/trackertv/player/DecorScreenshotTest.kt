package org.trackertv.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.trackertv.player.data.Occasion
import org.trackertv.player.ui.components.MediaCard
import org.trackertv.player.ui.components.SeedsLabel
import org.trackertv.player.ui.decor.DecorLayer
import org.trackertv.player.ui.decor.LocalOccasion
import org.trackertv.player.ui.theme.Palette
import org.trackertv.player.ui.theme.TrackerTheme
import java.io.File

/**
 * Every season and holiday on a home-like screen at 1920×1080, to check how the decoration
 * looks. Run with KINO_SCREENSHOTS=1; images go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DecorScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val titles = listOf("Интерстеллар", "Дюна: Часть вторая", "Оппенгеймер", "Начало", "Бегущий по лезвию 2049", "Марсианин")

    private fun shoot(occasion: Occasion) {
        assumeTrue("Set KINO_SCREENSHOTS=1 to render screenshots", System.getenv("KINO_SCREENSHOTS") != null)
        // The decoration never stops moving, so the test drives the clock itself.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TrackerTheme {
                CompositionLocalProvider(LocalOccasion provides occasion) {
                    Box(Modifier.fillMaxSize().background(Palette.canvas)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp)) {
                                Text("Кинотрек", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                            }
                            Box(
                                Modifier.padding(horizontal = 48.dp).fillMaxWidth().height(120.dp)
                                    .background(Palette.hero, RoundedCornerShape(28.dp)).padding(28.dp),
                            ) {
                                Text("Что посмотрим сегодня?", style = MaterialTheme.typography.displaySmall, color = Palette.text)
                            }
                            Text("Популярные фильмы", style = MaterialTheme.typography.headlineSmall, color = Palette.text,
                                modifier = Modifier.padding(start = 48.dp, top = 20.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                items(titles) { title ->
                                    MediaCard(title, "2024 · 3 раздачи", title, onClick = {}, width = 170.dp, portrait = true,
                                        badges = listOf("4K"), corner = { SeedsLabel(240) })
                                }
                            }
                        }
                        DecorLayer(occasion, celebrate = true)
                    }
                }
            }
        }
        // Let the particles spread over the screen and a firework go off.
        repeat(54) { compose.mainClock.advanceTimeBy(50) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "decor-${occasion.ordinal}-${occasion.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun winter() = shoot(Occasion.WINTER)
    @Test fun spring() = shoot(Occasion.SPRING)
    @Test fun summer() = shoot(Occasion.SUMMER)
    @Test fun autumn() = shoot(Occasion.AUTUMN)
    @Test fun newYear() = shoot(Occasion.NEW_YEAR)
    @Test fun easter() = shoot(Occasion.EASTER)
    @Test fun valentine() = shoot(Occasion.VALENTINE)
    @Test fun independence() = shoot(Occasion.INDEPENDENCE)
    @Test fun halloween() = shoot(Occasion.HALLOWEEN)
}
