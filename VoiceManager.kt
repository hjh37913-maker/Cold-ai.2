package com.coldai.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Распознавание речи и озвучивание. Микрофон слушается только после нажатия кнопки. */
class VoiceManager(private val app: Context) {
    private var tts: TextToSpeech? = null
    val ttsReady = MutableStateFlow(false)
    private var rec: SpeechRecognizer? = null

    init {
        tts = TextToSpeech(app.applicationContext) { ttsReady.value = it == TextToSpeech.SUCCESS }
    }

    fun voices(lang: String): List<Voice> = try {
        tts?.voices?.filter { it.locale.language == lang }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun speak(text: String, lang: String, rate: Float, pitch: Float, voice: String, done: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady.value) { done(); return }
        t.language = Locale(lang)
        if (voice.isNotEmpty()) {
            try { t.voices?.firstOrNull { it.name == voice && it.locale.language == lang }?.let { t.voice = it } } catch (e: Exception) { }
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done() }
        })
        t.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "cold")
    }

    fun stopSpeaking() { try { tts?.stop() } catch (e: Exception) { } }

    fun listen(lang: String, onPart: (String) -> Unit, onRes: (String) -> Unit, onErr: (Int) -> Unit) {
        cancelListening()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { onErr(-1); return }
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { onErr(error) }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) onErr(SpeechRecognizer.ERROR_NO_MATCH) else onRes(t)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPart)
            }
        })
        val tag = if (lang == "uk") "uk-UA" else "ru-RU"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    /** Завершить запись и получить результат. */
    fun finishListening() { try { rec?.stopListening() } catch (e: Exception) { } }

    fun cancelListening() {
        try { rec?.cancel(); rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    fun release() {
        cancelListening()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
    }
}
