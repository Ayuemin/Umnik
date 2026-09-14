package com.ayuemin.ymnik.ui

import android.content.Context
import android.media.MediaPlayer
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.network.OpenRouterAudioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
 * Audio exists only in cache for the current playback session. It is never
 * attached to a chat and never copied into Umnik's generated-file storage.
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
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    fun toggle(messageId: String, textRaw: String) {
        val text = textRaw.trim()
        if (messageId.isBlank() || text.isBlank()) return

        val current = mutableState.value
        if (current.messageId == messageId && current.phase != OpenRouterSpeechPhase.IDLE) {
            stop()
            return
        }

        stopInternal(resetState = false)
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

        val requestGeneration = ++generation
        mutableState.value = OpenRouterSpeechPlaybackState(
            messageId = messageId,
            phase = OpenRouterSpeechPhase.PREPARING
        )

        scope.launch {
            runCatching {
                val media = featurePrefs.media()
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = model,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                val extension = result.format.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "mp3" }
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply { writeBytes(result.bytes) }
                }
                file
            }.onSuccess { file ->
                if (requestGeneration != generation || mutableState.value.messageId != messageId) {
                    runCatching { file.delete() }
                    return@onSuccess
                }
                tempFile = file
                runCatching {
                    val mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { stop() }
                        setOnErrorListener { _, _, _ ->
                            stop()
                            true
                        }
                        prepare()
                        start()
                    }
                    player = mediaPlayer
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        messageId = messageId,
                        phase = OpenRouterSpeechPhase.PLAYING
                    )
                }.onFailure { error ->
                    stopInternal(resetState = false)
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        error = error.message ?: "Не удалось воспроизвести озвучку OpenRouter"
                    )
                }
            }.onFailure { error ->
                if (requestGeneration == generation) {
                    stopInternal(resetState = false)
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        error = error.message ?: "Не удалось озвучить ответ через OpenRouter"
                    )
                }
            }
        }
    }

    fun stop() {
        generation += 1L
        stopInternal(resetState = true)
    }

    private fun stopInternal(resetState: Boolean) {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        player = null
        tempFile?.let { runCatching { it.delete() } }
        tempFile = null
        if (resetState) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    fun clearError() {
        if (mutableState.value.error != null) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    fun close() {
        stop()
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        scope.cancel()
    }
}
