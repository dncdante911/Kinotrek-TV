package org.trackertv.player.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.blue
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.trackertv.player.net.Http
import java.io.File
import java.security.MessageDigest

/**
 * Covers and screenshots from image hosts. Decoded straight to the size on screen (a 2000 px
 * poster becomes ~300 px), cached in memory and on disk, at most three downloads at once.
 */
object Images {
    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(3)
    private const val MAX_BYTES = 8_000_000
    /** Main color of each loaded image; snapshot state, so a card's glow appears as soon as its poster does. */
    private val tints = mutableStateMapOf<String, Color>()
    /** Width of the original image, so a backdrop is only ever a picture big enough to stay sharp. */
    private val widths = mutableStateMapOf<String, Int>()

    fun cached(url: String, widthPx: Int): ImageBitmap? = memory.get("$widthPx|$url")

    fun tint(url: String?): Color? = url?.let { tints[it] }

    fun sourceWidth(url: String?): Int? = url?.let { widths[it] }

    suspend fun load(context: Context, url: String, widthPx: Int): ImageBitmap? {
        val key = "$widthPx|$url"
        memory.get(key)?.let { return it }
        return gate.withPermit {
            memory.get(key)?.let { return@withPermit it }
            val file = File(File(context.cacheDir, "images").apply { mkdirs() }, sha1(url))
            val bytes = withContext(Dispatchers.IO) {
                if (file.exists()) file.readBytes() else download(url)?.also { runCatching { file.writeBytes(it) } }
            } ?: return@withPermit null
            val decoded = withContext(Dispatchers.Default) { decode(bytes, widthPx)?.let { Triple(it.first, tintOf(it.first), it.second) } } ?: return@withPermit null
            val (bitmap, tint, width) = decoded
            memory.put(key, bitmap)
            if (tints.size > 500) {
                tints.clear()
                widths.clear()
            }
            tints[url] = tint
            widths[url] = width
            bitmap
        }
    }

    private suspend fun download(start: String): ByteArray? = try {
        var url = start
        var result: ByteArray? = null
        for (hop in 0..3) {
            val response = Http.request(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android TV) Kinotrek"), maxBytes = MAX_BYTES)
            val next = response.location
            if (response.code in 300..399 && next != null) {
                url = java.net.URL(java.net.URL(url), next).toString()
                continue
            }
            result = response.body.takeIf { response.ok && it.isNotEmpty() && response.contentType?.startsWith("image") != false }
            break
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The bitmap scaled down near [widthPx], and the original width. */
    private fun decode(bytes: ByteArray, widthPx: Int): Pair<ImageBitmap, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap() to bounds.outWidth
    }

    /**
     * A glow color for the image: the average of a 16×24 thumbnail with colorful pixels counting
     * more, then brightened, so a dark poster still glows and a grey one stays grey.
     */
    private fun tintOf(image: ImageBitmap): Color {
        val small = image.asAndroidBitmap().scale(16, 24)
        var r = 0f; var g = 0f; var b = 0f; var weight = 0f
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val pixel = small[x, y]
            android.graphics.Color.colorToHSV(pixel, hsv)
            val w = 0.15f + hsv[1] * hsv[2]
            r += pixel.red * w
            g += pixel.green * w
            b += pixel.blue * w
            weight += w
        }
        android.graphics.Color.RGBToHSV((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.4f).coerceAtMost(0.85f)
        hsv[2] = 0.9f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Keeps the disk cache bounded; called once per launch. */
    fun trim(context: Context, maxBytes: Long = 150L shl 20) {
        val files = File(context.cacheDir, "images").listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { file -> total += file.length(); if (total > maxBytes) file.delete() }
    }
}

/** Shows [url] once loaded (fading in); keeps whatever is behind it visible until then. */
@Composable
fun RemoteImage(url: String?, widthPx: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, description: String? = null) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val image by produceState(Images.cached(url, widthPx), url, widthPx) {
        if (value == null) value = Images.load(context, url, widthPx)
    }
    val alpha by animateFloatAsState(if (image != null) 1f else 0f, tween(250), label = "fade")
    image?.let { Image(it, description, modifier.alpha(alpha), contentScale = contentScale) }
}
