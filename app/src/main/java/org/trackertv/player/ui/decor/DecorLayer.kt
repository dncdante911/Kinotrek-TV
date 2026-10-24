package org.trackertv.player.ui.decor

import android.app.ActivityManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.trackertv.player.data.Occasion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The decoration in effect; cards read it to put snow or a leaf on themselves. */
val LocalOccasion = staticCompositionLocalOf<Occasion?> { null }

/**
 * Falling snow, leaves, petals… over the whole app, under dialogs. It is one layer drawn from a
 * few small images: the screens below are not redrawn, and nothing here takes focus or input.
 * Stops while the app is in the background and never runs in the player (another activity).
 */
@Composable
fun DecorLayer(occasion: Occasion, celebrate: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dp = LocalDensity.current.density
    val light = remember { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    val flocks = remember(occasion) { flocksFor(occasion, light) }
    val fireworks = remember(occasion) {
        if (occasion == Occasion.NEW_YEAR || occasion == Occasion.INDEPENDENCE) Fireworks(occasion, if (light) 22 else 40) else null
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var clock by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val onHome by rememberUpdatedState(celebrate)
    LaunchedEffect(occasion, size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val area = Size(size.width.toFloat(), size.height.toFloat())
        flocks.forEach { it.scatter(area, dp) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameNanos { it }
            while (true) {
                // Weak boxes get ~30 fps: the motion is slow, so it still looks smooth.
                if (light) delay(28)
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val time = clock + dt
                flocks.forEach { it.step(dt, time, area, dp) }
                fireworks?.step(time, area, dp, enabled = onHome)
                clock = time
            }
        }
    }

    Spacer(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer()
            .drawWithCache {
                val scenery = Scenery(occasion, this.size, dp)
                onDrawBehind {
                    val t = clock
                    scenery.back(this, t)
                    flocks.forEach { it.draw(this) }
                    fireworks?.draw(this, t, dp)
                    scenery.front(this, t)
                }
            },
    )
}

private enum class Motion { FALL, RISE, FLY }

/**
 * One kind of particle. Each particle has a depth: far ones are smaller, slower and fainter,
 * which gives the snow its feeling of space.
 */
private class Flock(
    val sprites: List<ImageBitmap>,
    val count: Int,
    val motion: Motion,
    val sizeDp: ClosedFloatingPointRange<Float>,
    val speedDp: ClosedFloatingPointRange<Float>,
    val alpha: ClosedFloatingPointRange<Float>,
    val swayDp: Float,
    val spin: Float = 0f,
    val flip: Boolean = false,
    val twinkle: Boolean = false,
    val flap: Boolean = false,
) {
    private val random = Random(sprites.size * 31 + count)
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val base = FloatArray(count)
    private val depth = FloatArray(count)
    private val phase = FloatArray(count)
    private val rate = FloatArray(count)
    private val angle = FloatArray(count)
    private val direction = FloatArray(count)
    private val sprite = IntArray(count)
    private val sx = FloatArray(count)
    private val sy = FloatArray(count)
    private val shade = FloatArray(count)
    private val sizePx = FloatArray(count)

    fun scatter(area: Size, dp: Float) {
        for (i in 0 until count) {
            spawn(i, area, dp)
            when (motion) {
                Motion.FLY -> x[i] = random.nextFloat() * area.width
                else -> y[i] = random.nextFloat() * area.height
            }
        }
    }

    private fun spawn(i: Int, area: Size, dp: Float) {
        val z = random.nextFloat()
        depth[i] = z
        sizePx[i] = lerp(sizeDp, z) * dp
        phase[i] = random.nextFloat() * 2 * PI.toFloat()
        rate[i] = 0.4f + random.nextFloat() * 0.9f
        angle[i] = random.nextFloat() * 360f
        sprite[i] = random.nextInt(sprites.size)
        direction[i] = if (random.nextBoolean()) 1f else -1f
        when (motion) {
            Motion.FALL -> { base[i] = random.nextFloat() * area.width; y[i] = -sizePx[i] }
            Motion.RISE -> { base[i] = random.nextFloat() * area.width; y[i] = area.height + sizePx[i] }
            Motion.FLY -> {
                base[i] = area.height * (0.08f + random.nextFloat() * 0.5f)
                x[i] = if (direction[i] > 0) -sizePx[i] else area.width + sizePx[i]
            }
        }
    }

    fun step(dt: Float, t: Float, area: Size, dp: Float) {
        for (i in 0 until count) {
            val z = depth[i]
            val speed = lerp(speedDp, z) * dp
            val sway = sin(t * rate[i] + phase[i]) * swayDp * dp * (0.5f + z * 0.5f)
            when (motion) {
                Motion.FALL -> {
                    y[i] += speed * dt
                    x[i] = base[i] + sway
                    if (y[i] > area.height + sizePx[i]) spawn(i, area, dp)
                }
                Motion.RISE -> {
                    y[i] -= speed * dt
                    x[i] = base[i] + sway
                    if (y[i] < -sizePx[i]) spawn(i, area, dp)
                }
                Motion.FLY -> {
                    x[i] += speed * dt * direction[i]
                    y[i] = base[i] + sway
                    if (x[i] < -2 * sizePx[i] || x[i] > area.width + 2 * sizePx[i]) spawn(i, area, dp)
                }
            }
            angle[i] += spin * dt * (if (direction[i] > 0) 1f else -1f) * (0.6f + z * 0.4f)
            sx[i] = if (flip) cos(t * rate[i] * 2.2f + phase[i]) else 1f
            sy[i] = if (flap) 0.45f + 0.55f * abs(sin(t * 7f * rate[i] + phase[i])) else 1f
            if (motion == Motion.FLY) sx[i] = direction[i]
            val a = lerp(alpha, z)
            shade[i] = if (twinkle) a * (0.35f + 0.65f * abs(sin(t * rate[i] * 1.7f + phase[i]))) else a
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        for (i in 0 until count) {
            val image = sprites[sprite[i]]
            val scale = sizePx[i] / image.width
            val px = x[i]
            val py = y[i]
            val rotation = if (motion == Motion.FLY) 0f else angle[i]
            withTransform({
                translate(px, py)
                rotate(rotation, Offset.Zero)
                scale(scale * sx[i], scale * sy[i], Offset.Zero)
            }) {
                drawImage(image, Offset(-image.width / 2f, -image.height / 2f), alpha = shade[i])
            }
        }
    }

    private fun lerp(range: ClosedFloatingPointRange<Float>, z: Float) = range.start + (range.endInclusive - range.start) * z
}

private fun flocksFor(occasion: Occasion, light: Boolean): List<Flock> {
    fun n(count: Int) = if (light) count / 2 else count
    return when (occasion) {
        Occasion.WINTER, Occasion.NEW_YEAR -> listOf(
            Flock(listOf(Sprites.softFlake()), n(34), Motion.FALL, 3f..8f, 16f..42f, 0.35f..0.9f, swayDp = 14f),
            Flock(listOf(Sprites.crystal()), n(8), Motion.FALL, 11f..19f, 20f..38f, 0.5f..0.9f, swayDp = 20f, spin = 24f),
        )
        Occasion.SPRING -> listOf(
            Flock(
                listOf(
                    Sprites.petal(Color(0xFFFFE6EF), Color(0xFFFF9EC2)),
                    Sprites.petal(Color(0xFFFFF5F8), Color(0xFFFFC1D6)),
                    Sprites.petal(Color(0xFFFFD6E5), Color(0xFFE87AA3)),
                ),
                n(18), Motion.FALL, 13f..21f, 22f..44f, 0.55f..0.95f, swayDp = 28f, spin = 70f, flip = true,
            ),
        )
        Occasion.SUMMER -> listOf(
            Flock(listOf(Sprites.glow(Color(0xFFFFE08A)), Sprites.glow(Color(0xFFFFF3C4)), Sprites.glow(Color(0xFFD8FF8A))),
                n(18), Motion.RISE, 10f..20f, 5f..14f, 0.45f..0.95f, swayDp = 32f, twinkle = true),
        )
        Occasion.AUTUMN -> listOf(
            Flock(
                listOf(
                    Sprites.maple(Color(0xFFF08A3A), Color(0xFFB4421E)),
                    Sprites.maple(Color(0xFFE0533A), Color(0xFF8E2219)),
                    Sprites.maple(Color(0xFFF7C24A), Color(0xFFC77F16)),
                    Sprites.maple(Color(0xFFC9662A), Color(0xFF7A3414)),
                ),
                n(14), Motion.FALL, 22f..36f, 28f..58f, 0.6f..0.95f, swayDp = 42f, spin = 80f, flip = true,
            ),
        )
        Occasion.VALENTINE -> listOf(
            Flock(listOf(Sprites.heart(Color(0xFFFF6B88), Color(0xFFC72C4F)), Sprites.heart(Color(0xFFFFA3B5), Color(0xFFE0506E))),
                n(14), Motion.FALL, 12f..22f, 16f..34f, 0.45f..0.9f, swayDp = 24f, spin = 18f),
        )
        Occasion.HALLOWEEN -> listOf(
            Flock(listOf(Sprites.glow(Color(0xFFFF9A3C)), Sprites.glow(Color(0xFFFFC46B))), n(12), Motion.RISE, 5f..9f, 10f..24f, 0.4f..0.9f, swayDp = 18f, twinkle = true),
            Flock(listOf(Sprites.bat()), if (light) 2 else 4, Motion.FLY, 28f..42f, 50f..90f, 0.75f..1f, swayDp = 20f, flap = true),
        )
        Occasion.EASTER -> listOf(
            Flock(
                listOf(
                    Sprites.petal(Color(0xFFFFF6DC), Color(0xFFF2D49B)),
                    Sprites.petal(Color(0xFFF1E6FF), Color(0xFFB79AE6)),
                    Sprites.petal(Color(0xFFFFE6EF), Color(0xFFFF9EC2)),
                ),
                n(16), Motion.FALL, 13f..20f, 20f..40f, 0.55f..0.95f, swayDp = 26f, spin = 60f, flip = true,
            ),
        )
        Occasion.INDEPENDENCE -> listOf(
            Flock(listOf(Sprites.confetti(Color(0xFF3D8BFF)), Sprites.confetti(Color(0xFFFFD500))),
                n(24), Motion.FALL, 13f..20f, 40f..76f, 0.6f..0.95f, swayDp = 16f, spin = 140f, flip = true),
        )
    }
}

/** A quiet salute on the home screen: one burst at a time, every few seconds. */
private class Fireworks(occasion: Occasion, private val sparks: Int) {
    private val palette = if (occasion == Occasion.INDEPENDENCE) {
        listOf(Color(0xFF3D8BFF), Color(0xFFFFD500), Color(0xFF8CC0FF), Color(0xFFFFE780))
    } else {
        listOf(Color(0xFFFFD27A), Color(0xFF5EE0D2), Color(0xFFFF7AA8), Color(0xFFFFFFFF), Color(0xFFB28CFF))
    }
    private val random = Random(occasion.ordinal)
    private val angle = FloatArray(sparks)
    private val speed = FloatArray(sparks)
    private var origin = Offset.Zero
    private var color = palette.first()
    private var accent = palette.first()
    private var startedAt = -1f
    private var nextAt = 3f

    fun step(t: Float, area: Size, dp: Float, enabled: Boolean) {
        if (startedAt >= 0 && t - startedAt > LIFE) startedAt = -1f
        if (!enabled || startedAt >= 0 || t < nextAt) return
        // Right half of the screen, where the home hero has its picture and no text.
        origin = Offset(area.width * (0.56f + random.nextFloat() * 0.32f), area.height * (0.14f + random.nextFloat() * 0.24f))
        color = palette[random.nextInt(palette.size)]
        accent = palette[random.nextInt(palette.size)]
        for (i in 0 until sparks) {
            angle[i] = i * 2 * PI.toFloat() / sparks + random.nextFloat() * 0.12f
            speed[i] = (110 + random.nextFloat() * 70) * dp
        }
        startedAt = t
        nextAt = t + LIFE + 4f + random.nextFloat() * 5f
    }

    fun draw(scope: DrawScope, t: Float, dp: Float) = with(scope) {
        if (startedAt < 0) return@with
        val age = t - startedAt
        val fade = (1f - age / LIFE).coerceIn(0f, 1f)
        if (age < 0.25f) {
            val flash = 1f - age / 0.25f
            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.5f * flash), 1f to Color.Transparent, center = origin, radius = 60 * dp), 60 * dp, origin)
        }
        for (i in 0 until sparks) {
            val head = position(i, age, dp)
            val tail = position(i, (age - 0.12f).coerceAtLeast(0f), dp)
            val c = if (i % 3 == 0) accent else color
            drawLine(c.copy(alpha = 0.5f * fade), tail, head, strokeWidth = 1.6f * dp, cap = StrokeCap.Round)
            drawCircle(c.copy(alpha = fade), 1.8f * dp, head)
        }
    }

    private fun position(i: Int, age: Float, dp: Float): Offset {
        // Sparks slow down in the air and drift down a little.
        val travel = speed[i] * (1 - exp(-age * 2.2f)) / 2.2f
        return origin + Offset(cos(angle[i]) * travel, sin(angle[i]) * travel + 26 * dp * age * age)
    }

    private companion object {
        const val LIFE = 1.9f
    }
}
