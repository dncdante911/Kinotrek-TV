package org.trackertv.player.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    fun cached(url: String, widthPx: Int): ImageBitmap? = memory.get("$widthPx|$url")

    suspend fun load(context: Context, url: String, widthPx: Int): ImageBitmap? {
        val key = "$widthPx|$url"
        memory.get(key)?.let { return it }
        return gate.withPermit {
            memory.get(key)?.let { return@withPermit it }
            val file = File(File(context.cacheDir, "images").apply { mkdirs() }, sha1(url))
            val bytes = withContext(Dispatchers.IO) {
                if (file.exists()) file.readBytes() else download(url)?.also { runCatching { file.writeBytes(it) } }
            } ?: return@withPermit null
            val bitmap = withContext(Dispatchers.Default) { decode(bytes, widthPx) } ?: return@withPermit null
            memory.put(key, bitmap)
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

    private fun decode(bytes: ByteArray, widthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
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
