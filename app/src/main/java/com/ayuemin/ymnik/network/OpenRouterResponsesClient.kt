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
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Responses"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Responses") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(900, TimeUnit.SECONDS)
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
        val shellArtifacts: List<ShellArtifact> = emptyList()
    )

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

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            val text = OpenRouterResponsesCodec.extractText(root)
            if (text.isBlank()) error("Модель не вернула готовый текст")
            val usage = root.getAsJsonObject("usage")
            val artifacts = OpenRouterResponsesCodec.collectShellArtifacts(root)
            DiagnosticLog.record(
                context,
                "RESPONSES",
                "id=${root.string("id")}; model=${root.string("model")}; shellFiles=${artifacts.size}; input=${usage?.int("input_tokens")}; output=${usage?.int("output_tokens")}"
            )
            Result(
                id = root.string("id"),
                text = text,
                model = root.string("model"),
                costUsd = usage?.double("cost"),
                inputTokens = usage?.int("input_tokens") ?: usage?.int("prompt_tokens"),
                outputTokens = usage?.int("output_tokens") ?: usage?.int("completion_tokens"),
                shellArtifacts = artifacts
            )
        }
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
    }
}

internal object OpenRouterResponsesCodec {
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
