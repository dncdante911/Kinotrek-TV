package org.trackertv.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.trackertv.player.data.SubtitleSize
import org.trackertv.player.device.DeviceCaps
import org.trackertv.player.net.TorrServerClient
import org.trackertv.player.net.TorrentStatus
import org.trackertv.player.player.PlaybackRequest
import org.trackertv.player.player.PlayerControls
import org.trackertv.player.player.PlayerMenu
import org.trackertv.player.torrent.LocalTorrentService
import org.trackertv.player.ui.sizeLabel
import java.util.Locale

/**
 * Full-screen Media3 player with its own TV controls. The player exists only between onStart and
 * onStop (except a paused torrent, see onStop), so a TV that goes to its home screen frees the
 * hardware decoder; the position is restored when the user returns.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var request: PlaybackRequest
    private lateinit var playerView: PlayerView
    private lateinit var controls: PlayerControls
    private lateinit var menu: PlayerMenu
    private lateinit var hintView: TextView
    private lateinit var upNextView: TextView
    private lateinit var loadingView: TextView
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
    private var holdsEngine = false
    private val releaseHeld = Runnable {
        releasePlayer()
        releaseEngine()
    }

    // Choices made in the player survive the player being recreated after the TV's home screen.
    private var trackParameters: TrackSelectionParameters? = null
    private var speed = 1f
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var sleepAtMs = 0L
    private var sleepAtEnd = false
    private var torrentStatus: TorrentStatus? = null
    private var statusJob: Job? = null
    /** A key whose press we handled; its release must not reach the views. */
    private var swallowUp = KeyEvent.KEYCODE_UNKNOWN

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
        // Back closes the menu page by page, then the controls, then the player.
        onBackPressedDispatcher.addCallback(this) {
            when {
                menu.isOpen -> menu.back()
                controls.isOpen && !errorPanel.isVisible -> controls.hide()
                else -> finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerView.removeCallbacks(releaseHeld)
        if (request.torrent && !holdsEngine) {
            holdsEngine = true
            LocalTorrentService.acquire()
            LocalTorrentService.resume(this)
        }
        initializePlayer()
    }

    override fun onStop() {
        // A paused torrent keeps its stream open (e.g. under the TV screensaver), so TorrServer keeps downloading ahead.
        val paused = player?.let { !it.playWhenReady && (it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING) } == true
        if (request.torrent && settings.pauseBuffer.enabled && paused && !isFinishing) {
            saveProgress()
            playerView.postDelayed(releaseHeld, PAUSE_HOLD_MS)
        } else {
            releasePlayer()
            releaseEngine()
        }
        app.library.flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (::playerView.isInitialized) playerView.removeCallbacks(releaseHeld)
        releasePlayer()
        releaseEngine()
        super.onDestroy()
    }

    private fun releaseEngine() {
        if (!holdsEngine) return
        holdsEngine = false
        LocalTorrentService.release()
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
        trackParameters?.let { exo.trackSelectionParameters = it }
        exo.setPlaybackSpeed(speed)
        exo.pauseAtEndOfMediaItems = sleepAtEnd || !settings.autoplayNext
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
        playerView.resizeMode = resizeMode
        applySubtitleSize()
        updateTitle()
        if (resumePositionMs > 5_000) showHint("Продолжаем с ${formatTime(resumePositionMs)}")
        ticker = lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                delay(TICK_MS)
                ticks++
                if (controls.isOpen) controls.update(exo)
                if (ticks % 2 != 0) continue
                // Once a second.
                updateUpNext()
                updateLoading()
                checkSleepTimer()
                if (ticks % 20 == 0) saveProgress()
                if (ticks % 4 == 0 && (controls.isOpen || exo.playbackState == Player.STATE_BUFFERING)) refreshTorrentStatus()
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
        trackParameters = exo.trackSelectionParameters
        ticker?.cancel()
        statusJob?.cancel()
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
            torrentStatus = null
            updateTitle()
            saveProgress()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveProgress(finished = true)
                showHint("Просмотр завершён")
                playerView.postDelayed({ if (!isFinishing) finish() }, 1_500)
            }
            updateLoading()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                sleepAtEnd = false
                player?.pauseAtEndOfMediaItems = !settings.autoplayNext
                showHint("Таймер сна: пауза")
            }
            player?.let { if (controls.isOpen) controls.update(it) }
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
            updateTrackButtons(tracks)
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
            menu.close()
            controls.hide()
            errorPanel.visibility = View.VISIBLE
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
            "Не удалось вывести звук. Выберите другую звуковую дорожку: кнопка с нотой на панели управления."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "Формат файла не поддерживается или файл повреждён."
        else -> "Не удалось воспроизвести видео (${error.errorCodeName})."
    }

    // ComponentActivity marks this override restricted, but overriding it is the documented way to see keys first.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        if (errorPanel.isVisible || exo == null) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) {
            swallowUp = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (menu.isOpen) return super.dispatchKeyEvent(event)
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> return handled(code) { togglePlay() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> return handled(code) { exo.play() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return handled(code) { exo.pause(); controls.show() }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return handled(code) { seekBy(FAST_STEP_MS) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> return handled(code) { seekBy(-FAST_STEP_MS) }
            KeyEvent.KEYCODE_MEDIA_NEXT -> return handled(code) { playFile(exo.nextMediaItemIndex) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return handled(code) { playFile(exo.previousMediaItemIndex) }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> return handled(code) { openMoreMenu() }
            KeyEvent.KEYCODE_CAPTIONS -> return handled(code) { openSubtitleMenu() }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> return handled(code) { openAudioMenu() }
        }
        if (!controls.isOpen) {
            when (code) {
                // Left/right start moving along the timeline right away, like on most TV players.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return handled(code) {
                    controls.update(exo)
                    controls.show(controls.seekBar)
                    controls.seekBar.onKeyDown(code, event)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return handled(code) { togglePlay() }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return handled(code) { showControls() }
            }
        } else {
            controls.ping()
        }
        return super.dispatchKeyEvent(event)
    }

    private inline fun handled(code: Int, action: () -> Unit): Boolean {
        swallowUp = code
        action()
        return true
    }

    private fun showControls(focus: View? = null) {
        player?.let(controls::update)
        controls.show(focus ?: controls.playPause)
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.playWhenReady && exo.playbackState != Player.STATE_ENDED) {
            exo.pause()
            showControls()
        } else {
            exo.play()
            if (controls.isOpen) controls.update(exo)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0, duration))
        showHint(if (deltaMs < 0) "−${-deltaMs / 1000} сек" else "+${deltaMs / 1000} сек")
        if (controls.isOpen) controls.update(exo)
    }

    /** Switches to another file of the release, resuming where it was left. */
    private fun playFile(index: Int) {
        val exo = player ?: return
        val entry = request.entries.getOrNull(index) ?: return
        saveProgress()
        val progress = app.library.snapshot.value.progress[entry.key]?.takeUnless { it.finished }
        exo.seekTo(index, progress?.positionMs ?: 0)
        exo.play()
        progress?.takeIf { it.positionMs > 5_000 }?.let { showHint("Продолжаем с ${formatTime(it.positionMs)}") }
    }

    // ---- Tracks ----

    private fun trackGroups(type: Int) = player?.currentTracks?.groups.orEmpty().filter { it.type == type }

    private fun updateTrackButtons(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val selectedAudio = audio.firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
        controls.audio.setText(selectedAudio?.let { trackLabel(it, C.TRACK_TYPE_AUDIO, short = true) } ?: if (audio.isEmpty()) "Нет звука" else "Звук")
        controls.audio.visibility = if (audio.isEmpty()) View.GONE else View.VISIBLE

        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val selectedText = text.firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
        controls.subtitles.setText(
            when {
                text.isEmpty() -> "Нет"
                selectedText == null -> "Выкл"
                else -> trackLabel(selectedText, C.TRACK_TYPE_TEXT, short = true)
            },
        )
    }

    private fun openAudioMenu() {
        val exo = player ?: return
        val items = trackGroups(C.TRACK_TYPE_AUDIO).flatMap { group ->
            (0 until group.length).map { i ->
                val format = group.getTrackFormat(i)
                val supported = group.isTrackSupported(i)
                PlayerMenu.Item(
                    label = trackLabel(format, C.TRACK_TYPE_AUDIO),
                    detail = if (supported) audioDetails(format) else "Не поддерживается устройством",
                    checked = group.isTrackSelected(i),
                    enabled = supported,
                ) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setPreferredAudioLanguage(format.language)
                        .build()
                    menu.close()
                }
            }
        }
        menu.open("Звуковая дорожка", items.ifEmpty { listOf(PlayerMenu.Item("В файле нет звуковых дорожек")) })
    }

    private fun openSubtitleMenu() {
        val exo = player ?: return
        val groups = trackGroups(C.TRACK_TYPE_TEXT)
        val anySelected = groups.any { group -> (0 until group.length).any(group::isTrackSelected) }
        val off = PlayerMenu.Item("Выключены", checked = !anySelected) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            menu.close()
        }
        val tracks = groups.flatMap { group ->
            (0 until group.length).map { i ->
                val format = group.getTrackFormat(i)
                val supported = group.isTrackSupported(i)
                PlayerMenu.Item(
                    label = trackLabel(format, C.TRACK_TYPE_TEXT),
                    detail = if (supported) null else "Формат не поддерживается",
                    checked = group.isTrackSelected(i),
                    enabled = supported,
                ) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setPreferredTextLanguage(format.language)
                        .build()
                    menu.close()
                }
            }
        }
        val size = PlayerMenu.Item("Размер субтитров", settings.subtitleSize.label) { openSubtitleSizeMenu() }
        menu.open("Субтитры", if (tracks.isEmpty()) listOf(PlayerMenu.Item("В файле нет субтитров")) else listOf(off) + tracks + size)
    }

    private fun openSubtitleSizeMenu() {
        menu.push("Размер субтитров", SubtitleSize.entries.map { size ->
            PlayerMenu.Item(size.label, checked = size == settings.subtitleSize) {
                app.settings.setSubtitleSize(size)
                applySubtitleSize()
                menu.back()
            }
        })
    }

    private fun applySubtitleSize() {
        playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleSize.scale)
    }

    /** "Русский · Дубляж · 5.1 · AC3" for menus, "Русский 5.1" for the button. */
    private fun trackLabel(format: Format, type: Int, short: Boolean = false): String {
        val language = format.language?.takeUnless { it.isBlank() || it == "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(RUSSIAN).replaceFirstChar { c -> c.titlecase(RUSSIAN) } }
            ?.takeIf { it.isNotBlank() }
        val name = format.label?.takeIf { it.isNotBlank() && (language == null || !it.contains(language, ignoreCase = true)) }
        val channels = if (type == C.TRACK_TYPE_AUDIO) channelsLabel(format.channelCount) else null
        if (short) return listOfNotNull(language ?: name, channels).joinToString(" ").ifEmpty { "Дорожка" }
        val forced = if (type == C.TRACK_TYPE_TEXT && (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "форсированные" else null
        return listOfNotNull(language, name, forced, channels, codecLabel(format.sampleMimeType)).joinToString(" · ").ifEmpty { "Дорожка" }
    }

    private fun audioDetails(format: Format): String? =
        format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} кбит/с" }

    private fun channelsLabel(count: Int) = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "Моно"
        2 -> "Стерео"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count кан."
    }

    private fun codecLabel(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }

    // ---- "More" menu ----

    private fun openMoreMenu() {
        val exo = player ?: return
        val items = buildList {
            add(PlayerMenu.Item("Скорость воспроизведения", speedLabel(speed)) { openSpeedMenu() })
            add(PlayerMenu.Item("Формат изображения", RESIZE_MODES.first { it.first == resizeMode }.second) { openResizeMenu() })
            add(PlayerMenu.Item("Таймер сна", sleepLabel()) { openSleepMenu() })
            if (request.entries.size > 1) {
                add(PlayerMenu.Item("Список файлов", "Файл ${exo.currentMediaItemIndex + 1} из ${request.entries.size}") { openFilesMenu() })
            }
            add(PlayerMenu.Item("Начать сначала") {
                exo.seekTo(0)
                exo.play()
                menu.close()
            })
            add(PlayerMenu.Item("Информация о видео") { openInfo() })
        }
        menu.open("Ещё", items)
    }

    private fun openSpeedMenu() {
        menu.push("Скорость воспроизведения", SPEEDS.map { value ->
            PlayerMenu.Item(speedLabel(value), checked = value == speed) {
                speed = value
                player?.setPlaybackSpeed(value)
                menu.close()
                if (value != 1f) showHint("Скорость ${speedLabel(value)}")
            }
        })
    }

    private fun openResizeMenu() {
        menu.push("Формат изображения", RESIZE_MODES.map { (mode, label, detail) ->
            PlayerMenu.Item(label, detail, checked = mode == resizeMode) {
                resizeMode = mode
                playerView.resizeMode = mode
                menu.close()
            }
        })
    }

    private fun openSleepMenu() {
        val off = PlayerMenu.Item("Выключен", checked = sleepAtMs == 0L && !sleepAtEnd) { setSleep(0, atEnd = false) }
        val minutes = SLEEP_MINUTES.map { m -> PlayerMenu.Item("Через $m мин") { setSleep(m * 60_000L, atEnd = false) } }
        val end = PlayerMenu.Item("В конце файла", checked = sleepAtEnd) { setSleep(0, atEnd = true) }
        menu.push("Таймер сна", listOf(off) + minutes + end)
    }

    private fun setSleep(afterMs: Long, atEnd: Boolean) {
        sleepAtMs = if (afterMs > 0) SystemClock.elapsedRealtime() + afterMs else 0
        sleepAtEnd = atEnd
        player?.pauseAtEndOfMediaItems = atEnd || !settings.autoplayNext
        menu.close()
        showHint(
            when {
                atEnd -> "Пауза в конце файла"
                afterMs > 0 -> "Пауза через ${afterMs / 60_000} мин"
                else -> "Таймер сна выключен"
            },
        )
    }

    private fun checkSleepTimer() {
        if (sleepAtMs == 0L || SystemClock.elapsedRealtime() < sleepAtMs) return
        sleepAtMs = 0
        player?.pause()
        showHint("Таймер сна: пауза")
    }

    private fun sleepLabel(): String = when {
        sleepAtEnd -> "В конце файла"
        sleepAtMs > 0 -> "Осталось ${((sleepAtMs - SystemClock.elapsedRealtime()) / 60_000 + 1).coerceAtLeast(1)} мин"
        else -> "Выключен"
    }

    private fun openFilesMenu() {
        val exo = player ?: return
        val progress = app.library.snapshot.value.progress
        menu.push("Список файлов", request.entries.mapIndexed { index, entry ->
            val saved = progress[entry.key]
            val detail = when {
                saved == null -> null
                saved.finished -> "Просмотрено"
                saved.positionMs > 5_000 -> "Остановились на ${formatTime(saved.positionMs)}"
                else -> null
            }
            PlayerMenu.Item(entry.title, detail, checked = index == exo.currentMediaItemIndex) {
                menu.close()
                if (index != exo.currentMediaItemIndex) playFile(index)
            }
        })
    }

    private fun openInfo() {
        val exo = player ?: return
        val rows = mutableListOf<PlayerMenu.Item>()
        exo.videoFormat?.let { video ->
            val parts = listOfNotNull(
                if (video.width > 0) "${video.width}×${video.height}" else null,
                codecLabel(video.sampleMimeType),
                hdrLabel(video),
                video.frameRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.3f к/с", it).replace(".000", "") },
                video.bitrate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f Мбит/с", it / 1_000_000f) },
            )
            rows += PlayerMenu.Item("Видео", parts.joinToString(" · "))
        }
        exo.audioFormat?.let { rows += PlayerMenu.Item("Звук", trackLabel(it, C.TRACK_TYPE_AUDIO)) }
        val ahead = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0) / 1000
        rows += PlayerMenu.Item("Буфер плеера", "$ahead сек вперёд")
        torrentStatus?.let { rows += PlayerMenu.Item("Торрент", statusLine(it)) }
        request.entries.getOrNull(exo.currentMediaItemIndex)?.let { rows += PlayerMenu.Item("Файл", it.title) }
        menu.push("Информация о видео", rows)
    }

    private fun hdrLabel(format: Format): String? = when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) null else "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

    private fun speedLabel(value: Float) = (if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()).replace('.', ',') + "×"

    // ---- Torrent status ----

    /** TorrServer address and hash parsed from the stream URL of the current file. */
    private fun currentTorrent(): Pair<TorrServerClient, String>? {
        val exo = player ?: return null
        if (!request.torrent) return null
        val uri = request.entries.getOrNull(exo.currentMediaItemIndex)?.url?.toUri() ?: return null
        val hash = uri.getQueryParameter("link") ?: return null
        val client = runCatching { TorrServerClient("${uri.scheme}://${uri.encodedAuthority}") }.getOrNull() ?: return null
        return client to hash
    }

    private fun refreshTorrentStatus() {
        if (statusJob?.isActive == true) return
        val (client, hash) = currentTorrent() ?: return
        statusJob = lifecycleScope.launch {
            torrentStatus = try {
                client.status(hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            controls.status.text = torrentStatus?.let(::statusLine).orEmpty()
            updateLoading()
        }
    }

    private fun statusLine(status: TorrentStatus): String = buildString {
        append("↓ ${sizeLabel(status.speedBytes)}/с")
        if (status.uploadBytes > 0) append(" · ↑ ${sizeLabel(status.uploadBytes)}/с")
        append(" · пиров ${status.peers}")
        if (status.seeders > 0) append(" (сидов ${status.seeders})")
    }

    /** While a torrent buffers with the controls hidden, show that data is coming and how fast. */
    private fun updateLoading() {
        val exo = player
        val buffering = exo?.playbackState == Player.STATE_BUFFERING && request.torrent && !controls.isOpen
        loadingView.text = torrentStatus?.let { "Загрузка · ${statusLine(it)}" } ?: "Загрузка…"
        loadingView.visibility = if (buffering) View.VISIBLE else View.GONE
    }

    // ---- Misc ----

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
        val multiple = request.entries.size > 1
        controls.title.text = entry.title
        controls.subtitle.text = if (multiple) "Файл ${index + 1} из ${request.entries.size}" else ""
        controls.subtitle.visibility = if (multiple) View.VISIBLE else View.GONE
        controls.setPlaylist(hasPrevious = index > 0, hasNext = index < request.entries.lastIndex, multiple = multiple)
    }

    private fun updateUpNext() {
        val exo = player ?: return
        val remaining = exo.duration - exo.currentPosition
        val next = request.entries.getOrNull(exo.nextMediaItemIndex)
        val show = settings.autoplayNext && !sleepAtEnd && next != null && exo.duration > 0 && remaining in 1..UP_NEXT_MS
        upNextView.text = if (next != null) "Далее: ${next.title}" else ""
        upNextView.visibility = if (show) View.VISIBLE else View.GONE
        // Above the bottom panel while it is open.
        upNextView.translationY = if (controls.isOpen) -dp(150).toFloat() else 0f
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
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
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

        controls = PlayerControls(this).apply {
            keepVisible = { player?.let { !it.playWhenReady } == true || seekBar.isScrubbing || menu.isOpen }
            playPause.setOnClickListener { togglePlay() }
            seekBar.setOnClickListener { togglePlay() }
            seekBar.onSeek = { position -> player?.seekTo(position); player?.let(::update) }
            rewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
            forward.setOnClickListener { seekBy(SEEK_STEP_MS) }
            previous.setOnClickListener { player?.let { playFile(it.previousMediaItemIndex) } }
            next.setOnClickListener { player?.let { playFile(it.nextMediaItemIndex) } }
            audio.setOnClickListener { openAudioMenu() }
            subtitles.setOnClickListener { openSubtitleMenu() }
            more.setOnClickListener { openMoreMenu() }
        }
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        hintView = pill(text(20f, Color.WHITE, bold = true)).apply { visibility = View.GONE }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(32) })

        upNextView = pill(text(18f, Color.WHITE, bold = false)).apply { visibility = View.GONE }
        root.addView(upNextView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(48)) })

        loadingView = pill(text(17f, Color.WHITE, bold = false)).apply { visibility = View.GONE }
        root.addView(loadingView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(48)) })

        menu = PlayerMenu(this).apply { onClosed = { controls.ping() } }
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))

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
        private const val FAST_STEP_MS = 30_000L
        private const val TICK_MS = 500L
        /** A paused torrent left in the background stops downloading after this long. */
        private const val PAUSE_HOLD_MS = 2 * 60 * 60_000L
        private const val UP_NEXT_MS = 20_000L
        private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val SLEEP_MINUTES = listOf(15L, 30L, 60L, 90L)
        private val RESIZE_MODES = listOf(
            Triple(AspectRatioFrameLayout.RESIZE_MODE_FIT, "По размеру экрана", "Весь кадр, возможны чёрные полосы"),
            Triple(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Заполнить экран", "Без полос, края кадра обрезаются"),
            Triple(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Растянуть", "Без полос и обрезки, пропорции искажаются"),
        )

        fun formatTime(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
