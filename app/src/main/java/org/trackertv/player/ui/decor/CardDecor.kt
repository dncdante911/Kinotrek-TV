package org.trackertv.player.ui.decor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import org.trackertv.player.data.Occasion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seasonal touch on a card's cover: snow lying on its top edge in winter, a leaf, a blossom, an
 * egg… Built once per card size and drawn as a static picture, so it costs nothing per frame.
 * Which cards get a corner item depends on the title, so the same card always looks the same.
 */
fun Modifier.cardDecor(occasion: Occasion?, seed: String): Modifier {
    if (occasion == null) return this
    val pick = seed.hashCode() and Int.MAX_VALUE
    return drawWithCache {
        val dp = density
        val random = Random(pick)
        val cap = if (occasion == Occasion.WINTER || occasion == Occasion.NEW_YEAR) snowCap(size, dp, random) else null
        val corner = Offset(size.width - 18 * dp, 18 * dp)
        val some = pick % 2 == 0
        val sparkles = random.nextInt()
        onDrawWithContent {
            drawContent()
            cap?.let { drawSnowCap(it, size, dp, sparkles) }
            when (occasion) {
                Occasion.NEW_YEAR -> if (pick / 2 % 2 == 0) ornament(size, dp, pick)
                Occasion.AUTUMN -> if (some) leaf(corner, 24 * dp, pick)
                Occasion.SPRING -> if (some) {
                    flower(corner + Offset(-2 * dp, 0f), 1f)
                    flower(corner + Offset(-16 * dp, 10 * dp), 0.65f)
                }
                Occasion.VALENTINE -> if (some) {
                    rotate(-14f, corner) { drawUnit(Shapes.heart, corner - Offset(9 * dp, 9 * dp), 18 * dp, unitVertical(Color(0xFFFF6B88), Color(0xFFC72C4F))) }
                }
                Occasion.HALLOWEEN -> if (some) cobweb(size, dp)
                Occasion.EASTER -> if (some) pysanka(corner + Offset(-2 * dp, 4 * dp), dp, pick)
                Occasion.INDEPENDENCE -> if (some) ribbon(size, dp)
                Occasion.SUMMER -> drawRect(Brush.radialGradient(0f to Color(0x2EFFE6A6), 1f to Color.Transparent, center = Offset(size.width, 0f), radius = size.width * 0.9f))
                Occasion.WINTER -> Unit
            }
        }
    }
}

private fun snowCap(size: Size, dp: Float, random: Random) = Path().apply {
    val w = size.width
    moveTo(0f, 0f)
    lineTo(w, 0f)
    lineTo(w, 6 * dp)
    var x = w
    while (x > 0f) {
        val next = (x - (16 + random.nextFloat() * 22) * dp).coerceAtLeast(0f)
        quadraticTo((x + next) / 2, (11 + random.nextFloat() * 6) * dp, next, (5 + random.nextFloat() * 2) * dp)
        x = next
    }
    close()
}

private fun DrawScope.drawSnowCap(cap: Path, size: Size, dp: Float, seed: Int) {
    drawPath(cap, Color(0x66000000), style = Stroke(2 * dp))
    drawPath(cap, Brush.verticalGradient(listOf(Color.White, Color(0xFFD6E4F2)), endY = 15 * dp))
    val random = Random(seed)
    repeat(4) {
        drawCircle(Color(0xFFBFD6EE), (0.6f + random.nextFloat() * 0.7f) * dp, Offset(random.nextFloat() * size.width, (2 + random.nextFloat() * 3) * dp))
    }
}

/** A glass ball hanging from the snow on a thread. */
private fun DrawScope.ornament(size: Size, dp: Float, pick: Int) {
    val colors = listOf(Color(0xFFE8344A) to Color(0xFF8E1424), Color(0xFFFFC94D) to Color(0xFFB07A12), Color(0xFF5EE0D2) to Color(0xFF1F8C82))
    val (color, dark) = colors[pick % colors.size]
    val x = size.width * 0.76f
    val center = Offset(x, 30 * dp)
    val r = 7.5f * dp
    drawLine(Color(0xCCFFFFFF), Offset(x, 6 * dp), Offset(x, center.y - r), strokeWidth = 0.9f * dp)
    drawRect(Color(0xFFD8C27A), Offset(x - 2.5f * dp, center.y - r - 2.5f * dp), Size(5 * dp, 3 * dp))
    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.9f), 0.25f to color, 1f to dark, center = center + Offset(-2.5f * dp, -2.5f * dp), radius = r * 1.6f), r, center)
}

private fun DrawScope.leaf(at: Offset, side: Float, pick: Int) {
    val colors = listOf(Color(0xFFF08A3A) to Color(0xFFB4421E), Color(0xFFE0533A) to Color(0xFF8E2219), Color(0xFFF7C24A) to Color(0xFFC77F16))
    val (color, dark) = colors[pick % colors.size]
    rotate((pick % 70 - 35).toFloat(), at) {
        drawUnit(Shapes.maple, at - Offset(side / 2, side / 2), side, Brush.linearGradient(listOf(color, dark), start = Offset(0.2f, 0f), end = Offset(0.8f, 1f)))
    }
}

