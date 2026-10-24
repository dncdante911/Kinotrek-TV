package org.trackertv.player.ui.decor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Outlines in a 1×1 box; callers scale them to the size they need. */
internal object Shapes {
    val maple: Path by lazy {
        polygon(
            0.50f, 0.00f, 0.57f, 0.18f, 0.68f, 0.12f, 0.65f, 0.34f, 0.86f, 0.24f, 0.82f, 0.40f,
            0.98f, 0.46f, 0.80f, 0.58f, 0.86f, 0.70f, 0.60f, 0.66f, 0.53f, 0.80f, 0.52f, 1.00f,
            0.48f, 1.00f, 0.47f, 0.80f, 0.40f, 0.66f, 0.14f, 0.70f, 0.20f, 0.58f, 0.02f, 0.46f,
            0.18f, 0.40f, 0.14f, 0.24f, 0.35f, 0.34f, 0.32f, 0.12f, 0.43f, 0.18f,
        )
    }

    val heart: Path by lazy {
        Path().apply {
            moveTo(0.5f, 0.92f)
            cubicTo(0.2f, 0.7f, 0f, 0.5f, 0f, 0.3f)
            cubicTo(0f, 0.12f, 0.15f, 0f, 0.3f, 0f)
            cubicTo(0.4f, 0f, 0.47f, 0.06f, 0.5f, 0.14f)
            cubicTo(0.53f, 0.06f, 0.6f, 0f, 0.7f, 0f)
            cubicTo(0.85f, 0f, 1f, 0.12f, 1f, 0.3f)
            cubicTo(1f, 0.5f, 0.8f, 0.7f, 0.5f, 0.92f)
            close()
        }
    }

    /** Cherry petal with the little notch at the tip. */
    val petal: Path by lazy {
        Path().apply {
            moveTo(0.5f, 1f)
            cubicTo(0.1f, 0.75f, 0.12f, 0.2f, 0.38f, 0.04f)
            quadraticTo(0.5f, 0.16f, 0.62f, 0.04f)
            cubicTo(0.88f, 0.2f, 0.9f, 0.75f, 0.5f, 1f)
            close()
        }
    }

    val egg: Path by lazy {
        Path().apply {
            moveTo(0.5f, 0f)
            cubicTo(0.8f, 0f, 1f, 0.45f, 1f, 0.65f)
            cubicTo(1f, 0.88f, 0.78f, 1f, 0.5f, 1f)
            cubicTo(0.22f, 1f, 0f, 0.88f, 0f, 0.65f)
            cubicTo(0f, 0.45f, 0.2f, 0f, 0.5f, 0f)
            close()
        }
    }

    val bat: Path by lazy {
        Path().apply {
            moveTo(0.5f, 0.40f)
            lineTo(0.545f, 0.30f)
            lineTo(0.56f, 0.40f)
            quadraticTo(0.72f, 0.28f, 1.0f, 0.34f)
            quadraticTo(0.90f, 0.42f, 0.86f, 0.56f)
            quadraticTo(0.80f, 0.48f, 0.73f, 0.52f)
            quadraticTo(0.67f, 0.46f, 0.60f, 0.58f)
            quadraticTo(0.55f, 0.62f, 0.5f, 0.72f)
            quadraticTo(0.45f, 0.62f, 0.40f, 0.58f)
            quadraticTo(0.33f, 0.46f, 0.27f, 0.52f)
            quadraticTo(0.20f, 0.48f, 0.14f, 0.56f)
            quadraticTo(0.10f, 0.42f, 0.0f, 0.34f)
            quadraticTo(0.28f, 0.28f, 0.44f, 0.40f)
            lineTo(0.455f, 0.30f)
            close()
        }
    }

    private fun polygon(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1])
        for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
        close()
    }
}

/** Draws [path] (1×1 box) at [topLeft] with side [side] px. Brushes are in the box's coordinates. */
internal fun DrawScope.drawUnit(path: Path, topLeft: Offset, side: Float, brush: Brush, alpha: Float = 1f, style: DrawStyle = Fill) {
    translate(topLeft.x, topLeft.y) {
        scale(side, side, Offset.Zero) { drawPath(path, brush, alpha, style) }
    }
}

internal fun unitVertical(top: Color, bottom: Color) = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = 1f)

/**
 * Particles are drawn from small pre-rendered images: one textured quad per particle is far
 * cheaper on TV GPUs than filling vector paths every frame.
 */
