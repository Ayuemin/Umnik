package com.ayuemin.ymnik.audio

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Purely offline continuous Russian speech recognition.
 *
 * The actual Apache-2.0 vosk-model-small-ru-0.22 neural acoustic model ships
 * inside APK assets via prepareRussianVoskModel. No network or main chat model.
 */
class VoskOfflineDictation(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var loading: Job? = null
    private var listening = false
    private var sessionId = 0L
    private val confirmedSegments = mutableListOf<String>()
    private var onText: ((String) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null

    fun start(onText: (String) -> Unit, onFailure: (String) -> Unit) {
        cancel()
        val session = ++sessionId
        this.onText = onText
        this.onFailure = onFailure
        confirmedSegments.clear()
        listening = true
        loading = scope.launch {
            try {
                val loadedModel = withContext(Dispatchers.IO) {
                    model ?: Model(ensureModelInstalled().absolutePath)
                }
                if (session != sessionId || !listening) {
                    if (model !== loadedModel) loadedModel.close()
                    return@launch
                }
                model = loadedModel
                val recognizer = withContext(Dispatchers.IO) { Recognizer(loadedModel, 16_000f) }
                if (session != sessionId || !listening) {
                    recognizer.close()
                    return@launch
                }
                this@VoskOfflineDictation.recognizer = recognizer
                val service = SpeechService(recognizer, 16_000f)
                this@VoskOfflineDictation.service = service
                check(service.startListening(object : RecognitionListener {
                    override fun onResult(hypothesis: String) {
                        if (session == sessionId && listening) appendResult(hypothesis)
                    }
                    override fun onPartialResult(hypothesis: String) = Unit
                    override fun onFinalResult(hypothesis: String) {
                        if (session != sessionId) return
                        appendResult(hypothesis)
                        finishSuccess()
                    }
                    override fun onError(exception: Exception) {
                        if (session == sessionId) fail(exception.message ?: "Ошибка локального распознавания")
                    }
                    override fun onTimeout() {
                        if (session == sessionId) finishSuccess()
                    }
                })) { "Не удалось запустить микрофон Vosk" }
            } catch (error: Exception) {
                if (session == sessionId) fail(error.message ?: "Не удалось загрузить русскую модель Vosk")
            }
        }
    }

    fun stop() {
        if (!listening) return
        val activeService = service
        if (activeService == null) {
            // User stopped during model load; do not start recording after loading.
            fail("Распознавание ещё загружалось. Нажмите микрофон снова.")
        } else {
            scope.launch(Dispatchers.IO) {
                activeService.stop() // Emits onFinalResult through Android main handler.
            }
        }
    }

    fun cancel() {
        sessionId++
        listening = false
        loading?.cancel()
        loading = null
        confirmedSegments.clear()
        onText = null
        onFailure = null
        val oldService = service
        val oldRecognizer = recognizer
        service = null
        recognizer = null
        if (oldService != null || oldRecognizer != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { oldService?.cancel() }
                runCatching { oldService?.shutdown() }
                runCatching { oldRecognizer?.close() }
            }
        }
    }

    fun close() {
        cancel()
        val oldModel = model
        model = null
        scope.launch(Dispatchers.IO) { runCatching { oldModel?.close() } }
        scope.cancel()
    }

    private fun appendResult(json: String) {
        val result = runCatching { JSONObject(json).optString("text", "").trim() }.getOrDefault("")
        if (result.isNotEmpty()) confirmedSegments += result
    }

    private fun finishSuccess() {
        val callback = onText
        val text = confirmedSegments.joinToString(" ").trim()
        cancel()
        if (text.isNotEmpty()) callback?.invoke(text)
        else onFailure?.invoke("Речь не распознана") // onFailure is cleared in cancel()
    }

    private fun fail(message: String) {
        val callback = onFailure
        cancel()
        callback?.invoke(message)
    }

    private fun ensureModelInstalled(): File {
        val destination = File(context.filesDir, "speech-models/vosk-small-ru-0.22")
        val ready = File(destination, ".umnik-ready")
        if (ready.isFile && File(destination, "am/final.mdl").isFile) return destination
        val parent = destination.parentFile ?: throw IOException("Нет каталога для локальной модели")
        parent.mkdirs()
        val staging = File(parent, "vosk-small-ru-0.22.tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            fun unpack(assetPath: String, directory: File) {
                val entries = context.assets.list(assetPath) ?: emptyArray()
                if (entries.isEmpty()) {
                    directory.parentFile?.mkdirs()
                    context.assets.open(assetPath).use { input ->
                        directory.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                } else {
                    directory.mkdirs()
                    entries.forEach { child ->
                        require(child != "." && child != ".." && '/' !in child && '\\' !in child)
                        unpack("$assetPath/$child", File(directory, child))
                    }
                }
            }
            unpack("vosk-ru-small", staging)
            check(File(staging, "am/final.mdl").isFile) { "Модель Vosk не включена в APK" }
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Не удалось установить локальную модель" }
            ready.writeText("vosk-model-small-ru-0.22")
            return destination
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }
}
