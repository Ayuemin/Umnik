package com.ayuemin.ymnik.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.OpenRouterBackgroundWorker
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
import com.ayuemin.ymnik.model.RagSettings
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

data class OpenRouterHubState(
    val catalog: List<ModelInfo> = emptyList(),
    val routing: ProviderRoutingSettings = ProviderRoutingSettings(),
    val tools: ServerToolSettings = ServerToolSettings(),
    val rag: RagSettings = RagSettings(),
    val media: OpenRouterMediaSettings = OpenRouterMediaSettings(),
    val batches: List<BatchJob> = emptyList(),
    val videos: List<VideoJob> = emptyList(),
    val loading: Boolean = false,
    val operation: String? = null,
    val status: String? = null,
    val transcription: String = "",
    val shellResult: String = "",
    val speechFile: GeneratedFile? = null
)

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
            rag = featurePrefs.rag(),
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

    fun updateRouting(value: ProviderRoutingSettings) {
        featurePrefs.saveRouting(value)
        mutableState.value = mutableState.value.copy(routing = value, status = "Маршрутизация сохранена")
    }

    fun updateTools(value: ServerToolSettings) {
        featurePrefs.saveTools(value)
        mutableState.value = mutableState.value.copy(tools = value, status = "Инструменты OpenRouter сохранены")
    }

    fun updateRag(value: RagSettings) {
        val clean = value.copy(topK = value.topK.coerceIn(1, 30))
        featurePrefs.saveRag(clean)
        mutableState.value = mutableState.value.copy(rag = clean, status = "Настройки RAG сохранены")
    }

    fun updateMedia(value: OpenRouterMediaSettings) {
        featurePrefs.saveMedia(value)
        mutableState.value = mutableState.value.copy(media = value, status = "Модель сохранена")
    }

    fun useAsTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не является текстовой")
            return
        }
        if (model.isBatch) {
            val media = mutableState.value.media.copy(batchModel = model.id)
            featurePrefs.saveMedia(media)
            mutableState.value = mutableState.value.copy(media = media, status = "${model.id} выбрана только для пакетных задач")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectDefaultTextModel(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = "${model.id} выбрана для обычного чата")
    }

    fun toggleQuickTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) return
        if (model.isBatch) {
            mutableState.value = mutableState.value.copy(status = "Batch-модель нельзя добавить в быстрые")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.toggleQuickTextModelForConnection(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = "Список быстрых моделей обновлён")
    }

    fun useAsImageModel(model: ModelInfo) {
        if (ModelCategory.IMAGE !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не генерирует изображения")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectImageModel(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = "${model.id} выбрана для изображений")
    }

    fun assignModel(model: ModelInfo, category: ModelCategory) {
        when (category) {
            ModelCategory.TEXT -> {
                if (model.isBatch) {
                    val media = mutableState.value.media.copy(batchModel = model.id)
                    featurePrefs.saveMedia(media)
                    mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для пакетных задач")
                } else {
                    useAsTextModel(model)
                }
            }
            ModelCategory.IMAGE -> useAsImageModel(model)
            ModelCategory.VIDEO -> {
                val media = mutableState.value.media.copy(videoModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для видео")
            }
            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = model.id)
                featurePrefs.saveMedia(media)
                viewModel.setOpenRouterSpeechModel(model.id)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания")
            }
            ModelCategory.TRANSCRIPTION -> {
                val media = mutableState.value.media.copy(transcriptionModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для распознавания речи")
            }
            ModelCategory.EMBEDDINGS -> {
                val rag = mutableState.value.rag.copy(embeddingModel = model.id)
                featurePrefs.saveRag(rag)
                mutableState.value = mutableState.value.copy(rag = rag, status = "${model.id} назначена для поиска по документам")
            }
            ModelCategory.RERANK -> {
                val rag = mutableState.value.rag.copy(rerankModel = model.id)
                featurePrefs.saveRag(rag)
                mutableState.value = mutableState.value.copy(rag = rag, status = "${model.id} назначена для точной сортировки результатов")
            }
        }
    }

    fun clearAssignedModel(category: ModelCategory) {
        when (category) {
            ModelCategory.TEXT -> {
                val profile = openRouterProfile() ?: return
                viewModel.selectDefaultTextModel(profile.id, "openrouter/auto")
                mutableState.value = mutableState.value.copy(status = "Модель чата сброшена на OpenRouter Auto")
            }
            ModelCategory.IMAGE -> {
                viewModel.clearImageModel()
                mutableState.value = mutableState.value.copy(status = "Модель изображений снята")
            }
            ModelCategory.VIDEO -> {
                val media = mutableState.value.media.copy(videoModel = "")
                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель видео снята")
            }
            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = "")
                featurePrefs.saveMedia(media)
                viewModel.setOpenRouterSpeechModel("")
                mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания снята")
            }
            ModelCategory.TRANSCRIPTION -> {
                val media = mutableState.value.media.copy(transcriptionModel = "")
                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель распознавания снята")
            }
            ModelCategory.EMBEDDINGS -> {
                val rag = mutableState.value.rag.copy(embeddingModel = "")
                featurePrefs.saveRag(rag); mutableState.value = mutableState.value.copy(rag = rag, status = "Embedding-модель снята")
            }
            ModelCategory.RERANK -> {
                val rag = mutableState.value.rag.copy(rerankModel = "")
                featurePrefs.saveRag(rag); mutableState.value = mutableState.value.copy(rag = rag, status = "Rerank-модель снята")
            }
        }
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

        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Отправляю Batch…", status = null)
            runCatching {
                val appState = viewModel.state.value
                val chat = appState.chats.firstOrNull { it.id == appState.currentChatId }
                val project = chat?.projectId?.let { id -> appState.projects.firstOrNull { it.id == id } }
                val modelInfo = mutableState.value.catalog.firstOrNull { it.id == model }
                // Batch API не принимает обычные file/image parts. Текстовые файлы
                // конкретной задачи безопасно встраиваются только в её prompt.
                val fileContexts = withContext(Dispatchers.IO) { filesPerPrompt.map(::batchTextContext) }
                val system = buildSystemPrompt(chat, project)
                val requests = prompts.mapIndexed { index, prompt ->
                    val inlineFiles = fileContexts[index]
                    val promptWithFiles = if (inlineFiles.isBlank()) prompt else "$prompt\n\n===== ФАЙЛЫ ЭТОЙ ЗАДАЧИ =====\n$inlineFiles"
                    OpenRouterBatchClient.BatchRequest(
                        customId = "hub-${index + 1}-${UUID.randomUUID()}",
                        label = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Задание ${index + 1}",
                        body = batchBuilder.build(
                            model = model,
                            history = chat?.messages.orEmpty(),
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
                    chatId = chat?.id,
                    projectId = project?.id,
                    modelId = model,
                    baseModelId = model.removeSuffix(":batch"),
                    title = "Batch · ${requests.size} заданий",
                    status = snapshot.status,
                    items = if (snapshot.items.isNotEmpty()) snapshot.items else requests.map { BatchJobItem(it.customId, it.label) },
                    error = snapshot.error
                )
                batchRepository.upsert(job)
                appendHubUserMessage(chat?.id, "[Batch: ${requests.size}${if (totalFiles > 0) " · файлов: $totalFiles" else ""}]\n$input")
                OpenRouterBackgroundWorker.schedule(context, replace = false)
                job
            }.onSuccess { job ->
                mutableState.value = mutableState.value.copy(
                    batches = batchRepository.list(),
                    loading = false,
                    operation = null,
                    status = "Batch принят · ${job.remoteId}"
                )
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
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
                val chatId = viewModel.state.value.currentChatId
                val projectId = viewModel.state.value.chats.firstOrNull { it.id == chatId }?.projectId
                val job = VideoJob(
                    id = UUID.randomUUID().toString(),
                    remoteId = snapshot.id,
                    connectionProfileId = profile.id,
                    chatId = chatId,
                    projectId = projectId,
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
                appendHubUserMessage(chatId, "[Видео · ${model.substringAfterLast('/')} ]\n$prompt")
                OpenRouterBackgroundWorker.schedule(context, replace = false)
                job
            }.onSuccess { job ->
                mutableState.value = mutableState.value.copy(
                    videos = videoRepository.list(),
                    loading = false,
                    operation = null,
                    status = "Видео принято · ${job.remoteId}"
                )
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
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
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Распознаю аудио…", status = null)
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать аудио") }
                val format = uri.lastPathSegment?.substringAfterLast('.', "mp3") ?: "mp3"
                audioClient.transcribe(key, model, bytes, format, baseUrl = viewModel.connectionTextEndpoint(profile.id))
            }.onSuccess { result ->
                appendHubExchange(chatId, "[Распознавание речи]", result.text, emptyList())
                mutableState.value = mutableState.value.copy(loading = false, operation = null, transcription = result.text, status = "Расшифровка готова и добавлена в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
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
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Создаю аудио…", status = null)
            runCatching {
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = media.speechModel,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result.bytes, result.mimeType, result.format)
            }.onSuccess { file ->
                appendHubExchange(chatId, "[Озвучивание]\n$text", "Аудио готово: ${file.name}", listOf(file))
                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Аудио создано и добавлено в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
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
        val media = featurePrefs.media()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (media.speechModel.isBlank()) {
            viewModel.setOpenRouterSpeechModel("")
            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель озвучивания OpenRouter")
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Озвучиваю ответ через OpenRouter…", status = null)
            runCatching {
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = media.speechModel,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result.bytes, result.mimeType, result.format)
            }.onSuccess { file ->
                appendHubAssistantResult(
                    chatId = chatId,
                    assistantText = "Озвучка OpenRouter · ${media.speechModel.substringAfterLast('/')}",
                    files = listOf(file)
                )
                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Озвучка OpenRouter добавлена в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось озвучить ответ через OpenRouter")
            }
        }
    }

    fun runShell(promptRaw: String, attachments: List<Uri> = emptyList()) {
        DiagnosticLog.action(context, "shell_submit", "promptChars=${promptRaw.length}; attachments=${attachments.size}")
        val prompt = promptRaw.trim()
        val profile = openRouterProfile()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val currentModel = viewModel.state.value.currentChatTextModel ?: viewModel.state.value.textModel
        val model = currentModel.removeSuffix(":batch")
        if (prompt.isBlank()) { mutableState.value = mutableState.value.copy(status = "Введите задачу для Shell"); return }
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Shell выполняет задачу…", status = null)
            val uploadedIds = mutableListOf<String>()
            runCatching {
                attachments.take(10).forEach { uri ->
                    val attachment = withContext(Dispatchers.IO) { uriBytes(uri) }
                    val remote = filesClient.upload(key, attachment.name, attachment.mime, attachment.bytes, viewModel.connectionTextEndpoint(profile.id))
                    uploadedIds += remote.id
                }
                val appState = viewModel.state.value
                val chat = appState.chats.firstOrNull { it.id == appState.currentChatId }
                val project = chat?.projectId?.let { id -> appState.projects.firstOrNull { it.id == id } }
                val result = responsesClient.respond(
                    apiKey = key,
                    model = model,
                    history = chat?.messages.orEmpty(),
                    prompt = prompt,
                    systemPrompt = buildSystemPrompt(chat, project),
                    tools = mutableState.value.tools.copy(shell = true),
                    routing = mutableState.value.routing,
                    shellFileIds = uploadedIds,
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                val generated = result.shellArtifacts.mapNotNull { artifact ->
                    runCatching {
                        val bytes = filesClient.downloadContainerFile(key, artifact.containerId, artifact.fileId, viewModel.connectionTextEndpoint(profile.id))
                        saveGeneratedBinary(artifact.name ?: "shell_${artifact.fileId}.bin", "application/octet-stream", bytes)
                    }.getOrNull()
                }
                result to generated
            }.onSuccess { (result, generated) ->
                val chatId = viewModel.state.value.currentChatId
                appendHubExchange(chatId, "[Shell]\n$prompt", result.text, generated)
                mutableState.value = mutableState.value.copy(loading = false, operation = null, shellResult = result.text, status = if (generated.isEmpty()) "Shell завершил работу" else "Shell завершил работу · файлов: ${generated.size}")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Ошибка Shell")
            }
            uploadedIds.forEach { id -> scope.launch(Dispatchers.IO) { runCatching { filesClient.delete(key, id, viewModel.connectionTextEndpoint(profile.id)) } } }
        }
    }

    fun clearTransientResult() {
        mutableState.value = mutableState.value.copy(transcription = "", shellResult = "", speechFile = null, status = null)
    }

    private fun openRouterProfile(): ConnectionProfile? = viewModel.state.value.connectionProfiles.firstOrNull {
        it.type == ProviderType.OPENROUTER && it.id !in viewModel.state.value.disabledConnectionIds
    }

    private fun buildPersistentAttachments(
        chatFiles: List<com.ayuemin.ymnik.model.ChatFile>,
        projectFiles: List<com.ayuemin.ymnik.model.ProjectFile>,
        model: ModelInfo?
    ): List<PendingAttachment> = (chatFiles.map {
        PendingAttachment("chat://${it.id}", it.name, it.mimeType, it.size, it.localPath)
    } + projectFiles.map {
        PendingAttachment("project://${it.id}", it.name, it.mimeType, it.size, it.localPath)
    }).filter { item ->
        val mime = item.mimeType.lowercase()
        when {
            mime.startsWith("image/") -> model?.accepts("image") == true
            mime.startsWith("audio/") -> model?.accepts("audio") == true
            mime.startsWith("video/") -> model?.accepts("video") == true
            else -> true
        }
    }.distinctBy { it.localPath ?: it.uri }

    private fun buildSystemPrompt(
        chat: com.ayuemin.ymnik.model.ChatSession?,
        project: com.ayuemin.ymnik.model.Project?
    ): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        val appState = viewModel.state.value
        val profile = appState.userProfile
        val useProfile = !profile.isEmpty() && when (appState.userProfileScope) {
            UserProfileScope.OFF -> false
            UserProfileScope.PROJECTS -> project != null
            UserProfileScope.EVERYWHERE -> true
        }
        if (useProfile) {
            appendLine("\n===== КРАТКО О ПОЛЬЗОВАТЕЛЕ =====")
            if (profile.name.isNotBlank()) appendLine("Имя: ${profile.name}")
            if (profile.gender.isNotBlank()) appendLine("Пол: ${profile.gender}")
            if (profile.age.isNotBlank()) appendLine("Возраст: ${profile.age}")
            if (profile.occupation.isNotBlank()) appendLine("Род занятий: ${profile.occupation}")
            if (profile.note.isNotBlank()) appendLine("Предпочтение в общении: ${profile.note}")
            appendLine("Используй эти сведения только когда они полезны. Явный запрос и инструкции проекта важнее профиля.")
            appendLine("===== КОНЕЦ ПРОФИЛЯ =====")
        }
        if (project != null) {
            appendLine("\n===== ПРОЕКТ: ${project.name} =====")
            if (project.role.isNotBlank()) appendLine("Роль в проекте: ${project.role}")
            if (project.masterPrompt.isNotBlank()) {
                appendLine("Мастер-инструкция проекта (ВЫСШИЙ ПРИОРИТЕТ внутри проекта):")
                appendLine(project.masterPrompt)
            }
            appendLine("===== КОНЕЦ НАСТРОЕК ПРОЕКТА =====")
        }
        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {
            appendLine("\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")
            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }
            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")
        }
        val skillIds = appState.activeSkillIds + project?.skillIds.orEmpty()
        val skillText = skills.promptFor(skillIds)
        if (skillText.isNotBlank()) {
            appendLine("\n===== ПОДКЛЮЧЁННЫЕ НАВЫКИ =====")
            appendLine(skillText)
            appendLine("===== КОНЕЦ НАВЫКОВ =====")
        }
        if (project?.masterPrompt?.isNotBlank() == true) {
            appendLine("\nПеред отправкой ответа молча проверь результат по мастер-инструкции проекта. При конфликте: мастер-инструкция проекта, явный текущий запрос, настройки диалога, навыки, файлы, профиль.")
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

    private fun uriBytes(uri: Uri): UriData {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать $name")
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

    private fun saveGeneratedAudio(bytes: ByteArray, mime: String, format: String): GeneratedFile =
        saveGeneratedBinary("umnik_speech_${System.currentTimeMillis()}.$format", mime, bytes)

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