internal object Sprites {
    private const val PX = 96

    private fun render(draw: DrawScope.(Float) -> Unit): ImageBitmap {
        val image = ImageBitmap(PX, PX)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(PX.toFloat(), PX.toFloat())) { draw(PX.toFloat()) }
        return image
    }

    fun softFlake() = render { s ->
        drawCircle(Brush.radialGradient(0f to Color.White, 0.35f to Color.White.copy(alpha = 0.9f), 1f to Color.Transparent, center = center, radius = s / 2), s / 2)
    }

    fun crystal() = render { s ->
        val c = Offset(s / 2, s / 2)
        val r = s * 0.44f
        drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.35f), 1f to Color.Transparent, center = c, radius = r), r)
        repeat(6) { k ->
            val a = k * PI.toFloat() / 3
            val end = c + Offset(cos(a), sin(a)) * r
            drawLine(Color.White, c, end, strokeWidth = s * 0.06f, cap = StrokeCap.Round)
            val mid = c + Offset(cos(a), sin(a)) * (r * 0.56f)
            for (side in listOf(-1f, 1f)) {
                val b = a + side * PI.toFloat() / 4
                drawLine(Color.White, mid, mid + Offset(cos(b), sin(b)) * (r * 0.28f), strokeWidth = s * 0.045f, cap = StrokeCap.Round)
            }
        }
        drawCircle(Color.White, s * 0.06f, c)
    }

    fun petal(light: Color, deep: Color) = render { s ->
        drawUnit(Shapes.petal, Offset(s * 0.12f, s * 0.08f), s * 0.76f, unitVertical(light, deep))
        drawLine(deep.copy(alpha = 0.45f), Offset(s * 0.5f, s * 0.84f), Offset(s * 0.5f, s * 0.36f), strokeWidth = s * 0.02f, cap = StrokeCap.Round)
    }

    fun maple(color: Color, dark: Color) = render { s ->
        val side = s * 0.9f
        val o = Offset(s * 0.05f, s * 0.05f)
        drawUnit(Shapes.maple, o, side, Brush.linearGradient(listOf(color, dark), start = Offset(0.2f, 0f), end = Offset(0.8f, 1f)))
        val base = o + Offset(0.5f, 0.84f) * side
        listOf(0.5f to 0.06f, 0.94f to 0.46f, 0.06f to 0.46f, 0.84f to 0.68f, 0.16f to 0.68f).forEach { (x, y) ->
            drawLine(dark.copy(alpha = 0.55f), base, o + Offset(x, y) * side, strokeWidth = s * 0.018f, cap = StrokeCap.Round)
        }
    }

    fun heart(color: Color, dark: Color) = render { s ->
        val o = Offset(s * 0.1f, s * 0.12f)
        val side = s * 0.8f
        drawUnit(Shapes.heart, o, side, unitVertical(color, dark))
        drawOval(Color.White.copy(alpha = 0.35f), o + Offset(0.16f, 0.12f) * side, Size(0.18f, 0.12f) * side)
    }

    fun glow(color: Color) = render { s ->
        drawCircle(Brush.radialGradient(0f to Color.White, 0.12f to color, 0.4f to color.copy(alpha = 0.35f), 1f to Color.Transparent, center = center, radius = s / 2), s / 2)
    }

    fun bat() = render { s ->
        drawCircle(Brush.radialGradient(0f to Color(0x66FF8A2A), 1f to Color.Transparent, center = center, radius = s / 2), s / 2)
        drawUnit(Shapes.bat, Offset.Zero, s, unitVertical(Color(0xFF3B2852), Color(0xFF1C1228)))
        drawUnit(Shapes.bat, Offset.Zero, s, SolidColor(Color(0xFFFF9A3C)), alpha = 0.55f, style = Stroke(width = 0.012f))
        drawCircle(Color(0xFFFFA43A), s * 0.012f, Offset(s * 0.485f, s * 0.43f))
        drawCircle(Color(0xFFFFA43A), s * 0.012f, Offset(s * 0.515f, s * 0.43f))
    }

    fun confetti(color: Color) = render { s ->
        drawRoundRect(color, Offset(s * 0.2f, s * 0.36f), Size(s * 0.6f, s * 0.28f), CornerRadius(s * 0.05f))
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(s * 0.2f, s * 0.36f), Size(s * 0.6f, s * 0.08f), CornerRadius(s * 0.05f))
    }
}
