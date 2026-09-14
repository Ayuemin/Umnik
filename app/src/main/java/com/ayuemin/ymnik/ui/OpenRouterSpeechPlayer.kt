package com.ayuemin.ymnik.ui

import android.content.Context
import android.media.MediaPlayer
import com.ayuemin.ymnik.ChatViewModel
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
 * Short and medium answers are synthesized as one coherent utterance for better
 * prosody. Long answers are split at natural boundaries; the next part is prepared
 * while the current one plays. Audio only lives in app cache for this session.
 */
class OpenRouterSpeechPlayer(
    context: Context,
    private val viewModel: ChatViewModel
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secrets = SecretStore(appContext)
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
        val replyVoice = appState.openRouterSpeechVoice.trim().takeIf { it.isNotBlank() }
        val replyFormat = appState.openRouterSpeechResponseFormat.trim().takeIf { it.isNotBlank() }
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
                            responseFormat = replyFormat,
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
                                    responseFormat = replyFormat,
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
        responseFormat: String?,
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
                    responseFormat = responseFormat,
                    baseUrl = baseUrl
                )
                val pcm = result.format.equals("pcm", ignoreCase = true) || result.mimeType.equals("audio/pcm", ignoreCase = true)
                val extension = if (pcm) "wav" else result.format.lowercase()
                    .replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "mp3" }
                val playableBytes = if (pcm) {
                    OpenRouterAudioClient.pcmToWav(
                        pcm = result.bytes,
                        sampleRateHz = result.sampleRateHz ?: 24_000,
                        channels = result.channels ?: 1
                    )
                } else result.bytes
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply {
                        writeBytes(playableBytes)
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

    private fun splitForSpeech(
        source: String,
        singleRequestMaxChars: Int = 1800,
        chunkMaxChars: Int = 1500
    ): List<String> {
        val normalized = source
            .replace("\r\n", "\n")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (normalized.length <= singleRequestMaxChars) return listOf(normalized)

        fun splitLongParagraph(paragraph: String): List<String> {
            var rest = paragraph.trim()
            if (rest.length <= chunkMaxChars) return listOf(rest)
            val result = mutableListOf<String>()
            while (rest.length > chunkMaxChars) {
                val candidate = rest.take(chunkMaxChars)
                val sentenceBreak = listOf(". ", "! ", "? ", "… ")
                    .maxOf { candidate.lastIndexOf(it) }
                    .takeIf { it >= chunkMaxChars / 2 }
                    ?.plus(1)
                val wordBreak = candidate.lastIndexOf(' ')
                    .takeIf { it >= chunkMaxChars / 2 }
                val splitAt = sentenceBreak ?: wordBreak ?: chunkMaxChars
                result += rest.take(splitAt).trim()
                rest = rest.drop(splitAt).trimStart()
            }
            if (rest.isNotBlank()) result += rest
            return result
        }

        val pieces = normalized
            .split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap(::splitLongParagraph)

        if (pieces.isEmpty()) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        pieces.forEach { piece ->
            val separator = if (current.isEmpty()) "" else "\n\n"
            if (current.isNotEmpty() && current.length + separator.length + piece.length > chunkMaxChars) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

}
