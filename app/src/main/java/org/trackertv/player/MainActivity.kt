package org.trackertv.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.trackertv.player.device.DeviceCaps
import org.trackertv.player.ui.AppRoot
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.DialogAction
import org.trackertv.player.ui.components.DialogSpec
import org.trackertv.player.ui.search.SearchViewModel
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.TrackerTheme
import org.trackertv.player.web.RutrackerActivity
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val search: SearchViewModel by viewModels()

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::onFilePicked) }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::onVoice)
    }
    private val voiceInput by lazy {
        VoiceInput(
            context = this,
            onState = { vm.voice = it },
            onResult = ::onVoice,
            onUnavailable = ::openRecognitionScreen,
            onError = vm::toast,
        )
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceInput.start(voiceLanguage()) else openRecognitionScreen("нет доступа к микрофону")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Xiaomi and many other TVs have the speech service but no recognition screen for other apps.
        val voiceAvailable = SpeechRecognizer.isRecognitionAvailable(this) ||
            packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
        setContent { TrackerTheme { AppRoot(vm, search, voiceAvailable) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.uiEvents.collect(::handle) }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Magnets and video links opened from other apps, or sent back by the RuTracker screen. */
    private fun handleIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme == "content") {
            runCatching { contentResolver.takePersistableUriPermission(data, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        vm.openLink(data.toString(), intent.getStringExtra(EXTRA_TITLE))
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun handle(event: UiEvent) {
        when (event) {
            is UiEvent.Play -> startActivity(event.request.toIntent(this))
            UiEvent.OpenFile -> try {
                openFile.launch(arrayOf("video/*"))
            } catch (e: ActivityNotFoundException) {
                vm.toast("На этом устройстве нет выбора файлов. Используйте файловый менеджер и «Открыть с помощью».")
            }
            UiEvent.OpenRutracker -> startActivity(Intent(this, RutrackerActivity::class.java))
            UiEvent.VoiceSearch -> startVoice()
            UiEvent.CancelVoice -> voiceInput.cancel()
            UiEvent.AllowInstalls -> runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }
            }
            UiEvent.OpenDisplaySettings -> openDisplaySettings()
            UiEvent.ShowDeviceCaps -> vm.showDialog(DialogSpec.Info(
                title = "Возможности ТВ",
                rows = DeviceCaps(this).report().map { Triple(it.label, it.value, it.supported) },
                actions = listOf(DialogAction("Настройки экрана", Icons.Tv) { openDisplaySettings() }),
            ))
        }
    }

    private fun startVoice() {
        when {
            !voiceInput.available() -> openRecognitionScreen("служба распознавания не найдена")
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            else -> voiceInput.start(voiceLanguage())
        }
    }

    /** Titles are mostly Russian; a TV set to Ukrainian or English keeps its own language. */
    private fun voiceLanguage(): String = Locale.getDefault().toLanguageTag().takeIf { it.length >= 2 } ?: "ru-RU"

    /** The system recognition screen; used when the service cannot be reached directly. */
    private fun openRecognitionScreen(reason: String) {
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Назовите фильм или сериал"))
        } catch (e: RuntimeException) {
            vm.showDialog(DialogSpec.Actions(
                title = "Голосовой ввод недоступен",
                message = "Телевизор не дал приложению распознавание речи ($reason). Проверьте, что включены «Google» / «Голосовой поиск» " +
                    "в настройках ТВ и что приложению Google разрешён микрофон. Поиск с клавиатуры работает как обычно.",
                actions = emptyList(),
            ))
        }
    }

    private fun onVoice(text: String) {
        search.query = text
        search.submit(text)
        vm.open(Screen.Search)
    }

    override fun onStop() {
        voiceInput.cancel()
        super.onStop()
    }

    private fun onFilePicked(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Видео"
        vm.addFile(uri, name)
    }

    private fun openDisplaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.onFailure { vm.toast("Настройки устройства недоступны") }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
