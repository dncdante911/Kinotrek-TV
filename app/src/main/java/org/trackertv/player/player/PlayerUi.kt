package org.trackertv.player.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser

/** Colors of the View-based player, the same as the Compose palette. */
internal object PlayerStyle {
    const val TEXT = 0xFFF2F5FA.toInt()
    const val MUTED = 0xFFA3AEC0.toInt()
    const val ACCENT = 0xFF5EE0D2.toInt()
    const val PANEL = 0xF2141A25.toInt()
    const val CHIP = 0x26FFFFFF
    const val FOCUS = 0xFFF2F5FA.toInt()
    const val ON_FOCUS = 0xFF0A0E16.toInt()
}

/** Material icon paths on a 24×24 grid. */
internal object PlayerIcons {
    const val PLAY = "M8,5v14l11,-7z"
    const val PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
    const val PREVIOUS = "M6,6h2v12H6zM9.5,12l8.5,6V6z"
    const val NEXT = "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"
    const val REWIND = "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z"
    const val FORWARD = "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z"
    const val AUDIO = "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z"
    const val SUBTITLES = "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z"
    const val MORE = "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
}

internal fun View.dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.playerText(sizeSp: Float, color: Int = PlayerStyle.TEXT, bold: Boolean = false) = TextView(this).apply {
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
}

internal fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/** A Material path filled with one color. */
internal class PathIcon(pathData: String, color: Int) : Drawable() {
    private var path: Path = PathParser.createPathFromPathData(pathData)
    private val scaled = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    fun setPath(pathData: String) {
        path = PathParser.createPathFromPathData(pathData)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        matrix.setScale(box.width() / 24f, box.height() / 24f)
        matrix.postTranslate(box.left.toFloat(), box.top.toFloat())
        path.transform(matrix, scaled)
        canvas.drawPath(scaled, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Focusable round icon button; with a text it becomes a pill with the label next to the icon. */
internal class ControlButton(context: Context, icon: String, label: String, withText: Boolean = false) : LinearLayout(context) {
    private val iconDrawable = PathIcon(icon, PlayerStyle.TEXT)
    private val textView: TextView? = if (withText) TextView(context).apply {
        textSize = 17f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(240)
    } else null
    private val idle = rounded(PlayerStyle.CHIP, dp(28).toFloat())
    private val focused = rounded(PlayerStyle.FOCUS, dp(28).toFloat())

    init {
        id = generateViewId()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        contentDescription = label
        addView(ImageView(context).apply { setImageDrawable(iconDrawable) }, LayoutParams(dp(28), dp(28)))
        if (textView != null) {
            addView(textView, LayoutParams(-2, -2).apply { marginStart = dp(10) })
            setPadding(dp(18), 0, dp(22), 0)
        }
        setOnFocusChangeListener { _, hasFocus -> style(hasFocus) }
        style(false)
    }

    fun setIcon(pathData: String) = iconDrawable.setPath(pathData)

    fun setText(text: String) {
        textView?.text = text
    }

    private fun style(hasFocus: Boolean) {
        background = if (hasFocus) focused else idle
        iconDrawable.color = if (hasFocus) PlayerStyle.ON_FOCUS else PlayerStyle.TEXT
        textView?.setTextColor(if (hasFocus) PlayerStyle.ON_FOCUS else PlayerStyle.TEXT)
        animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
    }
}

/**
 * Progress line with the buffered part. Focused, left/right move a preview position (faster while
 * held) and the seek happens once the remote is quiet, so a long jump doesn't reopen the torrent
 * stream at every step.
 */
internal class SeekBarView(context: Context) : View(context) {
    /** Preview position while the user moves along the line. */
    var onScrub: ((Long) -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var target: Long? = null
    private val commit = Runnable { finishScrub() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    val shownPosition: Long get() = target ?: position
    val isScrubbing: Boolean get() = target != null

    init {
        id = generateViewId()
        isFocusable = true
        isClickable = true
        minimumHeight = dp(32)
    }

    fun update(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (direction != 0 && duration > 0) {
            val step = when {
                event.repeatCount < 6 -> 10_000L
                event.repeatCount < 20 -> 30_000L
                else -> 60_000L
            }
            val next = (shownPosition + direction * step).coerceIn(0, duration)
            target = next
            onScrub?.invoke(next)
            removeCallbacks(commit)
            postDelayed(commit, SCRUB_COMMIT_MS)
            invalidate()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (isScrubbing) finishScrub() else performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && isScrubbing) finishScrub()
        invalidate()
    }

    private fun finishScrub() {
        removeCallbacks(commit)
        val value = target ?: return
        target = null
        position = value
        onSeek?.invoke(value)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val thumb = dp(10).toFloat()
        val line = if (isFocused) dp(8).toFloat() else dp(5).toFloat()
        val left = paddingLeft + thumb
        val right = width - paddingRight - thumb
        val cy = height / 2f
        fun x(value: Long) = if (duration > 0) left + (right - left) * (value.coerceIn(0, duration).toFloat() / duration) else left

        rect.set(left, cy - line / 2, right, cy + line / 2)
        paint.color = 0x40FFFFFF
        canvas.drawRoundRect(rect, line, line, paint)
        rect.right = x(buffered)
        paint.color = 0x80FFFFFF.toInt()
        canvas.drawRoundRect(rect, line, line, paint)
        rect.right = x(shownPosition)
        paint.color = PlayerStyle.ACCENT
        canvas.drawRoundRect(rect, line, line, paint)
        if (isFocused) {
            paint.color = PlayerStyle.FOCUS
            canvas.drawCircle(x(shownPosition), cy, thumb, paint)
        }
    }

    private companion object {
        const val SCRUB_COMMIT_MS = 800L
    }
}
