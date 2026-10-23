package org.trackertv.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.trackertv.player.device.DeviceCaps
import org.trackertv.player.player.PlaybackRequest
import org.trackertv.player.torrent.LocalTorrentService
import java.util.Locale

/**
 * Full-screen Media3 player. The player exists only between onStart and onStop, so a TV that goes
 * to its home screen frees the hardware decoder; the position is restored when the user returns.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var request: PlaybackRequest
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var topBar: View
    private lateinit var hintView: TextView
    private lateinit var upNextView: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var nextButton: Button

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private var playedSeconds = 0
    private var surfaceView: SurfaceView? = null
    private var contentFrameRate = 0f
    private var recommendationShown = false
    private var resumeIndex = 0
    private var resumePositionMs = 0L
    private var hideHint: Runnable? = null

    private val settings get() = app.settings.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PlaybackRequest.from(intent) ?: run { finish(); return }
        resumeIndex = savedInstanceState?.getInt(STATE_INDEX) ?: request.startIndex
        resumePositionMs = savedInstanceState?.getLong(STATE_POSITION) ?: request.startPositionMs
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(buildLayout())
    }

    override fun onStart() {
        super.onStart()
        if (request.torrent) LocalTorrentService.acquire()
        initializePlayer()
    }

    override fun onStop() {
        releasePlayer()
        if (request.torrent) LocalTorrentService.release()
        app.library.flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putInt(STATE_INDEX, it.currentMediaItemIndex)
            outState.putLong(STATE_POSITION, it.currentPosition)
        }
    }

    private fun initializePlayer() {
        if (player != null) return
        val trackSelector = DefaultTrackSelector(this).apply {
            val language = settings.audioLanguage.code
            if (language.isNotEmpty()) setParameters(buildUponParameters().setPreferredAudioLanguage(language))
        }
        // Torrent streams stall while pieces arrive: allow slow reads and keep a deeper buffer.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TrackerTV/${BuildConfig.VERSION_NAME}")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .build()
        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .apply { if (Build.VERSION.SDK_INT >= 30) setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) }
            .build()
        exo.pauseAtEndOfMediaItems = !settings.autoplayNext
        exo.addListener(listener)
        exo.setMediaItems(request.entries.map { entry ->
            MediaItem.Builder()
                .setUri(entry.url)
                .setMediaId(entry.key)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
                .build()
        }, resumeIndex, resumePositionMs)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        session = MediaSession.Builder(this, exo).build()
        playerView.player = exo
        playerView.requestFocus()
        updateTitle()
        if (resumePositionMs > 5_000) showHint("Продолжаем с ${formatTime(resumePositionMs)}")
        ticker = lifecycleScope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                updateUpNext()
                if (++seconds % 10 == 0) saveProgress()
                // Only real playback counts toward the occasional support note on the home screen.
                if (exo.isPlaying && ++playedSeconds >= 60) {
                    playedSeconds = 0
                    app.settings.addWatchedMinute()
                }
            }
        }
    }

    private fun releasePlayer() {
        val exo = player ?: return
        saveProgress()
        resumeIndex = exo.currentMediaItemIndex
        resumePositionMs = exo.currentPosition
        ticker?.cancel()
        clearFrameRate()
        session?.release()
        session = null
        playerView.player = null
        exo.removeListener(listener)
        exo.release()
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The previous file played to the end.
                val previous = player?.previousMediaItemIndex ?: C.INDEX_UNSET
                request.entries.getOrNull(previous)?.let { app.library.recordProgress(request.itemUrl, it.key, it.title, 1, 1) }
            }
            errorPanel.visibility = View.GONE
            updateTitle()
            saveProgress()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveProgress(finished = true)
                showHint("Просмотр завершён")
                playerView.postDelayed({ if (!isFinishing) finish() }, 1_500)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
            if (isPlaying) applyFrameRate() else clearFrameRate()
            if (isPlaying) errorPanel.visibility = View.GONE
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = player?.videoFormat
            contentFrameRate = video?.frameRate?.takeIf { it > 0 } ?: 0f
            applyFrameRate()
            if (!recommendationShown) {
                DeviceCaps(this@PlayerActivity).recommendation(video)?.let { suggestion ->
                    recommendationShown = true
                    val dialog = AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Проверка видео")
                        .setMessage(suggestion.message)
                        .setPositiveButton("Продолжить", null)
                    if (suggestion.settingsRelevant) dialog.setNeutralButton("Настройки ТВ") { _, _ -> openDisplaySettings() }
                    if (!isFinishing) dialog.show()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorText.text = describe(error)
            nextButton.visibility = if (player?.hasNextMediaItem() == true) View.VISIBLE else View.GONE
            errorPanel.visibility = View.VISIBLE
            playerView.hideController()
            retryButton.requestFocus()
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            if (request.torrent) "Данные не поступают: у раздачи мало пиров или торрент-движок остановлен. Подождите немного и нажмите «Повторить» или выберите раздачу с большим числом сидов."
            else "Не удалось загрузить видео по ссылке. Проверьте подключение к сети и адрес."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Устройство не может декодировать это видео. Выберите раздачу в другом качестве (например, 1080p вместо 4K или H.264 вместо HEVC)."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "Не удалось вывести звук. Выберите другую звуковую дорожку в настройках плеера (значок шестерёнки)."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "Формат файла не поддерживается или файл повреждён."
        else -> "Не удалось воспроизвести видео (${error.errorCodeName})."
    }

    // ComponentActivity marks this override restricted, but overriding it is the documented way to see keys first.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (errorPanel.isVisible) return super.dispatchKeyEvent(event)
        val exo = player
        // With the controls hidden, left/right jump through the video like on most TV players.
        if (exo != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    exo.seekBack(); showHint("−10 сек"); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    exo.seekForward(); showHint("+10 сек"); return true
                }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun saveProgress(finished: Boolean = false) {
        val exo = player ?: return
        val entry = request.entries.getOrNull(exo.currentMediaItemIndex) ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val position = if (finished && duration > 0) duration else exo.currentPosition
        if (position < 3_000 && !finished) return
        app.library.recordProgress(request.itemUrl, entry.key, entry.title, position, duration)
    }

    private fun updateTitle() {
        val index = player?.currentMediaItemIndex ?: resumeIndex
        val entry = request.entries.getOrNull(index) ?: return
        titleView.text = entry.title
        subtitleView.text = if (request.entries.size > 1) "Файл ${index + 1} из ${request.entries.size}" else ""
        subtitleView.visibility = if (request.entries.size > 1) View.VISIBLE else View.GONE
    }

    private fun updateUpNext() {
        val exo = player ?: return
        val remaining = exo.duration - exo.currentPosition
        val next = request.entries.getOrNull(exo.nextMediaItemIndex)
        val show = settings.autoplayNext && next != null && exo.duration > 0 && remaining in 1..UP_NEXT_MS
        upNextView.text = if (next != null) "Далее: ${next.title}" else ""
        upNextView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showHint(text: String) {
        hintView.text = text
        hintView.visibility = View.VISIBLE
        hideHint?.let(hintView::removeCallbacks)
        hideHint = Runnable { hintView.visibility = View.GONE }.also { hintView.postDelayed(it, 2_500) }
    }

    private fun applyFrameRate() {
        if (!settings.autoFrameRate || Build.VERSION.SDK_INT < 30 || contentFrameRate <= 0) return
        val surface = surfaceView?.holder?.surface?.takeIf { it.isValid } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(contentFrameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else {
                surface.setFrameRate(contentFrameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    private fun clearFrameRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val surface = surfaceView?.holder?.surface?.takeIf { it.isValid } ?: return
        runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    private fun openDisplaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            setShowSubtitleButton(true)
            setShowShuffleButton(false)
            setShowNextButton(request.entries.size > 1)
            setShowPreviousButton(request.entries.size > 1)
            controllerShowTimeoutMs = 4_000
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> topBar.visibility = visibility })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        (playerView.videoSurfaceView as? SurfaceView)?.let { view ->
            surfaceView = view
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = applyFrameRate()
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = applyFrameRate()
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }

        titleView = text(26f, Color.WHITE, bold = true).apply { maxLines = 2 }
        subtitleView = text(16f, MUTED, bold = false)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(48))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            addView(titleView)
            addView(subtitleView)
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        hintView = pill(text(20f, Color.WHITE, bold = true)).apply { visibility = View.GONE }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(32), dp(48), 0) })

        upNextView = pill(text(18f, Color.WHITE, bold = false)).apply { visibility = View.GONE }
        root.addView(upNextView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(120)) })

        errorText = text(20f, Color.WHITE, bold = false).apply { setLineSpacing(0f, 1.2f) }
        retryButton = button("Повторить") {
            errorPanel.visibility = View.GONE
            player?.let { it.prepare(); it.play() }
        }
        nextButton = button("Следующий файл") {
            errorPanel.visibility = View.GONE
            player?.let { it.seekToNextMediaItem(); it.prepare(); it.play() }
        }
        val closeButton = button("Закрыть") { finish() }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(36), dp(32), dp(36), dp(32))
            background = GradientDrawable().apply { setColor(0xF0151B26.toInt()); cornerRadius = dp(24).toFloat() }
            addView(text(28f, Color.WHITE, bold = true).apply { text = "Видео не воспроизводится" })
            addView(errorText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(LinearLayout(this@PlayerActivity).apply {
                setPadding(0, dp(24), 0, 0)
                addView(retryButton)
                addView(nextButton)
                addView(closeButton)
            })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(dp(680), -2, Gravity.CENTER))
        return root
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    }

    private fun pill(view: TextView) = view.apply {
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setShadowLayer(0f, 0f, 0f, 0)
        background = GradientDrawable().apply { setColor(0xD9151B26.toInt()); cornerRadius = dp(24).toFloat() }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        stateListAnimator = null
        setPadding(dp(24), dp(10), dp(24), dp(10))
        val idle = GradientDrawable().apply { setColor(0xFF2A3445.toInt()); cornerRadius = dp(24).toFloat() }
        val focused = GradientDrawable().apply { setColor(0xFFF2F5FA.toInt()); cornerRadius = dp(24).toFloat() }
        fun style(hasFocus: Boolean) {
            background = if (hasFocus) focused else idle
            setTextColor(if (hasFocus) 0xFF0B0F17.toInt() else Color.WHITE)
            scaleX = if (hasFocus) 1.06f else 1f
            scaleY = scaleX
        }
        style(false)
        setOnFocusChangeListener { _, hasFocus -> style(hasFocus) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(52)).apply { marginEnd = dp(14) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val STATE_INDEX = "index"
        private const val STATE_POSITION = "position"
        private const val SEEK_STEP_MS = 10_000L
        private const val UP_NEXT_MS = 20_000L
        private const val MUTED = 0xFFB8C2D3.toInt()

        fun formatTime(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
