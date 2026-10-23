package org.trackertv.player.torrent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.trackertv.player.MainActivity
import xyz.secozzi.torrserver.TorrServer
import java.util.concurrent.Executors

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data class Running(val port: Int) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Runs the bundled TorrServer while something needs it. Screens and the player [acquire] it;
 * once nobody holds it for [IDLE_STOP_MS] the service stops itself, so the engine never keeps
 * using the network in the background (notifications are almost invisible on TVs).
 */
class LocalTorrentService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { if (users == 0) stopSelf() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Торрент-поток", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The typed overload exists from Android 10; on Android 9 the manifest type is enough.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Запуск торрент-движка…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (mutableState.value !is EngineState.Running && mutableState.value != EngineState.Starting) {
            mutableState.value = EngineState.Starting
            worker.execute {
                try {
                    val port = TorrServer.startServer(PORT, filesDir.absolutePath, "tracker", "")
                    check(port > 0) { "TorrServer не запустился (код $port)" }
                    mutableState.value = EngineState.Running(port)
                    if (canNotify()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Торрент-поток готов"))
                } catch (e: Throwable) {
                    Log.e(TAG, "TorrServer start failed", e)
                    mutableState.value = EngineState.Failed(e.message ?: e.javaClass.simpleName)
                    handler.post { stopSelf() }
                }
            }
        }
        usersChanged()
        return START_NOT_STICKY
    }

    /** Android 13+ hides updates without the permission; TVs rarely grant it and the service works anyway. */
    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun usersChanged() {
        handler.removeCallbacks(idleStop)
        if (users == 0) handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocalTorrentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(org.trackertv.player.R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        instance = null
        // stopServer() may block on network teardown; never do it on the main thread.
        worker.execute {
            runCatching { TorrServer.stopServer() }
            if (mutableState.value is EngineState.Running || mutableState.value == EngineState.Starting) mutableState.value = EngineState.Stopped
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocalTorrentService"
        private const val CHANNEL = "tracker_torrent_playback"
        private const val NOTIFICATION_ID = 91
        private const val PORT = "8090"
        private const val ACTION_STOP = "org.trackertv.player.STOP_TORRENT"
        private const val IDLE_STOP_MS = 5 * 60_000L

        private val mutableState = MutableStateFlow<EngineState>(EngineState.Stopped)
        val state: StateFlow<EngineState> = mutableState.asStateFlow()

        private var instance: LocalTorrentService? = null
        private var users = 0

        fun start(context: Context) {
            if (mutableState.value is EngineState.Failed) mutableState.value = EngineState.Stopped
            context.startForegroundService(Intent(context, LocalTorrentService::class.java))
        }

        fun stop(context: Context) {
            if (instance != null) context.startService(Intent(context, LocalTorrentService::class.java).setAction(ACTION_STOP))
        }

        /** Call on the main thread while a screen or the player depends on the engine. */
        fun acquire() {
            users++
            instance?.usersChanged()
        }

        fun release() {
            users = (users - 1).coerceAtLeast(0)
            instance?.usersChanged()
        }

        /** The service died without onDestroy (process trimmed): forget the stale port. */
        internal fun markLost() {
            if (instance == null) mutableState.value = EngineState.Stopped
        }
    }
}
