package org.trackertv.player

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.trackertv.player.data.AppSettings
import org.trackertv.player.data.LibraryRepository
import org.trackertv.player.data.Updater
import org.trackertv.player.net.SourceSearchClient
import org.trackertv.player.ui.components.Images

/** Owns the process-wide singletons; everything else gets them through [Context.app]. */
class TrackerApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { AppSettings(this) }
    val library by lazy { LibraryRepository(this, scope) }
    val sources by lazy { SourceSearchClient(this) }
    val updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        library // start reading the library from disk before the first screen needs it
        scope.launch(Dispatchers.IO) { Images.trim(this@TrackerApp) }
    }
}

val Context.app: TrackerApp get() = applicationContext as TrackerApp
