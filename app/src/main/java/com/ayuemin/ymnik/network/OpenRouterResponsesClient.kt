package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenRouterResponsesClient(private val context: Context) {
    private val gson = Gson()
    @Volatile private var activeCall: okhttp3.Call? = null
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Responses"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Responses") }
        .retryOnConnectionFailure(false)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(900, TimeUnit.SECONDS)
        .build()

    private val shellHttp = http.newBuilder()
        .callTimeout(SHELL_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    data class ShellArtifact(
        val containerId: String,
        val fileId: String,
        val name: String? = null
    )

    data class Result(
        val id: String?,
        val text: String,
        val model: String?,
        val costUsd: Double? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val shellArtifacts: List<ShellArtifact> = emptyList(),
        val shellCalls: Int = 0
    )

    data class Progress(
        val label: String,
        val eventType: String,
        val responseId: String? = null,
        val shellStepDelta: Int = 0
    )

    fun cancelActive() {
        activeCall?.cancel()
    }

    suspend fun respond(
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        systemPrompt: String,
        tools: ServerToolSettings,
        routing: ProviderRoutingSettings = ProviderRoutingSettings(),
        shellFileIds: List<String> = emptyList(),
        sessionId: String? = null,
        forceToolUse: Boolean = false,
        onProgress: (Progress) -> Unit = {},
        baseUrl: String = DEFAULT_BASE_URL
    ): Result = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", model)
            if (systemPrompt.isNotBlank()) addProperty("instructions", systemPrompt)
            add("input", JsonArray().apply {
                history.forEach { message -> add(responseInput(message.role, message.text)) }
                add(responseInput("user", prompt))
            })
            addProperty("store", false)
            if (tools.shell) addProperty("stream", true)
            if (forceToolUse && tools.shell) addProperty("tool_choice", "required")
            sessionId?.takeIf { it.isNotBlank() }?.let { addProperty("session_id", it.take(256)) }
        }
        OpenRouterFeaturePayload.applyRouting(payload, routing)
        OpenRouterFeaturePayload.applyServerToolBudget(payload, tools)
        val serverTools = OpenRouterFeaturePayload.responsesServerTools(tools)
        if (shellFileIds.isNotEmpty()) attachShellFiles(serverTools, shellFileIds)
        if (serverTools.size() > 0) payload.add("tools", serverTools)

        val request = Request.Builder()
            .url(endpoint(baseUrl, "responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val call = (if (tools.shell) shellHttp else http).newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    error(apiError(response.code, body))
                }
                val contentType = response.header("Content-Type").orEmpty()
                var observedShellCalls = 0
                val root = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    val source = response.body?.source() ?: error("OpenRouter вернул пустой поток Responses")
                    OpenRouterResponsesCodec.readCompletedResponseStream(source, gson) { event ->
                        val type = OpenRouterResponsesCodec.eventType(event)
                        val itemType = OpenRouterResponsesCodec.eventItemType(event)
                        if (type.isNotBlank() && !type.endsWith(".delta")) {
                            DiagnosticLog.record(
                                context,
                                "RESPONSES_STREAM",
                                "event=$type${itemType?.let { "; item=$it" }.orEmpty()}"
                            )
                        }
                        OpenRouterResponsesCodec.shellProgress(event)?.let { progress ->
                            observedShellCalls += progress.shellStepDelta
                            onProgress(progress)
                        }
                    }
                } else {
                    val body = response.body?.string().orEmpty()
                    gson.fromJson(body, JsonObject::class.java)
                }
                resultFromRoot(
                    root = root,
                    observedShellCalls = maxOf(observedShellCalls, OpenRouterResponsesCodec.countShellCalls(root))
                )
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun resultFromRoot(root: JsonObject, observedShellCalls: Int = 0): Result {
        val status = root.string("status")
        if (status == "failed" || status == "cancelled" || status == "canceled") {
            val detail = OpenRouterResponsesCodec.responseFailure(root)
            error(detail ?: "OpenRouter отменил выполнение Shell")
        }
        val text = OpenRouterResponsesCodec.extractText(root)
        if (text.isBlank()) {
            val detail = OpenRouterResponsesCodec.responseFailure(root)
            error(detail ?: "Модель не вернула готовый текст")
        }
        val usage = root.getAsJsonObject("usage")
        val artifacts = OpenRouterResponsesCodec.collectShellArtifacts(root)
        DiagnosticLog.record(
            context,
            "RESPONSES",
            "id=${root.string("id")}; status=${status ?: "unknown"}; model=${root.string("model")}; shellCalls=$observedShellCalls; shellFiles=${artifacts.size}; input=${usage?.int("input_tokens")}; output=${usage?.int("output_tokens")}"
        )
        return Result(
            id = root.string("id"),
            text = text,
            model = root.string("model"),
            costUsd = usage?.double("cost"),
            inputTokens = usage?.int("input_tokens") ?: usage?.int("prompt_tokens"),
            outputTokens = usage?.int("output_tokens") ?: usage?.int("completion_tokens"),
            shellArtifacts = artifacts,
            shellCalls = observedShellCalls
        )
    }

    private fun attachShellFiles(tools: JsonArray, fileIds: List<String>) {
        val ids = fileIds.map(String::trim).filter(String::isNotBlank).distinct().take(20)
        if (ids.isEmpty()) return
        tools.forEach { element ->
            val tool = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (tool.string("type") != "openrouter:shell") return@forEach
            val parameters = tool.getAsJsonObject("parameters") ?: JsonObject().also { tool.add("parameters", it) }
            parameters.add("environment", JsonObject().apply {
                addProperty("type", "container_auto")
                add("file_ids", JsonArray().apply { ids.forEach(::add) })
            })
        }
    }

    private fun responseInput(role: String, text: String) = JsonObject().apply {
        addProperty("role", when (role) {
            "assistant" -> "assistant"
            "system" -> "system"
            else -> "user"
        })
        addProperty("content", text)
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.string("message") ?: root.string("message")
        }.getOrNull()
        return "OpenRouter Responses HTTP $code: ${detail ?: body.take(500)}"
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(name: String): Int? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        internal const val SHELL_CALL_TIMEOUT_MILLIS = 0L
    }
}

