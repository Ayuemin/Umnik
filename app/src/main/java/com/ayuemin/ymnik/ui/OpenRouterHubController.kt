package com.ayuemin.ymnik.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.OpenRouterBackgroundWorker
import com.ayuemin.ymnik.RequestKeepAliveService
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.data.VideoJobRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.BatchJob
import com.ayuemin.ymnik.model.BatchJobItem
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.OpenRouterMediaSettings
import com.ayuemin.ymnik.model.PendingAttachment
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.UserProfileScope
import com.ayuemin.ymnik.model.VideoJob
import com.ayuemin.ymnik.network.OpenRouterAudioClient
import com.ayuemin.ymnik.network.OpenRouterBatchBodyBuilder
import com.ayuemin.ymnik.network.OpenRouterBatchClient
import com.ayuemin.ymnik.network.OpenRouterCatalogClient
import com.ayuemin.ymnik.network.OpenRouterFilesClient
import com.ayuemin.ymnik.network.OpenRouterResponsesClient
import com.ayuemin.ymnik.network.OpenRouterVideoClient
import com.ayuemin.ymnik.network.ShellAttachmentEnvelope
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
import java.util.concurrent.atomic.AtomicBoolean

data class OpenRouterHubState(
    val catalog: List<ModelInfo> = emptyList(),
    val routing: ProviderRoutingSettings = ProviderRoutingSettings(),
    val tools: ServerToolSettings = ServerToolSettings(),
    val media: OpenRouterMediaSettings = OpenRouterMediaSettings(),
    val batches: List<BatchJob> = emptyList(),
    val videos: List<VideoJob> = emptyList(),
    val loading: Boolean = false,
    val operation: String? = null,
    val status: String? = null,
    val transcription: String = "",
    val shellResult: String = "",
    val shellRunning: Boolean = false,
    val shellFileCount: Int = 0,
    val shellError: String? = null,
    val shellChatId: String? = null,
    val speechFile: GeneratedFile? = null
)

private object ShellRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var cancelCurrent: (() -> Unit)? = null

    fun installCancel(cancel: () -> Unit) {
        cancelCurrent = cancel
    }

    fun cancel() {
        cancelCurrent?.invoke()
    }

    fun clear() {
        cancelCurrent = null
    }
}

