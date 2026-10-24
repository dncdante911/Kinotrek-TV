package org.trackertv.player.ui.decor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import org.trackertv.player.data.Occasion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The still part of the decoration around the screen edges: snow on the page, a garland, a
 * branch… Paths are built once per screen size; per frame only a few lights and a sway move.
 * Everything stays in the margins so it never hides text or focus.
 */
internal class Scenery(private val occasion: Occasion, private val size: Size, private val dp: Float) {
    private val w = size.width
    private val h = size.height
    private val random = Random(occasion.ordinal * 7919 + w.toInt())

    private val cornice = if (occasion == Occasion.WINTER) cornice() else null
    private val icicles = if (occasion == Occasion.WINTER) icicles() else emptyList()
    private val drift = if (occasion == Occasion.WINTER || occasion == Occasion.NEW_YEAR) drift() else null
    private val garland = if (occasion == Occasion.NEW_YEAR) garland() else null
    private val branch = if (occasion == Occasion.SPRING || occasion == Occasion.EASTER) branch() else null
    private val leafPiles = if (occasion == Occasion.AUTUMN) leafPiles() else emptyList()
    private val web = if (occasion == Occasion.HALLOWEEN) web() else null
    private val rays = if (occasion == Occasion.SUMMER) rays() else null

    /** Glows behind the particles. */
    fun back(scope: DrawScope, t: Float) = with(scope) {
        when (occasion) {
            Occasion.WINTER, Occasion.NEW_YEAR -> {
                glow(Offset(0f, 0f), 240 * dp, Color.White.copy(alpha = 0.09f))
                glow(Offset(w, 0f), 240 * dp, Color.White.copy(alpha = 0.09f))
                if (occasion == Occasion.NEW_YEAR) glow(Offset(w / 2, -40 * dp), 420 * dp, Color(0x1FFFC86B))
            }
            Occasion.SPRING -> glow(Offset(w, 0f), 380 * dp, Color(0x2EFF9EC2))
            Occasion.EASTER -> {
                glow(Offset(w, 0f), 380 * dp, Color(0x2EFFE7A3))
                glow(Offset(0f, h), 320 * dp, Color(0x24C9A8FF))
            }
            Occasion.SUMMER -> {
                val sun = Offset(w - 60 * dp, -30 * dp)
                glow(sun, 460 * dp, Color(0x33FFC66B))
                rays?.let { path -> rotate(t * 2.5f, sun) { drawPath(path, Brush.radialGradient(0f to Color(0x1CFFE6A6), 0.6f to Color(0x0AFFE6A6), 1f to Color.Transparent, center = sun, radius = 560 * dp)) } }
            }
            Occasion.AUTUMN -> {
                glow(Offset(0f, h), 380 * dp, Color(0x33FF8A3D))
                glow(Offset(w, 0f), 300 * dp, Color(0x1FFFB347))
            }
            Occasion.VALENTINE -> {
                glow(Offset(w, 0f), 380 * dp, Color(0x33FF5C7A))
                glow(Offset(0f, h), 320 * dp, Color(0x26FF8FA3))
            }
            Occasion.HALLOWEEN -> {
                glow(Offset(w / 2, h + 120 * dp), w * 0.55f, Color(0x38FF7A1A))
                glow(Offset(w, 0f), 300 * dp, Color(0x268E5BD6))
            }
            Occasion.INDEPENDENCE -> {
                glow(Offset(0f, 0f), 380 * dp, Color(0x333D8BFF))
                glow(Offset(w, h), 380 * dp, Color(0x2EFFD500))
            }
        }
    }

