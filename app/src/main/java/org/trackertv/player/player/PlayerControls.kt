package org.trackertv.player.player

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import org.trackertv.player.PlayerActivity
import java.util.Date

/**
 * On-screen controls: title and clock on top; the progress line, transport buttons, audio,
 * subtitles and the "more" menu at the bottom. They hide after a few seconds of playback and
 * stay while paused.
 */
internal class PlayerControls(context: Context) : FrameLayout(context) {
    val title: TextView = context.playerText(26f, bold = true).apply { maxLines = 2 }
    val subtitle: TextView = context.playerText(16f, PlayerStyle.MUTED)
    private val clock = context.playerText(26f, bold = true).apply { gravity = Gravity.END }
    private val endsAt = context.playerText(15f, PlayerStyle.MUTED).apply { gravity = Gravity.END }
    /** Torrent speed and peers; empty for direct links. */
    val status: TextView = context.playerText(15f, PlayerStyle.MUTED).apply { gravity = Gravity.END }

    val seekBar = SeekBarView(context)
    private val position = context.playerText(17f)
    private val duration = context.playerText(17f, PlayerStyle.MUTED)

    val previous = ControlButton(context, PlayerIcons.PREVIOUS, "Предыдущий файл")
    val rewind = ControlButton(context, PlayerIcons.REWIND, "Назад")
    val playPause = ControlButton(context, PlayerIcons.PAUSE, "Пауза")
    val forward = ControlButton(context, PlayerIcons.FORWARD, "Вперёд")
    val next = ControlButton(context, PlayerIcons.NEXT, "Следующий файл")
    val audio = ControlButton(context, PlayerIcons.AUDIO, "Звуковая дорожка", withText = true)
    val subtitles = ControlButton(context, PlayerIcons.SUBTITLES, "Субтитры", withText = true)
    val more = ControlButton(context, PlayerIcons.MORE, "Ещё")

    /** True while something (a pause) should keep the controls on screen. */
    var keepVisible: () -> Boolean = { false }
    var isOpen = false
        private set
    private val autoHide = Runnable { if (keepVisible()) ping() else hide() }

    init {
        visibility = GONE
        clipChildren = false

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(28), dp(48), dp(56))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(clock)
                addView(endsAt)
                addView(status)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(32) })
        }
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        val timeline = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(position, LinearLayout.LayoutParams(-2, -2))
            addView(seekBar, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(duration, LinearLayout.LayoutParams(-2, -2))
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            listOf(previous, rewind, playPause, forward, next).forEach { addView(it, round()) }
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(audio, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(14) })
            addView(subtitles, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(14) })
            addView(more, round().apply { marginStart = dp(14); marginEnd = 0 })
        }
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(48), dp(72), dp(48), dp(36))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            addView(timeline, LinearLayout.LayoutParams(-1, -2))
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))

        // Down from the line always lands on play; the rows never let focus wander off their ends.
        val row = listOf(previous, rewind, playPause, forward, next, audio, subtitles, more)
        seekBar.nextFocusDownId = playPause.id
        seekBar.nextFocusUpId = seekBar.id
        row.forEach {
            it.nextFocusUpId = seekBar.id
            it.nextFocusDownId = it.id
        }
        previous.nextFocusLeftId = previous.id
        more.nextFocusRightId = more.id
        seekBar.onScrub = { position.text = PlayerActivity.formatTime(it); position.setTextColor(PlayerStyle.ACCENT) }
    }

    private fun round() = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) }

    fun setPlaylist(hasPrevious: Boolean, hasNext: Boolean, multiple: Boolean) {
        previous.visibility = if (multiple) VISIBLE else GONE
        next.visibility = if (multiple) VISIBLE else GONE
        previous.alpha = if (hasPrevious) 1f else 0.4f
        next.alpha = if (hasNext) 1f else 0.4f
        rewind.nextFocusLeftId = if (multiple) View.NO_ID else rewind.id
    }

    fun show(focus: View = playPause) {
        if (!isOpen) {
            isOpen = true
            visibility = VISIBLE
            animate().cancel()
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
        }
        if (focus.isVisible && !focus.hasFocus()) focus.requestFocus()
        ping()
    }

    fun hide() {
        if (!isOpen) return
        isOpen = false
        removeCallbacks(autoHide)
        animate().cancel()
        animate().alpha(0f).setDuration(200).withEndAction { if (!isOpen) visibility = GONE }.start()
    }

    /** Restarts the auto-hide countdown after the user did something. */
    fun ping() {
        removeCallbacks(autoHide)
        if (isOpen) postDelayed(autoHide, HIDE_AFTER_MS)
    }

    fun update(player: Player) {
        val total = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val speed = player.playbackParameters.speed.takeIf { it > 0 } ?: 1f
        update(player.currentPosition, player.bufferedPosition, total, playing, speed)
    }

    fun update(current: Long, buffered: Long, total: Long, playing: Boolean, speed: Float) {
        seekBar.update(current, buffered, total)
        if (!seekBar.isScrubbing) {
            position.text = PlayerActivity.formatTime(current)
            position.setTextColor(PlayerStyle.TEXT)
        }
        duration.text = if (total > 0) PlayerActivity.formatTime(total) else ""
        playPause.setIcon(if (playing) PlayerIcons.PAUSE else PlayerIcons.PLAY)
        playPause.contentDescription = if (playing) "Пауза" else "Смотреть"
        val timeFormat = DateFormat.getTimeFormat(context)
        val now = System.currentTimeMillis()
        clock.text = timeFormat.format(Date(now))
        endsAt.text = if (total > 0) "Закончится в ${timeFormat.format(Date(now + ((total - current) / speed).toLong()))}" else ""
    }

    private companion object {
        const val HIDE_AFTER_MS = 5_000L
    }
}
