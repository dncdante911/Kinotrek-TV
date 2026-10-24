package org.trackertv.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.trackertv.player.player.PlayerControls
import org.trackertv.player.player.PlayerMenu
import java.io.File

/**
 * The player controls and menu at 1920×1080 over a stand-in frame, to check the layout.
 * Run with KINO_SCREENSHOTS=1; images go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerControlsScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun controls() = PlayerControls(context).apply {
        title.text = "Дюна: Часть вторая · S01E03"
        subtitle.text = "Файл 3 из 8"
        status.text = "↓ 3,4 МБ/с · ↑ 120 КБ/с · пиров 38 (сидов 21)"
        audio.setText("Русский 5.1")
        subtitles.setText("Выкл")
        setPlaylist(hasPrevious = true, hasNext = true, multiple = true)
        update(current = 47 * 60_000L + 12_000, buffered = 55 * 60_000L, total = 2 * 3_600_000L + 46 * 60_000L, playing = false, speed = 1f)
        show(playPause)
    }

    private fun shoot(name: String, content: View) {
        assumeTrue("Set KINO_SCREENSHOTS=1 to render screenshots", System.getenv("KINO_SCREENSHOTS") != null)
        val root = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3B4A5E.toInt(), 0xFF1A2230.toInt(), 0xFF6B4B2E.toInt()))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        val width = 1920
        val height = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        content.alpha = 1f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "player-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun controlsPaused() = shoot("controls", controls())

    @Test fun audioMenu() {
        val stack = FrameLayout(context)
        stack.addView(controls(), FrameLayout.LayoutParams(-1, -1))
        val menu = PlayerMenu(context).apply {
            open("Звуковая дорожка", listOf(
                PlayerMenu.Item("Русский · Дубляж · 5.1 · AC3", "640 кбит/с", checked = true) {},
                PlayerMenu.Item("Русский · MVO LostFilm · Стерео · AAC", "192 кбит/с") {},
                PlayerMenu.Item("Украинский · Дубляж · 5.1 · E-AC3", "448 кбит/с") {},
                PlayerMenu.Item("Английский · 7.1 · TrueHD", "Не поддерживается устройством", enabled = false) {},
            ))
        }
        stack.addView(menu, FrameLayout.LayoutParams(-1, -1))
        shoot("audio-menu", stack)
    }
}
