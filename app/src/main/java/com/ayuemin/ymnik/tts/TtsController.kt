package com.ayuemin.ymnik.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.min

class TtsController(context: Context) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    var speakingMessageId by mutableStateOf<String?>(null)
        private set

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            pending = null
            return
        }

        ready = true
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.endsWith("|last") == true) {
                    mainHandler.post { speakingMessageId = null }
                }
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                mainHandler.post { speakingMessageId = null }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { speakingMessageId = null }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post { speakingMessageId = null }
            }
        })

        pending?.also {
            pending = null
            speakInternal(it.first, it.second)
        }
    }

    fun toggle(messageId: String, text: String) {
        if (speakingMessageId == messageId) {
            stop()
            return
        }
        speak(messageId, text)
    }

    fun speak(messageId: String, text: String) {
        val clean = prepareForSpeech(text)
        if (clean.isBlank()) return

        if (!ready) {
            pending = messageId to clean
            speakingMessageId = messageId
            return
        }
        speakInternal(messageId, clean)
    }

    fun stop() {
        pending = null
        engine?.stop()
        speakingMessageId = null
    }

    fun shutdown() {
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        speakingMessageId = null
    }

    private fun speakInternal(messageId: String, text: String) {
        val tts = engine ?: return
        tts.stop()

        val preferredLocale = if (looksRussian(text)) Locale("ru", "RU") else Locale.getDefault()
        val languageStatus = tts.isLanguageAvailable(preferredLocale)
        if (languageStatus >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = preferredLocale
        }

        val maxChunk = min(TextToSpeech.getMaxSpeechInputLength() - 100, 3500).coerceAtLeast(500)
        val chunks = splitIntoChunks(text, maxChunk)
        if (chunks.isEmpty()) return

        speakingMessageId = messageId
        chunks.forEachIndexed { index, chunk ->
            val isLast = index == chunks.lastIndex
            val utteranceId = if (isLast) "$messageId|last" else "$messageId|$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts.speak(chunk, queueMode, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                speakingMessageId = null
                return
            }
        }
    }

    private fun splitIntoChunks(text: String, maxLength: Int): List<String> {
        var remaining = text.trim()
        val result = mutableListOf<String>()

        while (remaining.length > maxLength) {
            var cut = remaining.lastIndexOfAny(
                charArrayOf('.', '!', '?', '\n'),
                startIndex = maxLength,
            )
            if (cut < maxLength / 2) cut = remaining.lastIndexOf(' ', maxLength)
            if (cut < maxLength / 2) cut = maxLength

            result += remaining.substring(0, cut + 1).trim()
            remaining = remaining.substring(cut + 1).trimStart()
        }
        if (remaining.isNotBlank()) result += remaining
        return result.filter { it.isNotBlank() }
    }

    private fun prepareForSpeech(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " Фрагмент кода пропущен. ")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), " $1 ")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), " ссылка ")
        .replace(Regex("[`*_#>|~]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun looksRussian(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        val cyrillic = text.count { it in '\u0400'..'\u04FF' }
        return cyrillic * 3 >= letters
    }
}
