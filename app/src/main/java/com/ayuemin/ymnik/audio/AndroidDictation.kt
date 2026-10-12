package com.ayuemin.ymnik.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Android SpeechRecognizer lives on the main thread and is never a model tool.
 * Its transcript enters the composer only after a physical microphone tap.
 * On-device recognition is preferred; Android may not have an offline language pack.
 */
class AndroidDictation(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var onText: ((String) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null

    fun start(onText: (String) -> Unit, onFailure: (String) -> Unit) {
        cancel()
        this.onText = onText
        this.onFailure = onFailure
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("Служба распознавания речи Android отсутствует")
            return
        }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val service = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (error: Exception) {
            fail(error.message ?: "Не удалось запустить распознавание")
            return
        }
        recognizer = service
        active = true
        service.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (active) fail("Ошибка распознавания Android: $error")
            }
            override fun onResults(results: Bundle?) {
                if (!active) return
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                active = false
                recognizer?.destroy()
                recognizer = null
                if (result.isBlank()) onFailure("Речь не распознана") else onText(result)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale("ru", "RU").toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (error: Exception) {
            fail(error.message ?: "Не удалось включить микрофон")
        }
    }

    fun stop() {
        if (active) recognizer?.stopListening()
    }

    fun cancel() {
        active = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        onText = null
        onFailure = null
    }

    fun close() = cancel()

    private fun fail(message: String) {
        val callback = onFailure
        cancel()
        callback?.invoke(message)
    }
}
