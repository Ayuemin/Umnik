package com.ayuemin.ymnik.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import com.ayuemin.ymnik.OpenRouterRecoveryWorker
import com.ayuemin.ymnik.RequestCostKind
import com.ayuemin.ymnik.RequestExecutionManager
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.PendingAttachment
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenRouterClient(
    private val context: Context,
    private val requestId: String? = null,
    private val requestChatId: String? = null,
    private val requestProfileId: String? = null,
    private val recoveryEnabled: Boolean = false,
    private val streamCallback: (String) -> Unit = {},
    private val phaseCallback: (String) -> Unit = {},
    private val costSink: ((RequestCostKind, String?) -> Unit)? = null
) {
    private val gson = Gson()
    private val featurePrefs by lazy { OpenRouterFeaturePrefs(context.applicationContext) }
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter", requestId, requestChatId))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter") }
        .retryOnConnectionFailure(true)
        // Keep OkHttp defaults: negotiate HTTP/2 when available and fall back to HTTP/1.1.
        // No active ping is configured; SSE traffic itself keeps long responses active.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()
    private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId, requestChatId)
    private val recoveryStore by lazy { OpenRouterRecoveryStore(context.applicationContext) }
    private val activeCallLock = Any()
    @Volatile private var activeCall: Call? = null

    private enum class GenerationState { PENDING, COMPLETED, CANCELLED, TERMINAL_FAILURE }

    data class Result(
        val text: String,
        val files: List<GeneratedFile>,
        val modelId: String? = null,
        val providerName: String? = null,
        val costUsd: Double? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null
    )

    data class KeyUsage(
        val daily: Double,
        val weekly: Double,
        val monthly: Double,
        val total: Double
    )

    fun cancelActiveRequest() {
        chatBatchRunner.stopTracking()
        requestId?.takeIf { it.isNotBlank() }?.let { id ->
            recoveryStore.remove(id)
            OpenRouterRecoveryWorker.cancel(context, id)
        }
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }


    private fun executeActive(request: Request): okhttp3.Response {
        val call = http.newCall(request)
        synchronized(activeCallLock) { activeCall = call }
        return call.execute()
    }

    private fun clearActiveCall() {
        synchronized(activeCallLock) { activeCall = null }
    }

    suspend fun models(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, endpoint(baseUrl, "models"))
    }

    suspend fun imageModels(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, endpoint(baseUrl, "images/models"))
    }

    suspend fun keyUsage(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): KeyUsage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "key"))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonObject("data") ?: error("OpenRouter не вернул статистику ключа")
            fun value(name: String): Double = runCatching {
                data.get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
            }.getOrDefault(0.0)
            KeyUsage(
                daily = value("usage_daily"),
                weekly = value("usage_weekly"),
                monthly = value("usage_monthly"),
                total = value("usage")
            )
        }
    }

    private fun getModelInfos(apiKey: String, url: String): List<ModelInfo> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            return root.getAsJsonArray("data")
                ?.mapNotNull(OpenRouterModelCatalog::parse)
                ?.distinctBy { it.id }
                ?.sortedBy { it.id }
                ?: emptyList()
        }
    }

    suspend fun chat(
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String,
        webSearchEnabled: Boolean = false,
        reasoningEnabled: Boolean = false,
        reasoningEffort: String? = "medium",
        toolsEnabled: Boolean = true,
        baseUrl: String = DEFAULT_BASE_URL,
        modelInfo: ModelInfo? = null,
        streamToUi: Boolean = false,
        webSearchPreset: WebSearchPreset = WebSearchPreset.ON_DEMAND,
        requestImageOutput: Boolean = false,
        knowledgeSearch: (suspend (String) -> String)? = null,
        knowledgeSearchLimit: Int = 4
    ): Result = withContext(Dispatchers.IO) {
        val selectedHistory = ConversationContext.select(
            history, systemPrompt, prompt, ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength, 0
        )
        val messages = JsonArray()
        messages.add(message("system", systemPrompt))
        selectedHistory.forEach { item ->
            messages.add(message(item.role, item.text))
        }
        messages.add(userMessage(prompt, attachments))
        DiagnosticLog.record(context, "CONTEXT", "OpenRouter model=$model; stored=${history.size}; sent=${selectedHistory.size}; window=${modelInfo?.contextLength ?: "provider"}; output=provider; attachments=${attachments.size}")

        val created = mutableListOf<GeneratedFile>()
        val knowledgeBudget = KnowledgeToolBudget(knowledgeSearchLimit)
        val effectiveKnowledgeSearchLimit = knowledgeBudget.limit
        val maxToolLoops = maxOf(5, effectiveKnowledgeSearchLimit + 3)
        val requestRunId = UUID.randomUUID().toString()
        var loops = 0
        while (loops++ < maxToolLoops) {
            val payload = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                if (requestImageOutput && modelInfo?.outputs("image") == true) {
                    add("modalities", JsonArray().apply {
                        add("image")
                        if (modelInfo.outputs("text")) add("text")
                    })
                }
                add("metadata", JsonObject().apply {
                    addProperty("umnik_request_id", requestRunId)
                    addProperty("umnik_step", loops.toString())
                })
                val mergedTools = JsonArray()
                if (toolsEnabled) tools().forEach(mergedTools::add)
                if (knowledgeSearch != null && effectiveKnowledgeSearchLimit > 0) {
                    mergedTools.add(knowledgeSearchTool())
                }
                if (webSearchEnabled) {
                    if (modelInfo?.supportsTools == false) {
                        error("Выбранная модель не поддерживает современный веб-поиск OpenRouter")
                    }
                    val saved = featurePrefs.tools()
                    val searchSettings = ServerToolSettings(
                        webSearch = WebSearchMode.AUTO,
                        webSearchPreset = webSearchPreset,
                        webSearchEngine = saved.webSearchEngine
                    )
                    OpenRouterFeaturePayload.chatServerTools(searchSettings).forEach(mergedTools::add)
                    OpenRouterFeaturePayload.applyServerToolBudget(this, searchSettings)
                }
                if (mergedTools.size() > 0) add("tools", mergedTools)

                if (reasoningEnabled) {
                    add("reasoning", JsonObject().apply {
                        addProperty("enabled", true)
                        reasoningEffort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }
                        addProperty("exclude", true)
                    })
                } else if (modelInfo?.reasoningMandatory == true) {
                    add("reasoning", JsonObject().apply {
                        if ("low" in modelInfo.reasoningEfforts) addProperty("effort", "low")
                        addProperty("exclude", true)
                    })
                } else if (modelInfo?.supportsReasoning == true || modelInfo?.reasoningDefaultEnabled == true ||
                    model.startsWith("deepseek/deepseek-v4", ignoreCase = true)
                ) {
                    add("reasoning", JsonObject().apply { addProperty("effort", "none") })
                }
            }
            if (streamToUi) streamCallback("")
            val completion = requestCompletion(
                apiKey, baseUrl, payload, allowEmpty = created.isNotEmpty(), streamToUi = streamToUi
            )
            costSink?.invoke(RequestCostKind.PRIMARY, completion.costUsdExact)
            val responseMessage = completion.message
            created += generatedImagesFromMessage(responseMessage)
            val toolCalls = responseMessage.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
            if (toolCalls == null || toolCalls.size() == 0) {
                val content = extractText(responseMessage.get("content"))
                if (content.isBlank() && created.isEmpty()) {
                    error("Модель не вернула готовый текст. Измените уровень рассуждения или повторите запрос; пустой ответ не сохранён в чат.")
                }
                return@withContext Result(
                    text = content,
                    files = created,
                    modelId = completion.model.ifBlank { model },
                    providerName = completion.provider.takeIf { it.isNotBlank() },
                    costUsd = completion.costUsd,
                    inputTokens = completion.promptTokens,
                    outputTokens = completion.completionTokens
                )
            }

            phaseCallback("Выполняю инструменты…")
            messages.add(responseMessage.deepCopy())
            for (callElement in toolCalls) {
                val call = callElement.asJsonObject
                val callId = call.get("id")?.asString ?: UUID.randomUUID().toString()
                val function = call.getAsJsonObject("function")
                val name = function?.get("name")?.asString.orEmpty()
                val argsRaw = function?.get("arguments")?.asString ?: "{}"
                val resultText = when (name) {
                    "create_file" -> runCatching {
                        val args = gson.fromJson(argsRaw, JsonObject::class.java)
                        val file = createGeneratedTextFile(
                            args.get("filename")?.asString ?: "result.txt",
                            args.get("content")?.asString.orEmpty(),
                            args.get("mime_type")?.asString ?: "text/plain"
                        )
                        created += file
                        gson.toJson(mapOf("ok" to true, "filename" to file.name, "size" to file.size))
                    }.getOrElse {
                        gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Ошибка создания файла")))
                    }
                    "knowledge_search" -> {
                        val callback = knowledgeSearch
                        if (callback == null) {
                            gson.toJson(mapOf("ok" to false, "error" to "База знаний недоступна в этом запросе"))
                        } else {
                            runCatching {
                                val args = gson.fromJson(argsRaw, JsonObject::class.java)
                                val query = args.get("query")?.asString.orEmpty()
                                val beforeCalls = knowledgeBudget.usedCalls
                                val result = knowledgeBudget.execute(query) { cleanQuery ->
                                    callback(cleanQuery)
                                }
                                if (knowledgeBudget.usedCalls > beforeCalls) {
                                    DiagnosticLog.record(
                                        context,
                                        "KNOWLEDGE_TOOL",
                                        "autonomous call=${knowledgeBudget.usedCalls}/$effectiveKnowledgeSearchLimit"
                                    )
                                }
                                gson.toJson(mapOf("ok" to true, "result" to result))
                            }.getOrElse {
                                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Ошибка поиска по базе знаний")))
                            }
                        }
                    }
                    else -> gson.toJson(mapOf("ok" to false, "error" to "Неизвестный инструмент: $name"))
                }
                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", callId)
                    addProperty("content", resultText)
                })
            }
        }
        Result("Модель слишком много раз вызывала инструменты. Операция остановлена.", created)
    }

    /**
     * Minimal text-only call for Umnik's internal service tasks.
     * Deliberately bypasses every user-facing tool/web feature so a System Model
     * never requires provider tool-use support just to plan retrieval or summarize memory.
     */
    suspend fun internalText(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        baseUrl: String = DEFAULT_BASE_URL,
        modelInfo: ModelInfo? = null
    ): Result = withContext(Dispatchers.IO) {
        val messages = JsonArray().apply {
            add(message("system", systemPrompt))
            add(message("user", prompt))
        }
        DiagnosticLog.record(
            context,
            "CONTEXT",
            "OpenRouter internal model=$model; stored=0; sent=0; window=${modelInfo?.contextLength ?: "provider"}; tools=0; web=off"
        )
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            add("metadata", JsonObject().apply {
                addProperty("umnik_internal", "true")
            })
            if (modelInfo?.reasoningMandatory == true) {
                add("reasoning", JsonObject().apply {
                    if ("low" in modelInfo.reasoningEfforts) addProperty("effort", "low")
                    addProperty("exclude", true)
                })
            } else if (
                modelInfo?.supportsReasoning == true ||
                modelInfo?.reasoningDefaultEnabled == true ||
                model.startsWith("deepseek/deepseek-v4", ignoreCase = true) ||
                model.startsWith("~deepseek/deepseek-v4", ignoreCase = true)
            ) {
                add("reasoning", JsonObject().apply {
                    addProperty("effort", "none")
                    addProperty("exclude", true)
                })
            }
        }
        val completion = requestCompletion(
            apiKey = apiKey,
            baseUrl = baseUrl,
            payload = payload,
            allowEmpty = false,
            streamToUi = false
        )
        costSink?.invoke(RequestCostKind.SYSTEM, completion.costUsdExact)
        val content = extractText(completion.message.get("content"))
        if (content.isBlank()) error("Системная модель не вернула текст")
        Result(
            text = content,
            files = emptyList(),
            modelId = completion.model.ifBlank { model },
            providerName = completion.provider.takeIf { it.isNotBlank() },
            costUsd = completion.costUsd,
            inputTokens = completion.promptTokens,
            outputTokens = completion.completionTokens
        )
    }

    suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>,
        baseUrl: String = DEFAULT_BASE_URL,
        aspectRatio: String? = null,
        resolution: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt.ifBlank { "Создай вариант приложенного изображения." })
            aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
            resolution?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }

            val references = JsonArray()
            var attachmentBytes = 0L
            attachments.filter { it.mimeType.startsWith("image/") }.forEach { attachment ->
                val remaining = MAX_TOTAL_ATTACHMENT_BYTES - attachmentBytes
                require(remaining > 0L) { ATTACHMENT_LIMIT_MESSAGE }
                val bytes = readAttachment(attachment, remaining)
                attachmentBytes += bytes.size
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                references.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${attachment.mimeType};base64,$b64")
                    })
                })
            }
            if (references.size() > 0) add("input_references", references)
        }

        val request = Request.Builder()
            .url(endpoint(baseUrl, "images"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        try {
            executeActive(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val data = root.getAsJsonArray("data") ?: error("OpenRouter не вернул изображение")
                val files = data.mapIndexedNotNull { index, element ->
                    if (!element.isJsonObject) return@mapIndexedNotNull null
                    val item = element.asJsonObject
                    val encoded = item.get("b64_json")?.asString?.takeIf { it.isNotBlank() }
                        ?: return@mapIndexedNotNull null
                    val mime = item.get("media_type")?.asString?.takeIf { it.isNotBlank() } ?: "image/png"
                    saveGeneratedImage(encoded, mime, index)
                }
                if (files.isEmpty()) error("OpenRouter вернул ответ без данных изображения")
                val usage = root.getAsJsonObject("usage")
                val costExact = runCatching {
                    (usage?.get("cost")?.takeUnless { it.isJsonNull }
                        ?: root.get("cost")?.takeUnless { it.isJsonNull })
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
                val costUsd = costExact?.toDoubleOrNull()
                costSink?.invoke(RequestCostKind.PRIMARY, costExact)
                val promptTokens = usage?.get("prompt_tokens")?.takeUnless { it.isJsonNull }?.asInt
                val completionTokens = usage?.get("completion_tokens")?.takeUnless { it.isJsonNull }?.asInt
                Result(
                    text = "Изображение создано.",
                    files = files,
                    modelId = model,
                    costUsd = costUsd,
                    inputTokens = promptTokens,
                    outputTokens = completionTokens
                )
            }
        } finally {
            clearActiveCall()
        }
    }

    private suspend fun requestCompletion(
        apiKey: String,
        baseUrl: String,
        payload: JsonObject,
        allowEmpty: Boolean,
        streamToUi: Boolean = false
    ): OpenRouterResponseParser.Completion {
        val model = payload.get("model")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (model.endsWith(":batch", ignoreCase = true)) {
            return chatBatchRunner.complete(apiKey, baseUrl, payload)
        }
        val requestsImageOutput = payload.get("modalities")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.any { it.isJsonPrimitive && it.asString.equals("image", ignoreCase = true) } == true
        val requestPayload = payload.deepCopy().apply {
            addProperty("stream", !requestsImageOutput)
            add("usage", JsonObject().apply { addProperty("include", true) })
            if (!requestsImageOutput) {
                add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
            }
        }
        val payloadJson = gson.toJson(requestPayload)
        val recoveryRecord = recoveryRecord(apiKey, baseUrl, model)
        recoveryRecord?.let { record ->
            recoveryStore.put(record)
            // Persist the fallback before network I/O. If Android kills the process before
            // response headers arrive, WorkManager can still close the pending state safely.
            OpenRouterRecoveryWorker.schedule(context, record.requestId, initialDelaySeconds = 120L)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            .post(payloadJson.toRequestBody("application/json".toMediaType()))
            .build()

        var recoveryAttempt = 0
        while (true) {
            var generationId: String? = null
            var cacheStatus: String? = null
            try {
                phaseCallback(
                    if (recoveryAttempt == 0) "Запрос отправлен · модель отвечает…" else "Забираю восстановленный ответ…"
                )
                executeActive(request).use { response ->
                    generationId = response.header("X-Generation-Id")
                    cacheStatus = response.header("X-OpenRouter-Cache-Status")
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        // Persist every accepted generation. Recovery only performs GET requests;
                        // it never repeats this paid POST, regardless of cache status.
                        recoveryStore.updateGeneration(recoveryRecord.requestId, generationId.orEmpty(), cacheStatus)
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, replaceExisting = true)
                    }
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        clearRecovery(recoveryRecord)
                        error(apiError(response.code, body))
                    }
                    phaseCallback(
                        if (cacheStatus.equals("HIT", ignoreCase = true))
                            "Готовый ответ найден · загружаю…"
                        else
                            "Модель формирует ответ…"
                    )
                    // Do not clear recovery state until the whole SSE/body has arrived.
                    val responseBody = response.body ?: error("OpenRouter вернул ответ без тела")
                    val completion = if (
                        response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
                    ) {
                        var streamAnnounced = false
                        val preview = StringBuilder()
                        var lastPreviewAt = 0L
                        var lastPreviewLength = 0
                        OpenRouterStreamParser.parse(responseBody.source(), allowEmpty) { delta ->
                            if (streamToUi && delta.isNotEmpty()) {
                                preview.append(delta)
                                val now = SystemClock.elapsedRealtime()
                                val shouldPublish = lastPreviewLength == 0 ||
                                    now - lastPreviewAt >= STREAM_PREVIEW_INTERVAL_MS ||
                                    preview.length - lastPreviewLength >= STREAM_PREVIEW_MIN_CHARS
                                if (shouldPublish) {
                                    if (!streamAnnounced) {
                                        streamAnnounced = true
                                        phaseCallback("Получаю ответ…")
                                    }
                                    streamCallback(preview.toString())
                                    lastPreviewAt = now
                                    lastPreviewLength = preview.length
                                }
                            }
                        }.also { parsed ->
                            if (streamToUi) {
                                val finalText = extractText(parsed.message.get("content"))
                                if (finalText.isNotBlank() && finalText.length != lastPreviewLength) {
                                    if (!streamAnnounced) phaseCallback("Получаю ответ…")
                                    streamCallback(finalText)
                                }
                            }
                        }
                    } else {
                        // Defensive compatibility path for an endpoint that ignores stream=true.
                        OpenRouterResponseParser.parse(responseBody.string(), allowEmpty)
                    }
                    clearRecovery(recoveryRecord)
                    DiagnosticLog.record(
                        context,
                        "COMPLETION",
                        "OpenRouter id=${completion.id}; provider=${completion.provider}; finish=${completion.finishReason}; nativeFinish=${completion.nativeFinishReason}; completionTokens=${completion.completionTokens}; reasoningTokens=${completion.reasoningTokens}; cache=${cacheStatus ?: "off"}"
                    )
                    return completion
                }
            } catch (error: IOException) {
                val locallyCancelled = synchronized(activeCallLock) { activeCall?.isCanceled() == true }
                val recover = shouldRecoverOpenRouterBodyFailure(
                    locallyCancelled = locallyCancelled,
                    generationId = generationId,
                    recoveryAttempt = recoveryAttempt
                )
                DiagnosticLog.record(
                    context,
                    "REQUEST_RECOVERY",
                    "body failure; localCancel=$locallyCancelled; generation=${generationId ?: "none"}; cache=${cacheStatus ?: "off"}; recover=$recover; attempt=$recoveryAttempt; error=${error::class.java.simpleName}: ${error.message}"
                )
                if (!recover) {
                    if (!locallyCancelled && recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Связь нестабильна · продолжу восстановление в фоне…")
                        // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }

                clearActiveCall()
                phaseCallback("Связь прервалась · жду сеть…")
                val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
                if (!awaitNetworkAvailable(deadline)) {
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Сеть недоступна · продолжу восстановление в фоне…")
                        // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }
                phaseCallback("Связь доступна · проверяю готовый ответ…")
                val generationState = if (cacheStatus.equals("HIT", ignoreCase = true)) {
                    GenerationState.COMPLETED
                } else {
                    awaitGenerationState(apiKey, baseUrl, generationId.orEmpty(), deadline)
                }
                when (generationState) {
                    GenerationState.CANCELLED -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation cancelled; stopping recovery id=${generationId ?: "none"}")
                        phaseCallback("OpenRouter отменил генерацию · запрос остановлен")
                        clearRecovery(recoveryRecord)
                        throw IOException("OpenRouter отменил генерацию после обрыва связи. Повторите запрос.")
                    }
                    GenerationState.TERMINAL_FAILURE -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation cannot be recovered; stopping id=${generationId ?: "none"}")
                        clearRecovery(recoveryRecord)
                        throw IOException("Не удалось безопасно восстановить генерацию OpenRouter. Повторите запрос.")
                    }
                    GenerationState.PENDING -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation still pending after live recovery window; handing off id=${generationId ?: "none"}")
                        if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                            phaseCallback("Ответ ещё формируется · продолжу восстановление в фоне…")
                            // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                            throw error
                        }
                        clearRecovery(recoveryRecord)
                        throw error
                    }
                    GenerationState.COMPLETED -> Unit
                }
                // The generation is complete, but repeating the original POST can still be billed
                // before a cache HIT/MISS header is known. Hand the existing generation to the
                // read-only worker, which may retrieve /generation/content without a second request.
                phaseCallback("Ответ готов · забираю сохранённый результат…")
                throw error
            } finally {
                clearActiveCall()
            }
        }
    }

    private fun recoveryRecord(apiKey: String, baseUrl: String, model: String): OpenRouterRecoveryRecord? {
        if (!recoveryEnabled) return null
        val id = requestId?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = RequestExecutionManager.snapshotForRequest(id) ?: return null
        val chatId = requestChatId?.takeIf { it.isNotBlank() } ?: snapshot.chatId
        // Only a direct user-facing request may be delivered automatically after process death.
        // Orchestrator worker calls have their own continuation graph and must not be injected here.
        if (chatId != snapshot.chatId) return null
        val profileId = requestProfileId?.takeIf { it.isNotBlank() } ?: return null
        return OpenRouterRecoveryRecord(
            requestId = id,
            chatId = chatId,
            messageId = snapshot.messageId,
            connectionProfileId = profileId,
            apiKeyFingerprint = openRouterApiKeyFingerprint(apiKey),
            baseUrl = baseUrl,
            modelId = model
        )
    }

    private fun clearRecovery(record: OpenRouterRecoveryRecord?) {
        val id = record?.requestId ?: return
        recoveryStore.remove(id)
        OpenRouterRecoveryWorker.cancel(context, id)
    }

    private suspend fun awaitNetworkAvailable(deadlineElapsed: Long): Boolean {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return true
            delay(1_000L)
        }
        return false
    }

    private suspend fun awaitGenerationState(
        apiKey: String,
        baseUrl: String,
        generationId: String,
        deadlineElapsed: Long = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
    ): GenerationState {
        if (generationId.isBlank()) return GenerationState.TERMINAL_FAILURE
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            if (!awaitNetworkAvailable(deadlineElapsed)) return GenerationState.PENDING
            if (attempt > 0) delay(minOf(10_000L, 1_500L + attempt * 1_000L))
            val request = Request.Builder()
                .url(endpoint(baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val state = try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> GenerationState.PENDING
                        response.code == 401 || response.code == 403 -> GenerationState.TERMINAL_FAILURE
                        !response.isSuccessful -> GenerationState.PENDING
                        else -> {
                            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                            val data = root.getAsJsonObject("data") ?: return@use GenerationState.PENDING
                            val cancelled = runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true
                            val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                            DiagnosticLog.record(
                                context,
                                "REQUEST_RECOVERY",
                                "generation poll id=${generationId.take(12)} http=${response.code} cancelled=$cancelled finish=${finish.ifBlank { "pending" }} poll=${attempt + 1}"
                            )
                            when {
                                cancelled -> GenerationState.CANCELLED
                                finish.isNotBlank() -> GenerationState.COMPLETED
                                else -> GenerationState.PENDING
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                GenerationState.PENDING
            }
            if (state != GenerationState.PENDING) {
                if (state == GenerationState.COMPLETED) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation completed; id=$generationId; poll=${attempt + 1}")
                }
                return state
            }
            attempt += 1
        }
        return GenerationState.PENDING
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun userMessage(text: String, attachments: List<PendingAttachment>): JsonObject {
        if (attachments.isEmpty()) return message("user", text)
        val parts = JsonArray()
        val fallbackText = if (attachments.isNotEmpty() && attachments.all { it.mimeType.startsWith("audio/") }) {
            "Ответь на голосовое сообщение."
        } else {
            "Изучи вложения и помоги мне с ними."
        }
        parts.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text.ifBlank { fallbackText })
        })
        var attachmentBytes = 0L
        attachments.forEach { attachment ->
            val remaining = MAX_TOTAL_ATTACHMENT_BYTES - attachmentBytes
            require(remaining > 0L) { ATTACHMENT_LIMIT_MESSAGE }
            val bytes = readAttachment(attachment, remaining)
            attachmentBytes += bytes.size
            when {
                attachment.mimeType.startsWith("image/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
                attachment.mimeType.startsWith("audio/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "input_audio")
                        add("input_audio", JsonObject().apply {
                            addProperty("data", b64)
                            addProperty("format", audioFormat(attachment))
                        })
                    })
                }
                attachment.mimeType.startsWith("video/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "video_url")
                        add("video_url", JsonObject().apply {
                            addProperty("url", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
                attachment.mimeType.startsWith("text/") ||
                    attachment.name.endsWith(".md", true) ||
                    attachment.name.endsWith(".json", true) ||
                    attachment.name.endsWith(".csv", true) ||
                    attachment.name.endsWith(".yaml", true) ||
                    attachment.name.endsWith(".yml", true) ||
                    attachment.name.endsWith(".xml", true) -> {
                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    parts.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "\n--- Вложение: ${attachment.name} ---\n$content\n--- Конец вложения ---")
                    })
                }
                else -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "file")
                        add("file", JsonObject().apply {
                            addProperty("filename", attachment.name)
                            addProperty("file_data", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
            }
        }
        return JsonObject().apply {
            addProperty("role", "user")
            add("content", parts)
        }
    }

    private fun tools() = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", "create_file")
                addProperty(
                    "description",
                    "Создать текстовый файл на устройстве пользователя. Вызывай ТОЛЬКО если пользователь в текущем запросе прямо просит файл/скачивание либо системная, командная или подключённая инструкция прямо требует вернуть результат файлом. Никогда не создавай файл автоматически только из-за длины ответа."
                )
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("filename", JsonObject().apply { addProperty("type", "string") })
                        add("content", JsonObject().apply { addProperty("type", "string") })
                        add("mime_type", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "Например text/markdown, text/plain, text/csv, text/html или application/json")
                        })
                    })
                    add("required", JsonArray().apply { add("filename"); add("content") })
                })
            })
        })
    }

    private fun knowledgeSearchTool() = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", "knowledge_search")
            addProperty(
                "description",
                "Искать в подключённой пользовательской базе знаний текущего чата или специалиста. Используй по необходимости, когда для текущей задачи полезны дополнительные факты, правила, требования, процедуры, примеры или другие сведения из базы. Не вызывай без необходимости и не повторяй одинаковый поиск."
            )
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("query", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Краткий смысловой поисковый запрос к базе знаний")
                    })
                })
                add("required", JsonArray().apply { add("query") })
            })
        })
    }

    private fun createGeneratedTextFile(nameRaw: String, content: String, mimeType: String): GeneratedFile {
        val name = safeName(nameRaw).ifBlank { "result.txt" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}_$name")
        file.writeText(content)
        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())
    }

    private fun generatedImagesFromMessage(message: JsonObject): List<GeneratedFile> {
        val images = message.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return images.mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) return@mapIndexedNotNull null
            val item = element.asJsonObject
            val dataUrl = item.getAsJsonObject("image_url")
                ?.get("url")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?: item.get("url")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            val encoded = dataUrl?.takeIf { it.startsWith("data:", ignoreCase = true) }
                ?: item.get("b64_json")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val mime = when {
                dataUrl?.startsWith("data:", ignoreCase = true) == true ->
                    dataUrl.substringAfter("data:").substringBefore(';').takeIf { it.contains('/') }
                        ?: "image/png"
                else -> item.get("media_type")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                    ?: "image/png"
            }
            saveGeneratedImage(encoded, mime, index)
        }
    }

    private fun saveGeneratedImage(encoded: String, mimeType: String, index: Int): GeneratedFile {
        val bytes = Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT)
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            else -> mimeType.substringAfter('/', "bin").substringBefore('+').lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val name = "umnik_image_${System.currentTimeMillis()}_${index + 1}.$extension"
        val file = File(dir, "${UUID.randomUUID()}_$name")
        file.writeBytes(bytes)
        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())
    }

    private fun readAttachment(
        attachment: PendingAttachment,
        maxBytes: Long = MAX_TOTAL_ATTACHMENT_BYTES
    ): ByteArray {
        require(maxBytes > 0L) { ATTACHMENT_LIMIT_MESSAGE }
        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (!file.exists()) error("Файл не найден: ${attachment.name}")
            require(file.length() <= maxBytes) { ATTACHMENT_LIMIT_MESSAGE }
            return file.inputStream().use { readLimited(it, maxBytes, attachment.name) }
        }
        val uri = Uri.parse(attachment.uri)
        return context.contentResolver.openInputStream(uri)?.use {
            readLimited(it, maxBytes, attachment.name)
        } ?: error("Не удалось прочитать ${attachment.name}")
    }

    private fun readLimited(input: InputStream, maxBytes: Long, name: String): ByteArray {
        val initialCapacity = minOf(maxBytes, 1024L * 1024L).toInt().coerceAtLeast(32)
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "$ATTACHMENT_LIMIT_MESSAGE Файл: $name" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }


    private fun audioFormat(attachment: PendingAttachment): String {
        val ext = attachment.name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")) return ext
        return when (attachment.mimeType.lowercase()) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/aac" -> "aac"
            else -> error("Формат аудио ${attachment.name} не поддерживается")
        }
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)

    private fun extractText(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) return content.asJsonArray.mapNotNull { part ->
            when {
                part.isJsonPrimitive -> part.asString
                part.isJsonObject -> part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            }
        }.joinToString("\n")
        return content.toString()
    }

    private fun apiError(code: Int, body: String): String {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        val message = runCatching { root?.getAsJsonObject("error")?.get("message")?.asString }.getOrNull()
        val guardrailSummary = runCatching {
            root?.getAsJsonObject("openrouter_metadata")
                ?.getAsJsonArray("pipeline")
                ?.mapNotNull { stage ->
                    stage.takeIf { it.isJsonObject }?.asJsonObject?.takeIf {
                        it.get("type")?.asString == "guardrail"
                    }?.get("summary")?.takeIf { it.isJsonPrimitive }?.asString
                }
                ?.firstOrNull()
        }.getOrNull()

        return when {
            !guardrailSummary.isNullOrBlank() -> "OpenRouter $code: ${message ?: "запрос заблокирован"}. $guardrailSummary"
            code == 403 && message?.contains("security policy", ignoreCase = true) == true ->
                "OpenRouter 403: запрос отклонён политикой безопасности OpenRouter или провайдера. Попробуйте другую модель; если ошибка повторится, проверьте Privacy / Guardrails в OpenRouter."
            else -> "OpenRouter $code: ${message ?: body.take(500)}"
        }
    }

    fun attachmentFromUri(uri: Uri): PendingAttachment {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return PendingAttachment(uri.toString(), name, mime, size)
    }

    private fun endpoint(baseUrl: String, path: String): String {
        val root = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return "$root/${path.trimStart('/')}"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 50L * 1024L * 1024L
        private const val ATTACHMENT_LIMIT_MESSAGE =
            "Суммарный размер прямых вложений в одном запросе ограничен 50 МБ. Большие документы добавьте в базу знаний."
        private const val RECOVERY_WINDOW_MS = 120_000L
        private const val STREAM_PREVIEW_INTERVAL_MS = 120L
        private const val STREAM_PREVIEW_MIN_CHARS = 96
    }

}