internal object OpenRouterResponsesCodec {
    fun readCompletedResponseStream(
        source: okio.BufferedSource,
        gson: Gson,
        onEvent: (JsonObject) -> Unit = {}
    ): JsonObject {
        var lastResponse: JsonObject? = null
        var terminalError: String? = null
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") continue

            val event = runCatching { gson.fromJson(data, JsonObject::class.java) }.getOrNull() ?: continue
            val type = primitiveString(event, "type").orEmpty()
            if (type.isNotBlank()) onEvent(event)

            event.get("response")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { lastResponse = it }

            if (
                type == "response.failed" ||
                type == "response.cancelled" ||
                type == "response.canceled" ||
                type == "error"
            ) {
                terminalError = eventError(event)
            }
            if (type == "response.completed") {
                return lastResponse ?: error("OpenRouter завершил Responses без итогового объекта")
            }
        }

        terminalError?.let { error(it) }
        val root = lastResponse ?: error("Поток OpenRouter Responses завершился без итогового ответа")
        val status = primitiveString(root, "status")
        if (status == "completed") return root
        val fallback = status?.let { "OpenRouter прервал выполнение Shell: $it" }
            ?: "OpenRouter прервал выполнение Shell"
        error(responseFailure(root) ?: fallback)
    }

    fun eventType(event: JsonObject): String =
        primitiveString(event, "type").orEmpty()

    fun eventItemType(event: JsonObject): String? =
        event.get("item")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { primitiveString(it, "type") }

    fun shellProgress(event: JsonObject): OpenRouterResponsesClient.Progress? {
        val type = eventType(event)
        if (type.isBlank()) return null
        val itemType = eventItemType(event)
        val responseId = event.get("response")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { primitiveString(it, "id") }

        val label = when {
            type == "response.created" -> "OpenRouter принял задачу"
            type == "response.in_progress" -> "Модель и Shell работают"
            type == "response.completed" -> "Завершаю задачу и получаю результат"
            type == "response.failed" || type == "response.cancelled" || type == "response.canceled" ->
                "OpenRouter остановил задачу"
            type.contains("reasoning", ignoreCase = true) -> "Модель анализирует задачу"
            itemType?.contains("shell", ignoreCase = true) == true &&
                type.endsWith(".added") -> "Shell начал новый этап"
            itemType?.contains("shell", ignoreCase = true) == true &&
                type.endsWith(".done") -> "Shell завершил этап"
            type.contains("shell", ignoreCase = true) -> "Shell выполняет команды"
            type.contains("output_text", ignoreCase = true) -> "Модель формирует итоговый ответ"
            itemType == "message" && type.endsWith(".added") -> "Модель формирует ответ"
            else -> return null
        }
        val shellStep = if (
            itemType?.contains("shell", ignoreCase = true) == true &&
            type.endsWith(".added")
        ) 1 else 0
        return OpenRouterResponsesClient.Progress(
            label = label,
            eventType = type,
            responseId = responseId,
            shellStepDelta = shellStep
        )
    }

    fun responseFailure(root: JsonObject): String? {
        root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { error ->
            primitiveString(error, "message")?.let { return it }
            primitiveString(error, "code")?.let { return it }
        }
        root.get("incomplete_details")?.takeIf { it.isJsonObject }?.asJsonObject?.let { details ->
            primitiveString(details, "reason")?.let { return it }
        }
        return primitiveString(root, "message")
    }

    private fun eventError(event: JsonObject): String {
        event.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { error ->
            primitiveString(error, "message")?.let { return it }
            primitiveString(error, "code")?.let { return it }
        }
        event.get("response")?.takeIf { it.isJsonObject }?.asJsonObject?.let { response ->
            responseFailure(response)?.let { return it }
        }
        return primitiveString(event, "message") ?: "OpenRouter отменил выполнение Shell"
    }

    fun extractText(root: JsonObject): String {
        root.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val parts = mutableListOf<String>()
        root.get("output")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { outputItem ->
            val item = outputItem.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (item.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "message") {
                item.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { contentItem ->
                    val content = contentItem.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val type = content.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                    if (type == "output_text" || type == "text") {
                        content.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() }
                            ?.let(parts::add)
                    }
                }
            }
        }
        return parts.joinToString("\n").trim()
    }

    fun countShellCalls(root: JsonElement): Int {
        val ids = linkedSetOf<String>()
        fun visit(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonArray -> element.asJsonArray.forEach(::visit)
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val type = primitiveString(obj, "type").orEmpty()
                    if (type.contains("shell", ignoreCase = true) && !type.contains("output", ignoreCase = true)) {
                        val id = primitiveString(obj, "id") ?: primitiveString(obj, "call_id")
                        if (!id.isNullOrBlank()) ids += id
                    }
                    obj.entrySet().forEach { (_, child) -> visit(child) }
                }
            }
        }
        visit(root)
        return ids.size
    }

    fun collectShellArtifacts(root: JsonElement): List<OpenRouterResponsesClient.ShellArtifact> {
        val results = linkedSetOf<OpenRouterResponsesClient.ShellArtifact>()
        walk(root, null, results)
        return results.toList()
    }

    private fun walk(
        element: JsonElement?,
        inheritedContainerId: String?,
        results: MutableSet<OpenRouterResponsesClient.ShellArtifact>
    ) {
        if (element == null || element.isJsonNull) return
        when {
            element.isJsonArray -> element.asJsonArray.forEach { walk(it, inheritedContainerId, results) }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val containerId = primitiveString(obj, "container_id")
                    ?: primitiveString(obj, "containerId")
                    ?: inheritedContainerId
                val id = primitiveString(obj, "id") ?: primitiveString(obj, "file_id")
                if (containerId != null && id?.startsWith("cfile_") == true) {
                    results += OpenRouterResponsesClient.ShellArtifact(
                        containerId = containerId,
                        fileId = id,
                        name = primitiveString(obj, "name")
                            ?: primitiveString(obj, "filename")
                            ?: primitiveString(obj, "path")?.substringAfterLast('/')
                    )
                }
                obj.entrySet().forEach { (_, child) -> walk(child, containerId, results) }
            }
        }
    }

    private fun primitiveString(obj: JsonObject, name: String): String? = runCatching {
        obj.get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