class OpenRouterHubController(
    private val context: Context,
    private val viewModel: ChatViewModel
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secrets = SecretStore(context)
    private val featurePrefs = OpenRouterFeaturePrefs(context)
    private val catalogClient = OpenRouterCatalogClient(context)
    private val batchClient = OpenRouterBatchClient(context)
    private val batchBuilder = OpenRouterBatchBodyBuilder(context)
    private val batchRepository = BatchJobRepository(context)
    private val videoClient = OpenRouterVideoClient(context)
    private val videoRepository = VideoJobRepository(context)
    private val audioClient = OpenRouterAudioClient(context)
    private val responsesClient = OpenRouterResponsesClient(context)
    private val filesClient = OpenRouterFilesClient(context)
    private val chats = ChatRepository(context)
    private val skills = SkillRepository(context)

    private val mutableState = MutableStateFlow(
        OpenRouterHubState(
            routing = featurePrefs.routing(),
            tools = featurePrefs.tools(),
            media = featurePrefs.media(),
            batches = batchRepository.list(),
            videos = videoRepository.list()
        )
    )
    val state: StateFlow<OpenRouterHubState> = mutableState.asStateFlow()

    fun close() = scope.cancel()

    fun refreshCatalog(forceMessage: Boolean = false) {
        val profile = openRouterProfile()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Сначала включите OpenRouter и сохраните API-ключ")
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Загружаю полный каталог OpenRouter…", status = null)
            runCatching { catalogClient.allModels(key, viewModel.connectionTextEndpoint(profile.id)) }
                .onSuccess { models ->
                    mutableState.value = mutableState.value.copy(
                        catalog = models,
                        batches = batchRepository.list(),
                        videos = videoRepository.list(),
                        loading = false,
                        operation = null,
                        status = if (forceMessage) "Каталог обновлён: ${models.size} моделей" else null
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось загрузить каталог OpenRouter")
                }
        }
    }

    fun refreshJobs() {
        mutableState.value = mutableState.value.copy(
            batches = batchRepository.list(),
            videos = videoRepository.list()
        )
        if (batchRepository.active().isNotEmpty() || videoRepository.active().isNotEmpty()) {
            OpenRouterBackgroundWorker.schedule(context, replace = false)
        }
    }

    fun clearStatus() {
        if (mutableState.value.status != null) {
            mutableState.value = mutableState.value.copy(status = null)
        }
    }

    fun updateRouting(value: ProviderRoutingSettings) {
        featurePrefs.saveRouting(value)
        mutableState.value = mutableState.value.copy(routing = value, status = null)
    }

    fun updateTools(value: ServerToolSettings) {
        featurePrefs.saveTools(value)
        mutableState.value = mutableState.value.copy(tools = value, status = null)
    }

    fun updateMedia(value: OpenRouterMediaSettings) {
        featurePrefs.saveMedia(value)
        mutableState.value = mutableState.value.copy(media = value, status = null)
    }

    fun useAsTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не является текстовой")
            return
        }
        if (model.isBatch) {
            val media = mutableState.value.media.copy(batchModel = model.id)
            featurePrefs.saveMedia(media)
            mutableState.value = mutableState.value.copy(media = media, status = null)
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectDefaultTextModel(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun toggleQuickTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) return
        if (model.isBatch) {
            mutableState.value = mutableState.value.copy(status = "Batch-модель нельзя добавить в быстрые")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.toggleQuickTextModelForConnection(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun useAsSystemModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories || model.isBatch) {
            mutableState.value = mutableState.value.copy(
                status = "Для системных задач нужна обычная текстовая модель"
            )
            return
        }
        viewModel.setSystemModel(model.id)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun useAsImageModel(model: ModelInfo) {
        if (ModelCategory.IMAGE !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не генерирует изображения")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectImageModel(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun assignModel(model: ModelInfo, category: ModelCategory) {
        when (category) {
            ModelCategory.TEXT -> {
                if (model.isBatch) {
                    val media = mutableState.value.media.copy(batchModel = model.id)
                    featurePrefs.saveMedia(media)
                    mutableState.value = mutableState.value.copy(media = media, status = null)
                } else {
                    useAsTextModel(model)
                }
            }
            ModelCategory.IMAGE -> useAsImageModel(model)
            ModelCategory.VIDEO -> {
                val media = mutableState.value.media.copy(videoModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = null)
            }
            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val current = mutableState.value.media
                val media = current.copy(
                    speechModel = model.id,
                    voice = if (current.speechModel == model.id) current.voice else "",
                    responseFormat = if (current.speechModel == model.id) current.responseFormat else null
                )
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = null)
            }
            ModelCategory.TRANSCRIPTION -> {
                val media = mutableState.value.media.copy(transcriptionModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = null)
            }
            ModelCategory.EMBEDDINGS -> {
                viewModel.setEmbeddingModel(model.id)
                mutableState.value = mutableState.value.copy(status = null)
            }
            ModelCategory.RERANK -> {
                mutableState.value = mutableState.value.copy(
                    status = "Отдельная Rerank-модель сейчас не используется"
                )
            }
        }
    }

    fun assignReplySpeechModel(model: ModelInfo) {
        if (ModelCategory.SPEECH !in model.categories && ModelCategory.AUDIO !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не поддерживает озвучивание")
            return
        }
        viewModel.setOpenRouterSpeechModel(model.id)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun setReplySpeechModelId(modelId: String) {
        viewModel.setOpenRouterSpeechModel(modelId.trim())
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun setMediaSpeechModelId(modelId: String) {
        val clean = modelId.trim()
        val current = mutableState.value.media
        val changed = current.speechModel != clean
        val media = current.copy(
            speechModel = clean,
            voice = if (changed) "" else current.voice,
            responseFormat = if (changed) null else current.responseFormat
        )
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = null)
    }

    fun setBatchModelId(modelId: String) {
        val media = mutableState.value.media.copy(batchModel = modelId.trim())
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = null)
    }

    fun setVideoModelId(modelId: String) {
        val media = mutableState.value.media.copy(videoModel = modelId.trim())
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = null)
    }

    fun setTranscriptionModelId(modelId: String) {
        val media = mutableState.value.media.copy(transcriptionModel = modelId.trim())
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = null)
    }

    fun updateReplySpeechVoice(voice: String) {
        viewModel.setOpenRouterSpeechVoice(voice)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun updateReplySpeechResponseFormat(format: String) {
        viewModel.setOpenRouterSpeechResponseFormat(format)
        mutableState.value = mutableState.value.copy(status = null)
    }

    fun clearBatchModel() {
        val media = mutableState.value.media.copy(batchModel = "")
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = "Batch-модель снята")
    }

    fun clearFinishedBatchHistory() {
        runCatching {
            val remaining = batchRepository.list().filterNot { it.status.terminal }
            batchRepository.save(remaining)
            remaining
        }.onSuccess { remaining ->
            mutableState.value = mutableState.value.copy(
                batches = remaining,
                status = if (remaining.isEmpty()) "История Batch очищена" else "Завершённая история Batch очищена; активные задачи сохранены"
            )
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(status = error.message ?: "Не удалось очистить историю Batch")
        }
    }

    fun clearFinishedVideoHistory() {
        runCatching {
            val remaining = videoRepository.list().filterNot { it.status.terminal }
            videoRepository.save(remaining)
            remaining
        }.onSuccess { remaining ->
            mutableState.value = mutableState.value.copy(
                videos = remaining,
                status = if (remaining.isEmpty()) "История видео очищена" else "Завершённая история видео очищена; активные задачи сохранены"
            )
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(status = error.message ?: "Не удалось очистить историю видео")
        }
    }

    fun submitBatch(raw: String, taskFileUris: List<List<Uri>> = emptyList()) {
        val totalFiles = taskFileUris.sumOf { it.size }
        DiagnosticLog.action(context, "batch_submit", "inputChars=${raw.length}; taskFiles=$totalFiles")
        val input = raw.trim()
        if (input.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Введите хотя бы одно задание")
            return
        }
        val profile = openRouterProfile()
        val model = mutableState.value.media.batchModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (!model.endsWith(":batch", true)) {
            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель Batch в каталоге")
            return
        }
        val prompts = input.split(Regex("(?m)^\\s*---+\\s*$"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (prompts.isEmpty()) return
        val filesPerPrompt = prompts.indices.map { index -> taskFileUris.getOrNull(index).orEmpty().take(6) }
        val originState = viewModel.state.value
        val originChatId = originState.currentChatId
        val originChat = originState.chats.firstOrNull { it.id == originChatId }
        val originTeam = originChat?.teamId?.let { id -> originState.teams.firstOrNull { it.id == id } }
        AsyncJobEvents.markHubToolRunning(originChatId, "batch", "Batch отправляется…")

        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Отправляю Batch…", status = null)
            runCatching {
                val chat = originChat
                val team = originTeam
                val modelInfo = mutableState.value.catalog.firstOrNull { it.id == model }
                // Batch API не принимает обычные file/image parts. Текстовые файлы
                // конкретной задачи безопасно встраиваются только в её prompt.
                val fileContexts = withContext(Dispatchers.IO) { filesPerPrompt.map(::batchTextContext) }
                val system = buildSystemPrompt(chat, team)
                val requests = prompts.mapIndexed { index, prompt ->
                    val inlineFiles = fileContexts[index]
                    val promptWithFiles = if (inlineFiles.isBlank()) prompt else "$prompt\n\n===== ФАЙЛЫ ЭТОЙ ЗАДАЧИ =====\n$inlineFiles"
                    OpenRouterBatchClient.BatchRequest(
                        customId = "hub-${index + 1}-${UUID.randomUUID()}",
                        label = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Задание ${index + 1}",
                        body = batchBuilder.build(
                            model = model,
                            history = chat?.messages.orEmpty().filterNot { message ->
                        message.role == "assistant" &&
                            message.text.trimStart().startsWith("Shell не выполнил задачу:")
                    },
                            prompt = promptWithFiles,
                            attachments = emptyList(),
                            systemPrompt = system,
                            reasoningEnabled = false,
                            reasoningEffort = null,
                            modelInfo = modelInfo
                        )
                    )
                }
                val snapshot = batchClient.create(key, model, requests, viewModel.connectionTextEndpoint(profile.id))
                val job = BatchJob(
                    id = UUID.randomUUID().toString(),
                    remoteId = snapshot.remoteId,
                    connectionProfileId = profile.id,
                    chatId = originChatId,
                    teamId = team?.id,
                    modelId = model,
                    baseModelId = model.removeSuffix(":batch"),
                    title = "Batch · ${requests.size} заданий",
                    status = snapshot.status,
                    items = if (snapshot.items.isNotEmpty()) snapshot.items else requests.map { BatchJobItem(it.customId, it.label) },
                    error = snapshot.error
                )
                batchRepository.upsert(job)
                appendHubUserMessage(originChatId, "[Batch: ${requests.size}${if (totalFiles > 0) " · файлов: $totalFiles" else ""}]\n$input")
                OpenRouterBackgroundWorker.schedule(context, replace = false)
                job
            }.onSuccess { job ->
                mutableState.value = mutableState.value.copy(
                    batches = batchRepository.list(),
                    loading = false,
                    operation = null,
                    status = "Batch принят · ${job.remoteId}"
                )
                AsyncJobEvents.markHubToolFinished(originChatId, "batch")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                AsyncJobEvents.markHubToolFinished(originChatId, "batch")
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось создать Batch")
            }
        }
    }

    fun submitVideo(promptRaw: String, references: List<Uri> = emptyList()) {
        DiagnosticLog.action(context, "video_submit", "promptChars=${promptRaw.length}; refs=${references.size}")
        val prompt = promptRaw.trim()
        val profile = openRouterProfile()
        val model = mutableState.value.media.videoModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (prompt.isBlank()) { mutableState.value = mutableState.value.copy(status = "Введите описание видео"); return }
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        if (model.isBlank()) { mutableState.value = mutableState.value.copy(status = "Сначала выберите модель видео"); return }
        val originState = viewModel.state.value
        val originChatId = originState.currentChatId
        val originTeamId = originState.chats.firstOrNull { it.id == originChatId }?.teamId
        AsyncJobEvents.markHubToolRunning(originChatId, "video", "Видео отправляется…")

        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Отправляю генерацию видео…", status = null)
            runCatching {
                val refs = withContext(Dispatchers.IO) { references.take(4).map(::videoReference) }
                val snapshot = videoClient.submit(
                    apiKey = key,
                    model = model,
                    prompt = prompt,
                    options = OpenRouterVideoClient.SubmitOptions(references = refs),
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                val job = VideoJob(
                    id = UUID.randomUUID().toString(),
                    remoteId = snapshot.id,
                    connectionProfileId = profile.id,
                    chatId = originChatId,
                    teamId = originTeamId,
                    modelId = model,
                    prompt = prompt,
                    status = snapshot.status,
                    generationId = snapshot.generationId,
                    pollingUrl = snapshot.pollingUrl,
                    remoteUrls = snapshot.urls,
                    costUsd = snapshot.costUsd,
                    error = snapshot.error
                )
                videoRepository.upsert(job)
                appendHubUserMessage(originChatId, "[Видео · ${model.substringAfterLast('/')} ]\n$prompt")
                OpenRouterBackgroundWorker.schedule(context, replace = false)
                job
            }.onSuccess { job ->
                mutableState.value = mutableState.value.copy(
                    videos = videoRepository.list(),
                    loading = false,
                    operation = null,
                    status = "Видео принято · ${job.remoteId}"
                )
                AsyncJobEvents.markHubToolFinished(originChatId, "video")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                AsyncJobEvents.markHubToolFinished(originChatId, "video")
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось запустить видео")
            }
        }
    }

    fun transcribe(uri: Uri) {
        DiagnosticLog.action(context, "transcription_submit")
        val profile = openRouterProfile()
        val model = mutableState.value.media.transcriptionModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val chatId = viewModel.state.value.currentChatId
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        if (model.isBlank()) { mutableState.value = mutableState.value.copy(status = "Сначала выберите модель распознавания речи"); return }
        AsyncJobEvents.markHubToolRunning(chatId, "transcription", "Распознаю аудио…")
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Распознаю аудио…", status = null)
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать аудио") }
                val format = uri.lastPathSegment?.substringAfterLast('.', "mp3") ?: "mp3"
                audioClient.transcribe(key, model, bytes, format, baseUrl = viewModel.connectionTextEndpoint(profile.id))
            }.onSuccess { result ->
                appendHubExchange(chatId, "[Распознавание речи]", result.text, emptyList())
                mutableState.value = mutableState.value.copy(loading = false, operation = null, transcription = result.text, status = "Расшифровка готова и добавлена в чат")
                AsyncJobEvents.markHubToolFinished(chatId, "transcription")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                AsyncJobEvents.markHubToolFinished(chatId, "transcription")
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось распознать аудио")
            }
        }
    }

    fun loadTextFileForInput(uri: Uri, maxChars: Int = 60_000, onReady: (String) -> Unit) {
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Читаю текстовый файл…", status = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val data = uriBytes(uri)
                    ensureTextFile(data)
                    val value = data.bytes.toString(Charsets.UTF_8).trim()
                    if (value.isBlank()) error("В выбранном файле нет текста")
                    if (value.length > maxChars) error("Текстовый файл слишком большой: максимум $maxChars символов для этого поля")
                    value
                }
            }.onSuccess { value ->
                onReady(value)
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = "Текст загружен из файла")
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось прочитать файл")
            }
        }
    }

    fun synthesize(textRaw: String) {
        DiagnosticLog.action(context, "speech_submit", "textChars=${textRaw.length}")
        val text = textRaw.trim()
        val profile = openRouterProfile()
        val media = mutableState.value.media
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val chatId = viewModel.state.value.currentChatId
        if (text.isBlank()) { mutableState.value = mutableState.value.copy(status = "Введите текст для озвучивания"); return }
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        if (media.speechModel.isBlank()) { mutableState.value = mutableState.value.copy(status = "Сначала выберите speech-модель"); return }
        AsyncJobEvents.markHubToolRunning(chatId, "speech", "Создаю аудио…")
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Создаю аудио…", status = null)
            runCatching {
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = media.speechModel,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    responseFormat = media.responseFormat?.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result)
            }.onSuccess { file ->
                appendHubExchange(chatId, "[Озвучивание]\n$text", "Аудио готово: ${file.name}", listOf(file))
                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Аудио создано и добавлено в чат")
                AsyncJobEvents.markHubToolFinished(chatId, "speech")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                AsyncJobEvents.markHubToolFinished(chatId, "speech")
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось создать аудио")
            }
        }
    }

    fun synthesizeAnswer(chatId: String, textRaw: String) {
        val text = textRaw.trim()
        if (text.isBlank() || chatId.isBlank()) return
        if (mutableState.value.loading) {
            mutableState.value = mutableState.value.copy(status = "Дождитесь завершения текущей операции OpenRouter")
            return
        }
        val profile = openRouterProfile()
        val appState = viewModel.state.value
        val model = appState.openRouterSpeechModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (model.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель озвучивания ответов")
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Озвучиваю ответ через OpenRouter…", status = null)
            runCatching {
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = model,
                    input = text,
                    voice = appState.openRouterSpeechVoice.takeIf { it.isNotBlank() },
                    responseFormat = appState.openRouterSpeechResponseFormat.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result)
            }.onSuccess { file ->
                appendHubAssistantResult(
                    chatId = chatId,
                    assistantText = "Озвучка OpenRouter · ${model.substringAfterLast('/')}",
                    files = listOf(file)
                )
                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Озвучка OpenRouter добавлена в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось озвучить ответ через OpenRouter")
            }
        }
    }

    fun cancelShell() {
        ShellRuntime.cancel()
    }

    fun runShell(promptRaw: String, attachments: List<Uri> = emptyList()) {
        DiagnosticLog.action(context, "shell_submit", "promptChars=${promptRaw.length}; attachments=${attachments.size}")
        val prompt = promptRaw.trim()
        val profile = openRouterProfile()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val originState = viewModel.state.value
        val originChatId = originState.currentChatId
        val currentModel = originState.currentChatTextModel ?: originState.textModel
        val model = currentModel.removeSuffix(":batch")
        if (prompt.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Введите задачу для Shell")
            return
        }
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (AsyncJobEvents.shellActivity.value != null) {
            mutableState.value = mutableState.value.copy(status = "Другая задача Shell уже выполняется")
            return
        }

        val cancelRequested = AtomicBoolean(false)
        ShellRuntime.scope.launch {
            AsyncJobEvents.markShellRunning(originChatId, model, attachments.size)
            ShellRuntime.installCancel {
                cancelRequested.set(true)
                responsesClient.cancelActive()
            }
            runCatching { RequestKeepAliveService.start(context) }
            mutableState.value = mutableState.value.copy(
                loading = true,
                operation = "Shell выполняет задачу…",
                status = null,
                shellResult = "",
                shellRunning = true,
                shellFileCount = 0,
                shellError = null,
                shellChatId = originChatId
            )

            fun updateShellProgress(
                label: String,
                responseId: String? = null,
                shellStepDelta: Int = 0,
                remoteSignal: Boolean = false
            ) {
                AsyncJobEvents.updateShellProgress(
                    chatId = originChatId,
                    status = label,
                    responseId = responseId,
                    shellStepDelta = shellStepDelta,
                    remoteSignal = remoteSignal
                )
                runCatching { RequestKeepAliveService.update(context) }
            }

            val uploadedIds = mutableListOf<String>()
            val transportedAttachments = mutableListOf<ShellTransportedAttachment>()
            runCatching {
                attachments.take(10).forEachIndexed { index, uri ->
                    updateShellProgress("Передаю файл ${index + 1} из ${attachments.take(10).size}")
                    val attachment = withContext(Dispatchers.IO) { uriBytes(uri) }
                    val uploaded = uploadShellAttachment(
                        apiKey = key,
                        attachment = attachment,
                        index = index,
                        baseUrl = viewModel.connectionTextEndpoint(profile.id)
                    )
                    uploadedIds += uploaded.remoteId
                    transportedAttachments += uploaded
                    if (cancelRequested.get()) error("Shell остановлен пользователем")
                }
                val chat = originState.chats.firstOrNull { it.id == originChatId }
                val team = chat?.teamId?.let { id -> originState.teams.firstOrNull { it.id == id } }
                val shellSystemPrompt = buildString {
                    append(buildSystemPrompt(chat, team))
                    appendLine()
                    appendLine("===== РЕЖИМ SHELL =====")
                    appendLine("Прикреплённые к этому запросу файлы — пользовательские вложения из Umnik. В контейнере их имена могут получить служебный префикс OpenRouter; не говори пользователю, что эти файлы тебе недоступны.")
                    val encodedFallbacks = transportedAttachments.filter { it.encodedFallback }
                    if (encodedFallbacks.isNotEmpty()) {
                        appendLine("Некоторые бинарные вложения OpenRouter Files не принял в исходном виде. Umnik передал их через транспортные текстовые файлы вида umnik_attachment_N.b64.txt.")
                        appendLine("Перед основной задачей обязательно восстанови такие вложения. Формат транспорта: первая строка UMNIK_BASE64_ATTACHMENT_V1; original_name_base64 содержит имя исходного файла в Base64 UTF-8; mime_base64 — MIME; sha256 — контрольную сумму; полезные данные находятся между data_base64_begin и data_base64_end.")
                        appendLine("Восстанови исходные байты Base64-декодированием в отдельную рабочую папку, проверь SHA-256 и дальше работай только с восстановленным файлом. Транспортный .b64.txt — служебная оболочка Umnik, не включай её в пользовательский результат.")
                        encodedFallbacks.forEach { item ->
                            appendLine("Транспорт: ${item.transportName} → исходный файл: ${item.originalName}")
                        }
                    }
                    appendLine("Если среди вложений есть ZIP-архив, при необходимости работай с ним как с целой папкой, проектом или набором данных. Перед изменениями зафиксируй список путей внутри исходного архива, затем распакуй его в отдельную временную рабочую папку. Исходный архив не изменяй и не перезаписывай.")
                    appendLine("Все файлы и папки, которые были в пользовательском архиве, по умолчанию считаются частью пользовательских данных. Не удаляй их только потому, что они не понадобились при анализе. Если для исправления действительно нужно удалить, переименовать или переместить исходный файл, делай это осознанно как часть задачи, а не как очистку временных данных.")
                    appendLine("Если пользователь передал ZIP с папкой или проектом и просит проверить, исправить, поправить или доработать содержимое, по умолчанию верни новый ZIP со всем обновлённым деревом, даже если пользователь отдельно не написал «верни целиком». Исключение — если он явно попросил только отдельные файлы, патч или отчёт.")
                    appendLine("Перед упаковкой сравни итоговое дерево с исходным списком путей: случайно пропавшие исходные файлы восстанови; намеренно удалённые или переименованные в рамках исправления не восстанавливай. В новый ZIP включи неизменённые исходные файлы, изменённые файлы и новые файлы, которые являются частью результата. Не включай только временные скрипты, кэши, промежуточные файлы и прочие артефакты, созданные исключительно для выполнения задачи.")
                    appendLine("RAR/7z используй только если формат реально поддерживается доступными утилитами; не обещай поддержку, если распаковка не удалась.")
                    appendLine("Вспомогательные скрипты и промежуточные файлы создавай как временные и удаляй перед завершением.")
                    appendLine("Возвращай пользователю запрошенный итоговый результат. Служебные скрипты и временные файлы не возвращай, если пользователь отдельно их не просил.")
                    appendLine("В финальном ответе описывай результат понятным языком и не акцентируй внутренние пути контейнера без необходимости.")
                    appendLine("===== КОНЕЦ РЕЖИМА SHELL =====")
                }
                if (cancelRequested.get()) error("Shell остановлен пользователем")
                updateShellProgress("Запускаю модель и Shell")
                val shellHistory = chat?.messages.orEmpty().filterNot { message ->
                    message.role == "assistant" &&
                        message.text.trimStart().startsWith("Shell не выполнил задачу:")
                }
                val result = responsesClient.respond(
                    apiKey = key,
                    model = model,
                    history = shellHistory,
                    prompt = prompt,
                    systemPrompt = shellSystemPrompt,
                    tools = mutableState.value.tools.copy(shell = true),
                    routing = mutableState.value.routing,
                    shellFileIds = uploadedIds,
                    onProgress = { progress ->
                        updateShellProgress(
                            label = progress.label,
                            responseId = progress.responseId,
                            shellStepDelta = progress.shellStepDelta,
                            remoteSignal = true
                        )
                    },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                if (result.shellArtifacts.isNotEmpty()) {
                    updateShellProgress("Скачиваю созданные файлы")
                } else {
                    updateShellProgress("Получаю итоговый ответ")
                }
                val generated = result.shellArtifacts.mapNotNull { artifact ->
                    runCatching {
                        val bytes = filesClient.downloadContainerFile(
                            key,
                            artifact.containerId,
                            artifact.fileId,
                            viewModel.connectionTextEndpoint(profile.id)
                        )
                        saveGeneratedBinary(
                            artifact.name ?: "shell_${artifact.fileId}.bin",
                            "application/octet-stream",
                            bytes
                        )
                    }.getOrNull()
                }
                result to generated
            }.onSuccess { (result, generated) ->
                appendHubExchange(originChatId, "[Shell]\n$prompt", result.text, generated)
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    operation = null,
                    shellResult = result.text,
                    shellRunning = false,
                    shellFileCount = generated.size,
                    shellError = null,
                    shellChatId = originChatId,
                    status = if (generated.isEmpty()) {
                        "Shell завершил работу"
                    } else {
                        "Shell завершил работу · файлов: ${generated.size}"
                    }
                )
                AsyncJobEvents.markShellFinished(originChatId)
                runCatching { RequestKeepAliveService.update(context) }
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                val rawMessage = error.message ?: "Ошибка Shell"
                val activityBeforeFailure = AsyncJobEvents.shellActivity.value
                val modelNotStarted = activityBeforeFailure?.responseId == null &&
                    (activityBeforeFailure?.eventCount ?: 0) == 0
                val message = when {
                    cancelRequested.get() -> "Shell остановлен пользователем"
                    modelNotStarted && (
                        rawMessage.contains("PROTOCOL_ERROR", ignoreCase = true) ||
                            rawMessage.contains("stream was reset", ignoreCase = true)
                    ) ->
                        "Не удалось передать файл в OpenRouter: соединение оборвалось до запуска модели. Платный запрос Shell не был запущен."
                    rawMessage.contains("Software caused connection abort", ignoreCase = true) ->
                        "Соединение с OpenRouter оборвалось. Автоматический повтор не запущен, чтобы не списать деньги повторно."
                    else -> rawMessage
                }
                val activity = AsyncJobEvents.shellActivity.value
                DiagnosticLog.record(
                    context,
                    "SHELL",
                    "failed; response=${activity?.responseId ?: "none"}; events=${activity?.eventCount ?: 0}; shellSteps=${activity?.shellSteps ?: 0}",
                    error
                )
                appendHubExchange(
                    originChatId,
                    "[Shell]\n$prompt",
                    if (cancelRequested.get()) message else "Shell не выполнил задачу: $message",
                    emptyList()
                )
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    operation = null,
                    shellRunning = false,
                    shellFileCount = 0,
                    shellError = message,
                    shellChatId = originChatId,
                    status = message
                )
                AsyncJobEvents.markShellFinished(originChatId)
                runCatching { RequestKeepAliveService.update(context) }
                AsyncJobEvents.notifyChanged()
            }
            uploadedIds.forEach { id ->
                ShellRuntime.scope.launch(Dispatchers.IO) {
                    runCatching {
                        filesClient.delete(
                            key,
                            id,
                            viewModel.connectionTextEndpoint(profile.id)
                        )
                    }
                }
            }
            ShellRuntime.clear()
        }
    }

    fun clearTransientResult() {
        mutableState.value = mutableState.value.copy(
            transcription = "",
            shellResult = "",
            shellRunning = false,
            shellFileCount = 0,
            shellError = null,
            shellChatId = null,
            speechFile = null,
            status = null
        )
    }

    private fun openRouterProfile(): ConnectionProfile? = viewModel.state.value.connectionProfiles.firstOrNull {
        it.type == ProviderType.OPENROUTER && it.id !in viewModel.state.value.disabledConnectionIds
    }

    private fun buildSystemPrompt(
        chat: com.ayuemin.ymnik.model.ChatSession?,
        team: com.ayuemin.ymnik.model.Team?
    ): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        val appState = viewModel.state.value
        val profile = appState.userProfile
        val useProfile = !profile.isEmpty() &&
            appState.userProfileScope == UserProfileScope.CHATS &&
            team == null
        if (useProfile) {
            appendLine("\n===== КРАТКО О ПОЛЬЗОВАТЕЛЕ =====")
            if (profile.name.isNotBlank()) appendLine("Имя: ${profile.name}")
            if (profile.gender.isNotBlank()) appendLine("Пол: ${profile.gender}")
            if (profile.age.isNotBlank()) appendLine("Возраст: ${profile.age}")
            if (profile.occupation.isNotBlank()) appendLine("Род занятий: ${profile.occupation}")
            if (profile.note.isNotBlank()) appendLine("Предпочтение в общении: ${profile.note}")
            appendLine("Используй эти сведения только когда они полезны. Явный запрос и инструкции команды важнее профиля.")
            appendLine("===== КОНЕЦ ПРОФИЛЯ =====")
        }
        if (team != null) {
            appendLine("\n===== КОМАНДА: ${team.name} =====")
            appendLine("Команда — только кабинет; рабочие настройки принадлежат специалистам.")
            appendLine("===== КОНЕЦ КОМАНДЫ =====")
        }
        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {
            appendLine("\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")
            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }
            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")
        }
        val skillText = skills.promptFor(appState.activeSkillIds)
        if (skillText.isNotBlank()) {
            appendLine("\n===== ПОДКЛЮЧЁННЫЕ НАВЫКИ =====")
            appendLine(skillText)
            appendLine("===== КОНЕЦ НАВЫКОВ =====")
        }
    }

    private fun appendHubUserMessage(chatId: String?, text: String) {
        if (chatId.isNullOrBlank()) return
        val all = chats.list()
        val next = all.map { chat ->
            if (chat.id == chatId) chat.copy(
                messages = chat.messages + ChatMessage(UUID.randomUUID().toString(), "user", text),
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chats.save(next)
        DiagnosticLog.record(context, "CHAT_RESULT", "hub user message added; chat=${chatId.take(8)}; chars=${text.length}")
        AsyncJobEvents.notifyChanged()
    }

    private fun appendHubExchange(chatId: String, userText: String, assistantText: String, files: List<GeneratedFile>) {
        val all = chats.list()
        val marker = "hub:${UUID.randomUUID()}"
        val next = all.map { chat ->
            if (chat.id == chatId) chat.copy(
                messages = chat.messages +
                    ChatMessage(UUID.randomUUID().toString(), "user", userText) +
                    ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText, generatedFiles = files, deliveryState = marker),
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chats.save(next)
        DiagnosticLog.record(context, "CHAT_RESULT", "hub exchange added; chat=${chatId.take(8)}; assistantChars=${assistantText.length}; files=${files.size}; fileTypes=${files.map { it.mimeType }.distinct().joinToString(",")}")
    }

    private fun appendHubAssistantResult(chatId: String, assistantText: String, files: List<GeneratedFile>) {
        val all = chats.list()
        val marker = "hub:${UUID.randomUUID()}"
        val next = all.map { chat ->
            if (chat.id == chatId) chat.copy(
                messages = chat.messages + ChatMessage(
                    UUID.randomUUID().toString(),
                    "assistant",
                    assistantText,
                    generatedFiles = files,
                    deliveryState = marker
                ),
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chats.save(next)
        DiagnosticLog.record(context, "CHAT_RESULT", "hub assistant result added; chat=${chatId.take(8)}; files=${files.size}")
    }

    private fun ensureTextFile(data: UriData) {
        val mime = data.mime.lowercase()
        val lowerName = data.name.lowercase()
        val supported = mime.startsWith("text/") ||
            mime == "application/json" || mime == "application/xml" ||
            lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".json") || lowerName.endsWith(".xml") || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml")
        if (!supported) error("Этот режим принимает текстовые файлы: TXT, MD, CSV, JSON, XML или YAML")
    }

    private fun batchTextContext(uris: List<Uri>): String {
        if (uris.isEmpty()) return ""
        var totalChars = 0
        return buildString {
            uris.forEachIndexed { index, uri ->
                val data = uriBytes(uri)
                ensureTextFile(data)
                if (data.bytes.size > 512 * 1024) error("Файл ${data.name} слишком большой для Batch-вложения")
                val value = data.bytes.toString(Charsets.UTF_8).trim()
                totalChars += value.length
                if (totalChars > 120_000) error("Суммарный текст файлов слишком большой для Batch. Уменьшите объём материалов")
                if (index > 0) append("\n\n")
                append("===== ${data.name} =====\n")
                append(value)
            }
        }
    }

    private data class UriData(val name: String, val mime: String, val bytes: ByteArray)

    private data class ShellTransportedAttachment(
        val remoteId: String,
        val originalName: String,
        val transportName: String,
        val encodedFallback: Boolean
    )

    private suspend fun uploadShellAttachment(
        apiKey: String,
        attachment: UriData,
        index: Int,
        baseUrl: String
    ): ShellTransportedAttachment {
        val direct = runCatching {
            filesClient.upload(
                apiKey,
                attachment.name,
                attachment.mime,
                attachment.bytes,
                baseUrl
            )
        }
        direct.getOrNull()?.let { remote ->
            DiagnosticLog.record(
                context,
                "SHELL_FILE",
                "upload=direct; ext=${attachment.name.substringAfterLast('.', "")}; mime=${attachment.mime}; bytes=${attachment.bytes.size}"
            )
            return ShellTransportedAttachment(
                remoteId = remote.id,
                originalName = attachment.name,
                transportName = remote.name ?: attachment.name,
                encodedFallback = false
            )
        }

        val directError = direct.exceptionOrNull() ?: error("Не удалось загрузить ${attachment.name}")
        if (!isUnsupportedShellFileType(directError)) throw directError

        val encoded = ShellAttachmentEnvelope.encode(
            originalName = attachment.name,
            mimeType = attachment.mime,
            bytes = attachment.bytes,
            index = index
        )
        DiagnosticLog.record(
            context,
            "SHELL_FILE",
            "upload=base64-fallback; ext=${attachment.name.substringAfterLast('.', "")}; mime=${attachment.mime}; bytes=${attachment.bytes.size}; transport=${encoded.transportName}"
        )
        val remote = filesClient.upload(
            apiKey,
            encoded.transportName,
            "text/plain",
            encoded.bytes,
            baseUrl
        )
        return ShellTransportedAttachment(
            remoteId = remote.id,
            originalName = attachment.name,
            transportName = encoded.transportName,
            encodedFallback = true
        )
    }

    private fun isUnsupportedShellFileType(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("File type is not allowed", ignoreCase = true)
    }

    private fun uriBytes(uri: Uri): UriData {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val name = displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать $name")
        return UriData(name, mime, bytes)
    }

    private fun videoReference(uri: Uri): OpenRouterVideoClient.Reference {
        val data = uriBytes(uri)
        val b64 = Base64.encodeToString(data.bytes, Base64.NO_WRAP)
        val type = when {
            data.mime.startsWith("audio/") -> "audio"
            data.mime.startsWith("video/") -> "video"
            else -> "image"
        }
        return OpenRouterVideoClient.Reference(type, "data:${data.mime};base64,$b64")
    }

    private fun saveGeneratedAudio(result: OpenRouterAudioClient.SpeechResult): GeneratedFile {
        val pcm = result.format.equals("pcm", ignoreCase = true) || result.mimeType.equals("audio/pcm", ignoreCase = true)
        val bytes = if (pcm) {
            OpenRouterAudioClient.pcmToWav(
                pcm = result.bytes,
                sampleRateHz = result.sampleRateHz ?: 24_000,
                channels = result.channels ?: 1
            )
        } else result.bytes
        val format = if (pcm) "wav" else result.format
        val mime = if (pcm) "audio/wav" else result.mimeType
        return saveGeneratedBinary("umnik_speech_${System.currentTimeMillis()}.$format", mime, bytes)
    }

    private fun saveGeneratedBinary(nameRaw: String, mime: String, bytes: ByteArray): GeneratedFile {
        val safe = nameRaw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
            .take(120)
            .ifBlank { "umnik_file_${System.currentTimeMillis()}.bin" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}_$safe").apply { writeBytes(bytes) }
        return GeneratedFile(UUID.randomUUID().toString(), safe, mime, file.absolutePath, file.length())
    }
}
