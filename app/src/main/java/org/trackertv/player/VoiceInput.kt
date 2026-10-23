package org.trackertv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** What the voice overlay shows. */
sealed interface VoiceState {
    data object Off : VoiceState
    data class Listening(val heard: String, val level: Float) : VoiceState
    data class Thinking(val heard: String) : VoiceState
}

/**
 * Listens through the TV's speech recognition service directly. Many Android TV builds have the
 * service (the Assistant uses it) but no recognition screen for other apps, so the old
 * RECOGNIZE_SPEECH activity alone was not enough; the activity stays as a fallback.
 */
class VoiceInput(
    private val context: Context,
    private val onState: (VoiceState) -> Unit,
    private val onResult: (String) -> Unit,
    /** Unrecoverable for this path: the caller may fall back to the recognition screen. */
    private val onUnavailable: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String) {
        stop()
        val speech = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return onUnavailable("служба распознавания не запустилась")
        recognizer = speech
        var heard = ""
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(VoiceState.Listening("", 0f))
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onState(VoiceState.Listening(heard, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onState(VoiceState.Thinking(heard))
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    heard = it
                    onState(VoiceState.Listening(it, 0.5f))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: heard
                stop()
                onState(VoiceState.Off)
                if (text.isBlank()) onError("Не расслышал. Попробуйте ещё раз ближе к микрофону пульта.") else onResult(text)
            }

            override fun onError(error: Int) {
                stop()
                onState(VoiceState.Off)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        onError("Не расслышал. Нажмите «Голос» и говорите сразу после сигнала.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        onError("Распознаванию речи нужен интернет. Проверьте подключение ТВ к сети.")
                    SpeechRecognizer.ERROR_AUDIO ->
                        onError("Не удалось получить звук с микрофона. Проверьте, что пульт с микрофоном подключён.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        onError("Микрофон занят Ассистентом. Подождите пару секунд и попробуйте снова.")
                    else -> onUnavailable("код ошибки $error")
                }
            }
        })
        runCatching {
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName))
        }.onFailure {
            stop()
            onUnavailable(it.message ?: "не удалось начать")
        }
    }

    fun cancel() {
        stop()
        onState(VoiceState.Off)
    }

    private fun stop() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }
}
