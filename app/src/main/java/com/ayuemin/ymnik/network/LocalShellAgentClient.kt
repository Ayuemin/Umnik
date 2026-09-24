
package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.local.LocalShellEngine
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenRouter model loop whose tools execute on the Android device via [LocalShellEngine].
 * No user attachment is uploaded as a model attachment: the model sees only prompt text
 * and the results of local tool calls it requested.
 */
class LocalShellAgentClient(private val context: Context) {
    data class Result(
        val text: String,
        val model: String?,
        val turns: Int,
        val toolCalls: Int,
        val costUsd: Double?,
        val inputTokens: Int?,
        val outputTokens: Int?
    )

    data class Progress(
        val label: String,
        val turn: Int,
        val toolCalls: Int
    )

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "Local Shell Model"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "Local Shell Model") }
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        activeCall?.cancel()
    }

    suspend fun run(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        engine: LocalShellEngine,
        routing: ProviderRoutingSettings = ProviderRoutingSettings(),
        reasoningEnabled: Boolean = false,
        reasoningEffort: String? = null,
        onProgress: (Progress) -> Unit = {},
        baseUrl: String = DEFAULT_BASE_URL
    ): Result = withContext(Dispatchers.IO) {
        val messages = JsonArray().apply {
            add(message("system", systemPrompt))
            add(message("user", prompt))
        }

        val requestRunId = UUID.randomUUID().toString()
        var turn = 0
        var toolCalls = 0
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalCost = 0.0
        var costObserved = false
        var returnedModel: String? = null

        try {
            while (turn < MAX_TURNS) {
                turn += 1
                onProgress(Progress("Модель планирует следующий локальный шаг", turn, toolCalls))

                val payload = JsonObject().apply {
                    addProperty("model", model)
                    add("messages", messages)
                    add("tools", engine.toolDefinitions())
                    addProperty("tool_choice", if (turn == 1) "required" else "auto")
                    addProperty("stream", false)
                    add("metadata", JsonObject().apply {
                        addProperty("umnik_local_shell", "true")
                        addProperty("umnik_request_id", requestRunId)
                        addProperty("umnik_turn", turn.toString())
                    })
                    add("usage", JsonObject().apply { addProperty("include", true) })

                    if (reasoningEnabled) {
                        add("reasoning", JsonObject().apply {
                            addProperty("enabled", true)
                            reasoningEffort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }
                            addProperty("exclude", true)
                        })
                    }
                }
                OpenRouterFeaturePayload.applyRouting(payload, routing)

                val request = Request.Builder()
                    .url(endpoint(baseUrl, "chat/completions"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("X-Title", "Umnik Android Local Shell")
                    .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
                    .header("X-OpenRouter-Metadata", "enabled")
                    .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                    .build()

                val root = execute(request)
                returnedModel = root.string("model") ?: returnedModel
                val usage = root.getAsJsonObject("usage")
                totalInputTokens += usage?.int("prompt_tokens") ?: usage?.int("input_tokens") ?: 0
                totalOutputTokens += usage?.int("completion_tokens") ?: usage?.int("output_tokens") ?: 0
                (usage?.double("cost") ?: root.double("cost"))?.let {
                    totalCost += it
                    costObserved = true
                }

                val choice = root.getAsJsonArray("choices")
                    ?.firstOrNull()
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: error("OpenRouter не вернул choices для локального Shell")
                val assistant = choice.getAsJsonObject("message")
                    ?: error("OpenRouter не вернул message для локального Shell")

                val calls = assistant.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                if (calls == null || calls.size() == 0) {
                    val text = extractText(assistant.get("content")).trim()
                    if (text.isBlank()) {
                        error("Модель завершила локальный Shell без итогового текста")
                    }
                    DiagnosticLog.record(
                        context,
                        "LOCAL_SHELL_AGENT",
                        "done; turns=$turn; toolCalls=$toolCalls; input=$totalInputTokens; output=$totalOutputTokens"
                    )
                    return@withContext Result(
                        text = text,
                        model = returnedModel ?: model,
                        turns = turn,
                        toolCalls = toolCalls,
                        costUsd = totalCost.takeIf { costObserved },
                        inputTokens = totalInputTokens.takeIf { it > 0 },
                        outputTokens = totalOutputTokens.takeIf { it > 0 }
                    )
                }

                messages.add(assistant.deepCopy())
                for (element in calls) {
                    if (!element.isJsonObject) continue
                    if (toolCalls >= MAX_TOOL_CALLS) {
                        error("Локальный Shell достиг лимита $MAX_TOOL_CALLS вызовов инструментов")
                    }
                    val call = element.asJsonObject
                    val callId = call.string("id") ?: UUID.randomUUID().toString()
                    val function = call.getAsJsonObject("function")
                    val name = function?.string("name").orEmpty()
                    val args = function?.string("arguments") ?: "{}"
                    toolCalls += 1
                    onProgress(Progress("Локально: " + toolLabel(name), turn, toolCalls))
                    val resultText = engine.execute(name, args)
                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", callId)
                        addProperty("content", resultText)
                    })
                }
            }
            error("Локальный Shell достиг лимита $MAX_TURNS модельных шагов без завершения")
        } finally {
            activeCall = null
        }
    }

    private fun execute(request: Request): JsonObject {
        val call = http.newCall(request)
        activeCall = call
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            return gson.fromJson(body, JsonObject::class.java)
        }
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun extractText(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            return content.asJsonArray.mapNotNull { part ->
                when {
                    part.isJsonPrimitive -> part.asString
                    part.isJsonObject -> part.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                    else -> null
                }
            }.joinToString("\n")
        }
        return content.toString()
    }

    private fun toolLabel(name: String): String = when (name) {
        "local_list" -> "смотрю файлы"
        "local_read" -> "читаю файл"
        "local_search" -> "ищу по проекту"
        "local_write" -> "записываю файл"
        "local_replace" -> "исправляю файл"
        "local_command" -> "выполняю команду"
        "local_python" -> "запускаю Python"
        "local_archive" -> "работаю с архивом"
        "local_git" -> "работаю с Git"
        "local_fetch" -> "получаю данные из сети"
        "local_export" -> "готовлю результат"
        else -> name.ifBlank { "выполняю инструмент" }
    }

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.string("message") ?: root.string("message")
        }.getOrNull()
        return "OpenRouter HTTP $code: " + (detail ?: body.take(500))
    }

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

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
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val MAX_TURNS = 8
        private const val MAX_TOOL_CALLS = 16
    }
}
