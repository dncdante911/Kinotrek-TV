package org.trackertv.player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

enum class AudioLanguage(val code: String, val label: String) {
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "Английский"),
    ORIGINAL("", "Как в файле");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: RUSSIAN
    }
}

/** How far the built-in engine downloads ahead, kept on disk, so a pause on slow internet fills up the buffer. */
enum class PauseBuffer(val bytes: Long, val label: String) {
    OFF(0, "Выключено"),
    SMALL(1L shl 30, "1 ГБ"),
    MEDIUM(2L shl 30, "2 ГБ"),
    LARGE(4L shl 30, "4 ГБ");

    val enabled: Boolean get() = this != OFF
}

data class SettingsState(
    /** Empty means the TorrServer bundled with the app. */
    val server: String = "",
    val autoFrameRate: Boolean = true,
    val audioLanguage: AudioLanguage = AudioLanguage.RUSSIAN,
    val autoplayNext: Boolean = true,
    val pauseBuffer: PauseBuffer = PauseBuffer.MEDIUM,
    val history: List<String> = emptyList(),
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val watchedMinutes: Int = 0,
    /** Watched minutes when the support note was last shown; it returns every [Support.REMINDER_AFTER_MINUTES]. */
    val supportShownAtMinutes: Int = 0,
    val decor: DecorMode = DecorMode.AUTO,
)

/** User preferences; the "settings" file and its keys are shared with versions before 1.0. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()
    val current: SettingsState get() = mutableState.value

    fun setServer(address: String) = change { it.copy(server = address.trim()) }
    fun setAutoFrameRate(enabled: Boolean) = change { it.copy(autoFrameRate = enabled) }
    fun setAudioLanguage(language: AudioLanguage) = change { it.copy(audioLanguage = language) }
    fun setAutoplayNext(enabled: Boolean) = change { it.copy(autoplayNext = enabled) }
    fun setPauseBuffer(buffer: PauseBuffer) = change { it.copy(pauseBuffer = buffer) }
    fun clearHistory() = change { it.copy(history = emptyList()) }
    fun setAutoUpdate(enabled: Boolean) = change { it.copy(autoUpdate = enabled) }
    fun setLastUpdateCheck(time: Long) = change { it.copy(lastUpdateCheck = time) }
    fun addWatchedMinute() = change { it.copy(watchedMinutes = it.watchedMinutes + 1) }
    fun setDecor(mode: DecorMode) = change { it.copy(decor = mode) }
    fun markSupportReminderShown() = change { it.copy(supportShownAtMinutes = it.watchedMinutes) }

    fun remember(query: String) {
        val value = query.trim()
        if (value.length < 2) return
        change { state -> state.copy(history = (listOf(value) + state.history.filterNot { it.equals(value, ignoreCase = true) }).take(MAX_HISTORY)) }
    }

    private fun change(transform: (SettingsState) -> SettingsState) {
        mutableState.update(transform)
        write(mutableState.value)
    }

    private fun read(): SettingsState {
        val history = runCatching {
            val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        return SettingsState(
            server = prefs.getString(KEY_SERVER, "").orEmpty(),
            autoFrameRate = prefs.getBoolean(KEY_AUTO_FPS, true),
            audioLanguage = AudioLanguage.of(prefs.getString(KEY_AUDIO, AudioLanguage.RUSSIAN.code)),
            autoplayNext = prefs.getBoolean(KEY_AUTOPLAY, true),
            pauseBuffer = PauseBuffer.entries.firstOrNull { it.name == prefs.getString("pause_buffer", null) } ?: PauseBuffer.MEDIUM,
            history = history,
            autoUpdate = prefs.getBoolean("auto_update", true),
            lastUpdateCheck = prefs.getLong("last_update_check", 0),
            watchedMinutes = prefs.getInt("watched_minutes", 0),
            supportShownAtMinutes = prefs.getInt("support_shown_at", 0),
            decor = DecorMode.entries.firstOrNull { it.name == prefs.getString("decor", null) } ?: DecorMode.AUTO,
        )
    }

    private fun write(state: SettingsState) = prefs.edit {
        putString(KEY_SERVER, state.server)
        putBoolean(KEY_AUTO_FPS, state.autoFrameRate)
        putString(KEY_AUDIO, state.audioLanguage.code)
        putBoolean(KEY_AUTOPLAY, state.autoplayNext)
        putString("pause_buffer", state.pauseBuffer.name)
        putString(KEY_HISTORY, JSONArray(state.history).toString())
        putBoolean("auto_update", state.autoUpdate)
        putLong("last_update_check", state.lastUpdateCheck)
        putInt("watched_minutes", state.watchedMinutes)
        putInt("support_shown_at", state.supportShownAtMinutes)
        putString("decor", state.decor.name)
    }

    private companion object {
        const val MAX_HISTORY = 12
        const val KEY_SERVER = "server"
        const val KEY_AUTO_FPS = "auto_fps"
        const val KEY_AUDIO = "audio_language"
        const val KEY_AUTOPLAY = "autoplay_next"
        const val KEY_HISTORY = "search_history"
    }
}