    /** Things in front of the particles: snow lands behind the drift, the garland hangs over it. */
    fun front(scope: DrawScope, t: Float) = with(scope) {
        cornice?.let {
            drawPath(it, Color(0x55000000), style = Stroke(2 * dp))
            drawPath(it, Brush.verticalGradient(listOf(Color.White, Color(0xFFDCE8F4)), endY = 14 * dp))
        }
        icicles.forEach { drawPath(it, Brush.verticalGradient(listOf(Color(0xF0FFFFFF), Color(0x66BFD9F0)), startY = 5 * dp, endY = 24 * dp)) }
        drift?.let {
            drawPath(it, Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xFFC9D9EA)), startY = h - 12 * dp, endY = h))
            drawPath(it, Color.White, style = Stroke(1.2f * dp))
        }
        garland?.let { drawGarland(it, t) }
        branch?.let { drawBranch(it, t) }
        leafPiles.forEach { leaf -> drawLeaf(leaf) }
        web?.let { drawWeb(it, t) }
        if (occasion == Occasion.INDEPENDENCE) {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF3D8BFF), Color(0xFFFFD500))), size = Size(w, 3 * dp), alpha = 0.85f)
        }
    }

    private fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
        drawCircle(Brush.radialGradient(0f to color, 1f to Color.Transparent, center = center, radius = radius), radius, center)
    }

    // Winter: snow lying along the top of the page with a few icicles, and a drift at the bottom.

    private fun cornice() = Path().apply {
        moveTo(0f, 0f)
        lineTo(w, 0f)
        lineTo(w, 5 * dp)
        var x = w
        while (x > 0f) {
            val next = (x - (24 + random.nextFloat() * 34) * dp).coerceAtLeast(0f)
            quadraticTo((x + next) / 2, (8 + random.nextFloat() * 6) * dp, next, (4 + random.nextFloat() * 2) * dp)
            x = next
        }
        close()
    }

    private fun icicles(): List<Path> {
        val list = ArrayList<Path>()
        var x = (40 + random.nextFloat() * 60) * dp
        while (x < w - 20 * dp) {
            val half = (2 + random.nextFloat() * 1.8f) * dp
            val length = (7 + random.nextFloat() * 13) * dp
            list += Path().apply {
                moveTo(x - half, 5 * dp)
                quadraticTo(x - half * 0.3f, 5 * dp + length * 0.6f, x, 5 * dp + length)
                quadraticTo(x + half * 0.3f, 5 * dp + length * 0.6f, x + half, 5 * dp)
                close()
            }
            x += (70 + random.nextFloat() * 110) * dp
        }
        return list
    }

    private fun drift() = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h - 5 * dp)
        var x = 0f
        while (x < w) {
            val next = (x + (60 + random.nextFloat() * 90) * dp).coerceAtMost(w)
            quadraticTo((x + next) / 2, h - (8 + random.nextFloat() * 6) * dp, next, h - (3 + random.nextFloat() * 3) * dp)
            x = next
        }
        lineTo(w, h)
        close()
    }

    // New Year: a garland of lights along the top edge, above the menu.

    private class Bulb(val at: Offset, val color: Color, val phase: Float, val rate: Float)
    private class Garland(val wire: Path, val bulbs: List<Bulb>)

    private fun garland(): Garland {
        val colors = listOf(Color(0xFFFF4D5E), Color(0xFFFFC94D), Color(0xFF5EE0D2), Color(0xFFFF7AD9), Color(0xFF7CE05E), Color(0xFF6BA8FF))
        val wire = Path()
        val bulbs = ArrayList<Bulb>()
        val spans = 6
        val sag = 12 * dp
        for (i in 0 until spans) {
            val a = Offset(w * i / spans, -2 * dp)
            val b = Offset(w * (i + 1) / spans, -2 * dp)
            val control = Offset((a.x + b.x) / 2, a.y + sag * 2)
            wire.moveTo(a.x, a.y)
            wire.quadraticTo(control.x, control.y, b.x, b.y)
            val perSpan = 5
            for (k in 1..perSpan) {
                val u = k / (perSpan + 1f)
                val p = a * ((1 - u) * (1 - u)) + control * (2 * (1 - u) * u) + b * (u * u)
                bulbs += Bulb(p, colors[(i * perSpan + k) % colors.size], random.nextFloat() * 6.3f, 1.2f + random.nextFloat() * 1.8f)
            }
        }
        return Garland(wire, bulbs)
    }

    private fun DrawScope.drawGarland(garland: Garland, t: Float) {
        drawPath(garland.wire, Color(0xFF5C7A66), style = Stroke(1.6f * dp))
        garland.bulbs.forEach { bulb ->
            val light = 0.55f + 0.45f * sin(t * bulb.rate + bulb.phase)
            val center = bulb.at + Offset(0f, 7 * dp)
            drawCircle(Brush.radialGradient(0f to bulb.color.copy(alpha = 0.55f * light), 1f to Color.Transparent, center = center, radius = 16 * dp), 16 * dp, center)
            drawRect(Color(0xFF8C9A92), bulb.at + Offset(-2 * dp, 0f), Size(4 * dp, 3 * dp))
            drawOval(bulb.color.copy(alpha = 0.45f + 0.55f * light), bulb.at + Offset(-3.5f * dp, 2.5f * dp), Size(7 * dp, 10 * dp))
            drawOval(Color.White.copy(alpha = 0.5f * light), bulb.at + Offset(-1.8f * dp, 4 * dp), Size(2 * dp, 3.5f * dp))
        }
    }

    // Spring (blossom) and Easter (pussy willow): a branch reaching in from the top-right corner.

    private class Branch(val wood: Path, val twigs: Path, val buds: List<Offset>)

    private fun branch(): Branch {
        val x = w
        val wood = Path().apply {
            moveTo(x + 6 * dp, 64 * dp)
            cubicTo(x - 40 * dp, 74 * dp, x - 86 * dp, 104 * dp, x - 140 * dp, 98 * dp)
        }
        val twigs = Path().apply {
            moveTo(x - 58 * dp, 84 * dp); quadraticTo(x - 66 * dp, 108 * dp, x - 80 * dp, 128 * dp)
            moveTo(x - 102 * dp, 100 * dp); quadraticTo(x - 108 * dp, 86 * dp, x - 118 * dp, 74 * dp)
            moveTo(x - 24 * dp, 70 * dp); quadraticTo(x - 30 * dp, 90 * dp, x - 26 * dp, 110 * dp)
        }
        val buds = listOf(
            Offset(x - 140 * dp, 98 * dp), Offset(x - 118 * dp, 74 * dp), Offset(x - 80 * dp, 128 * dp),
            Offset(x - 26 * dp, 110 * dp), Offset(x - 92 * dp, 104 * dp), Offset(x - 50 * dp, 80 * dp), Offset(x - 66 * dp, 110 * dp),
        )
        return Branch(wood, twigs, buds)
    }

    private fun DrawScope.drawBranch(branch: Branch, t: Float) {
        rotate(sin(t * 0.6f) * 1.4f, Offset(w, 64 * dp)) {
            drawPath(branch.wood, Color(0xFF6B4A3A), style = Stroke(3.5f * dp, cap = StrokeCap.Round))
            drawPath(branch.twigs, Color(0xFF6B4A3A), style = Stroke(1.8f * dp, cap = StrokeCap.Round))
            branch.buds.forEachIndexed { index, at ->
                if (occasion == Occasion.EASTER) catkin(at, index) else blossom(at, if (index % 3 == 2) 0.7f else 1f)
            }
        }
    }

    private fun DrawScope.blossom(at: Offset, scale: Float) {
        val r = 4.4f * dp * scale
        repeat(5) { k ->
            val a = k * 2 * PI.toFloat() / 5 - PI.toFloat() / 2
            val c = at + Offset(cos(a), sin(a)) * (r * 1.05f)
            drawCircle(Brush.radialGradient(0f to Color(0xFFFFE3EC), 1f to Color(0xFFFFA9C6), center = c, radius = r), r, c)
        }
        drawCircle(Color(0xFFFFD86B), r * 0.55f, at)
    }

    private fun DrawScope.catkin(at: Offset, index: Int) {
        val tilt = if (index % 2 == 0) -18f else 14f
        rotate(tilt, at) {
            drawOval(Color(0xFF7A5A44), at + Offset(-1.2f * dp, -1 * dp), Size(2.4f * dp, 4 * dp))
            drawOval(Brush.radialGradient(0f to Color.White, 1f to Color(0xFFCFC8BC), center = at + Offset(0f, 6 * dp), radius = 6 * dp),
                at + Offset(-3.5f * dp, 1.5f * dp), Size(7 * dp, 11 * dp))
        }
    }

    // Autumn: a few leaves fallen into the bottom corners.

    private class Leaf(val at: Offset, val side: Float, val angle: Float, val color: Color, val dark: Color)

    private fun leafPiles(): List<Leaf> {
        val colors = listOf(Color(0xFFE8742A) to Color(0xFFB4421E), Color(0xFFD2452F) to Color(0xFF8E2219), Color(0xFFF2B53A) to Color(0xFFC77F16))
        return List(10) { i ->
            val left = i < 5
            val x = if (left) random.nextFloat() * 120 * dp else w - random.nextFloat() * 120 * dp
            val spread = abs(if (left) x else w - x) / (120 * dp)
            val (color, dark) = colors[random.nextInt(colors.size)]
            Leaf(Offset(x, h + (2 - random.nextFloat() * 10 * (1 - spread)) * dp), (18 + random.nextFloat() * 10) * dp, random.nextFloat() * 360f, color, dark)
        }
    }

    private fun DrawScope.drawLeaf(leaf: Leaf) {
        rotate(leaf.angle, leaf.at) {
            drawUnit(Shapes.maple, leaf.at - Offset(leaf.side / 2, leaf.side / 2), leaf.side,
                Brush.linearGradient(listOf(leaf.color, leaf.dark), start = Offset(0.2f, 0f), end = Offset(0.8f, 1f)), alpha = 0.92f)
        }
    }

    // Halloween: a cobweb in the top-left corner with a spider slowly going up and down.

    private fun web() = Path().apply {
        val spokes = 6
        val radius = 130 * dp
        val angles = List(spokes) { it * (PI.toFloat() / 2) / (spokes - 1) }
        angles.forEach { a -> moveTo(0f, 0f); lineTo(cos(a) * radius, sin(a) * radius) }
        listOf(30f, 58f, 86f, 114f).forEach { ring ->
            val r = ring * dp
            for (i in 0 until spokes - 1) {
                val a = angles[i]
                val b = angles[i + 1]
                val mid = (a + b) / 2
                if (i == 0) moveTo(cos(a) * r, sin(a) * r)
                quadraticTo(cos(mid) * r * 0.86f, sin(mid) * r * 0.86f, cos(b) * r, sin(b) * r)
            }
        }
    }

    private fun DrawScope.drawWeb(web: Path, t: Float) {
        drawPath(web, Color.White.copy(alpha = 0.28f), style = Stroke(1 * dp))
        val anchor = Offset(64 * dp, 52 * dp)
        val body = anchor + Offset(0f, (46 + sin(t * 0.8f) * 16) * dp)
        drawLine(Color.White.copy(alpha = 0.35f), anchor, body, strokeWidth = 0.8f * dp)
        for (side in listOf(-1f, 1f)) {
            for (k in 0 until 4) {
                val knee = body + Offset(side * 6 * dp, (-4 + k * 3) * dp)
                drawLine(Color(0xFFB9A6D6), body, knee, strokeWidth = 1 * dp, cap = StrokeCap.Round)
                drawLine(Color(0xFFB9A6D6), knee, knee + Offset(side * 3 * dp, (2 + k) * dp), strokeWidth = 1 * dp, cap = StrokeCap.Round)
            }
        }
        drawCircle(Color(0xFF3A2C4A), 4.6f * dp, body)
        drawCircle(Color(0xFF3A2C4A), 3 * dp, body + Offset(0f, -5 * dp))
        drawCircle(Color(0xFFFFA43A), 0.9f * dp, body + Offset(-1.2f * dp, -5.5f * dp))
        drawCircle(Color(0xFFFFA43A), 0.9f * dp, body + Offset(1.2f * dp, -5.5f * dp))
    }

    // Summer: soft sun rays turning very slowly from the top-right corner.

    private fun rays() = Path().apply {
        val sun = Offset(w - 60 * dp, -30 * dp)
        val length = 620 * dp
        repeat(9) { k ->
            val a = (k * 22f + 70f) * PI.toFloat() / 180
            val spread = 4f * PI.toFloat() / 180
            moveTo(sun.x, sun.y)
            lineTo(sun.x + cos(a - spread) * length, sun.y + sin(a - spread) * length)
            lineTo(sun.x + cos(a + spread) * length, sun.y + sin(a + spread) * length)
            close()
        }
    }
}
