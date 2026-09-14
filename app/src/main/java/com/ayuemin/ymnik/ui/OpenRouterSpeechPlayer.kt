package com.ayuemin.ymnik.ui

import android.content.Context
import android.media.MediaPlayer
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.network.OpenRouterAudioClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OpenRouterSpeechPhase {
    IDLE,
    PREPARING,
    PLAYING
}

data class OpenRouterSpeechPlaybackState(
    val messageId: String? = null,
    val phase: OpenRouterSpeechPhase = OpenRouterSpeechPhase.IDLE,
    val error: String? = null
)

/**
 * One-tap neural speech for an assistant message.
 *
 * Long answers are synthesized in short fragments. The first fragment starts
 * playing as soon as it is ready while the following fragment is prepared in
 * parallel. Audio only lives in app cache for the current playback session.
 */
class OpenRouterSpeechPlayer(
    context: Context,
    private val viewModel: ChatViewModel
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secrets = SecretStore(appContext)
    private val featurePrefs = OpenRouterFeaturePrefs(appContext)
    private val audioClient = OpenRouterAudioClient(appContext)
    private val cacheDir = File(appContext.cacheDir, "openrouter_speech_playback").apply {
        mkdirs()
        listFiles()?.forEach { runCatching { it.delete() } }
    }

    private val mutableState = MutableStateFlow(OpenRouterSpeechPlaybackState())
    val state: StateFlow<OpenRouterSpeechPlaybackState> = mutableState.asStateFlow()

    private var generation = 0L
    private var playbackJob: Job? = null
    private var player: MediaPlayer? = null
    private val tempFiles = linkedSetOf<File>()

    fun toggle(messageId: String, textRaw: String) {
        val text = textRaw.trim()
        if (messageId.isBlank() || text.isBlank()) return

        val current = mutableState.value
        if (current.messageId == messageId && current.phase != OpenRouterSpeechPhase.IDLE) {
            stop()
            return
        }

        generation += 1L
        cancelActiveSession(resetState = false)
        val requestGeneration = generation

        val appState = viewModel.state.value
        val profile = appState.connectionProfiles.firstOrNull {
            it.type == ProviderType.OPENROUTER && it.id !in appState.disabledConnectionIds
        }
        val model = appState.openRouterSpeechModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "OpenRouter не настроен")
            return
        }
        if (model.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "Сначала выберите модель озвучивания OpenRouter")
            return
        }

        val baseUrl = viewModel.connectionTextEndpoint(profile.id)
        val replyVoice = appState.openRouterSpeechVoice.trim()
        if (replyVoice.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "Голос озвучивания ответов не выбран")
            return
        }
        val chunks = splitForSpeech(text)
        mutableState.value = OpenRouterSpeechPlaybackState(
            messageId = messageId,
            phase = OpenRouterSpeechPhase.PREPARING
        )

        playbackJob = scope.launch {
            try {
                coroutineScope {
                    var pending: Deferred<File> = async {
                        synthesizeChunk(
                            apiKey = key,
                            model = model,
                            text = chunks.first(),
                            voice = replyVoice,
                            baseUrl = baseUrl
                        )
                    }

                    for (index in chunks.indices) {
                        ensureCurrent(requestGeneration, messageId)
                        if (!pending.isCompleted) {
                            mutableState.value = OpenRouterSpeechPlaybackState(
                                messageId = messageId,
                                phase = OpenRouterSpeechPhase.PREPARING
                            )
                        }
                        val file = pending.await()
                        ensureCurrent(requestGeneration, messageId)

                        val next: Deferred<File>? = if (index < chunks.lastIndex) {
                            async {
                                synthesizeChunk(
                                    apiKey = key,
                                    model = model,
                                    text = chunks[index + 1],
                                    voice = replyVoice,
                                    baseUrl = baseUrl
                                )
                            }
                        } else {
                            null
                        }

                        playFileAndAwait(file, requestGeneration, messageId)
                        deleteTempFile(file)
                        if (next == null) break
                        pending = next
                    }
                }

                if (requestGeneration == generation && mutableState.value.messageId == messageId) {
                    mutableState.value = OpenRouterSpeechPlaybackState()
                }
            } catch (_: CancellationException) {
                // Explicit stop or another message started playback.
            } catch (error: Throwable) {
                if (requestGeneration == generation) {
                    releasePlayer()
                    deleteAllTempFiles()
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        error = error.message ?: "Не удалось озвучить ответ через OpenRouter"
                    )
                }
            } finally {
                if (requestGeneration == generation) {
                    playbackJob = null
                    releasePlayer()
                    deleteAllTempFiles()
                }
            }
        }
    }

    private suspend fun synthesizeChunk(
        apiKey: String,
        model: String,
        text: String,
        voice: String?,
        baseUrl: String
    ): File {
        var lastNetworkError: IOException? = null
        repeat(2) { attempt ->
            try {
                val result = audioClient.synthesize(
                    apiKey = apiKey,
                    model = model,
                    input = text,
                    voice = voice,
                    responseFormat = "mp3",
                    baseUrl = baseUrl
                )
                val extension = result.format.lowercase()
                    .replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "mp3" }
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply {
                        writeBytes(result.bytes)
                    }
                }
                tempFiles += file
                return file
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastNetworkError = error
                if (attempt == 0) delay(650)
            }
        }
        throw lastNetworkError ?: IOException("Не удалось получить аудио от OpenRouter")
    }

    private suspend fun playFileAndAwait(
        file: File,
        requestGeneration: Long,
        messageId: String
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer

        fun releaseCurrent() {
            if (player === mediaPlayer) player = null
            runCatching { mediaPlayer.reset() }
            runCatching { mediaPlayer.release() }
        }

        continuation.invokeOnCancellation { releaseCurrent() }

        runCatching {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener {
                if (requestGeneration != generation || mutableState.value.messageId != messageId) {
                    releaseCurrent()
                    if (continuation.isActive) continuation.resume(Unit)
                    return@setOnPreparedListener
                }
                runCatching { mediaPlayer.start() }
                    .onSuccess {
                        mutableState.value = OpenRouterSpeechPlaybackState(
                            messageId = messageId,
                            phase = OpenRouterSpeechPhase.PLAYING
                        )
                    }
                    .onFailure { error ->
                        releaseCurrent()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
            mediaPlayer.setOnCompletionListener {
                releaseCurrent()
                if (continuation.isActive) continuation.resume(Unit)
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                releaseCurrent()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Ошибка воспроизведения аудио ($what/$extra)")
                    )
                }
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure { error ->
            releaseCurrent()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun ensureCurrent(requestGeneration: Long, messageId: String) {
        if (requestGeneration != generation || mutableState.value.messageId != messageId) {
            throw CancellationException("Speech session replaced")
        }
    }

    fun stop() {
        generation += 1L
        cancelActiveSession(resetState = true)
    }

    private fun cancelActiveSession(resetState: Boolean) {
        playbackJob?.cancel()
        playbackJob = null
        releasePlayer()
        deleteAllTempFiles()
        if (resetState) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    private fun releasePlayer() {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        player = null
    }

    private fun deleteTempFile(file: File) {
        tempFiles.remove(file)
        runCatching { file.delete() }
    }

    private fun deleteAllTempFiles() {
        tempFiles.toList().forEach(::deleteTempFile)
    }

    fun clearError() {
        if (mutableState.value.error != null) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    fun close() {
        stop()
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        scope.cancel()
    }

    private fun splitForSpeech(source: String, maxChars: Int = 280): List<String> {
        val normalized = source
            .replace("\r\n", "\n")
            .replace(Regex("[ \t]+"), " ")
            .trim()
        if (normalized.length <= maxChars) return listOf(normalized)

        val sentences = normalized
            .split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val pieces = mutableListOf<String>()
        sentences.forEach { sentence ->
            if (sentence.length <= maxChars) {
                pieces += sentence
            } else {
                var rest = sentence
                while (rest.length > maxChars) {
                    val candidate = rest.take(maxChars)
                    val splitAt = candidate.lastIndexOf(' ').takeIf { it >= maxChars / 2 } ?: maxChars
                    pieces += rest.take(splitAt).trim()
                    rest = rest.drop(splitAt).trimStart()
                }
                if (rest.isNotBlank()) pieces += rest
            }
        }

        if (pieces.isEmpty()) return listOf(normalized.take(maxChars))

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        pieces.forEach { piece ->
            val extra = if (current.isEmpty()) piece.length else piece.length + 1
            if (current.isNotEmpty() && current.length + extra > maxChars) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }
}