private fun DrawScope.flower(at: Offset, scale: Float) {
    val r = 4.6f * density * scale
    repeat(5) { k ->
        val a = k * 2 * PI.toFloat() / 5 - PI.toFloat() / 2
        val c = at + Offset(cos(a), sin(a)) * (r * 1.05f)
        drawCircle(Brush.radialGradient(0f to Color(0xFFFFEEF3), 1f to Color(0xFFFFA9C6), center = c, radius = r), r, c)
    }
    drawCircle(Color(0xFFFFD86B), r * 0.55f, at)
}

private fun DrawScope.cobweb(size: Size, dp: Float) {
    val corner = Offset(size.width, 0f)
    val radius = 40 * dp
    val web = Path()
    val angles = List(4) { PI.toFloat() / 2 + it * (PI.toFloat() / 2) / 3 }
    angles.forEach { a -> web.moveTo(corner.x, corner.y); web.lineTo(corner.x + cos(a) * radius, corner.y + sin(a) * radius) }
    listOf(14f, 26f, 37f).forEach { ring ->
        val r = ring * dp
        for (i in 0 until angles.size - 1) {
            val a = angles[i]
            val b = angles[i + 1]
            if (i == 0) web.moveTo(corner.x + cos(a) * r, corner.y + sin(a) * r)
            val mid = (a + b) / 2
            web.quadraticTo(corner.x + cos(mid) * r * 0.85f, corner.y + sin(mid) * r * 0.85f, corner.x + cos(b) * r, corner.y + sin(b) * r)
        }
    }
    drawPath(web, Color.White.copy(alpha = 0.55f), style = Stroke(0.8f * dp, cap = StrokeCap.Round))
}

/** A Ukrainian painted egg: red with a yellow wave band and dots. */
private fun DrawScope.pysanka(at: Offset, dp: Float, pick: Int) {
    val w = 15 * dp
    val h = 20 * dp
    val topLeft = at - Offset(w / 2, h / 2)
    val shell = Path().apply {
        addPath(Shapes.egg)
        transform(Matrix().apply { scale(w, h) })
        translate(topLeft)
    }
    rotate((pick % 40 - 20).toFloat(), at) {
        drawPath(shell, Brush.verticalGradient(listOf(Color(0xFFE0392F), Color(0xFF9E1E18)), startY = topLeft.y, endY = topLeft.y + h))
        clipPath(shell) {
            // Yellow belt with a black zigzag, dots above and below: the classic pysanka pattern.
            val beltTop = at.y - 2.2f * dp
            val beltHeight = 4.4f * dp
            drawRect(Color(0xFFFFD34D), Offset(topLeft.x, beltTop), Size(w, beltHeight))
            drawLine(Color(0xFF1C1C1C), Offset(topLeft.x, beltTop), Offset(topLeft.x + w, beltTop), strokeWidth = 0.7f * dp)
            drawLine(Color(0xFF1C1C1C), Offset(topLeft.x, beltTop + beltHeight), Offset(topLeft.x + w, beltTop + beltHeight), strokeWidth = 0.7f * dp)
            val zigzag = Path().apply {
                moveTo(topLeft.x, beltTop + beltHeight * 0.75f)
                var x = topLeft.x
                var up = true
                while (x < topLeft.x + w) {
                    x += 1.8f * dp
                    lineTo(x, beltTop + beltHeight * if (up) 0.25f else 0.75f)
                    up = !up
                }
            }
            drawPath(zigzag, Color(0xFF9E1E18), style = Stroke(0.7f * dp))
            listOf(-0.25f, 0f, 0.25f).forEach { dx -> drawCircle(Color(0xFFFFD34D), 1f * dp, Offset(at.x + dx * w, at.y - 5.5f * dp)) }
            listOf(-0.2f, 0.2f).forEach { dx -> drawCircle(Color.White, 0.9f * dp, Offset(at.x + dx * w, at.y + 5.5f * dp)) }
        }
        drawOval(Color.White.copy(alpha = 0.3f), topLeft + Offset(w * 0.2f, h * 0.12f), Size(w * 0.18f, h * 0.2f))
    }
}

/** Blue and yellow sash across the top-right corner. */
private fun DrawScope.ribbon(size: Size, dp: Float) {
    val w = size.width
    val band = 6 * dp
    fun stripe(offset: Float, color: Color) {
        val start = 26 * dp + offset
        drawPath(Path().apply {
            moveTo(w - start - band, 0f)
            lineTo(w - start, 0f)
            lineTo(w, start)
            lineTo(w, start + band)
            close()
        }, color)
    }
    stripe(0f, Color(0xFF2F7BEF))
    stripe(band, Color(0xFFFFD500))
}
