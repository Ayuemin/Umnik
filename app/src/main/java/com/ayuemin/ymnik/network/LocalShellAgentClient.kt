
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
        maxTurns: Int = DEFAULT_MAX_TURNS,
        onProgress: (Progress) -> Unit = {},
        baseUrl: String = DEFAULT_BASE_URL
    ): Result = withContext(Dispatchers.IO) {
        var messages = JsonArray().apply {
            add(message("system", systemPrompt))
            add(message("user", prompt))
        }

        val requestRunId = UUID.randomUUID().toString()
        val safeMaxTurns = maxTurns.coerceAtLeast(1)
        val maxToolCalls = (safeMaxTurns.toLong() * 4L).coerceIn(MIN_TOOL_CALLS.toLong(), MAX_TOOL_CALLS.toLong()).toInt()
        var turn = 0
        var toolCalls = 0
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalCost = 0.0
        var costObserved = false
        var returnedModel: String? = null
        var lastToolSignature: String? = null
        var repeatedToolSignature = 0
        var lastCompactionTurn = -100

        try {
            while (turn < safeMaxTurns) {
                val shouldCompact = turn > 0 &&
                    turn < safeMaxTurns - 1 &&
                    turn - lastCompactionTurn >= MIN_TURNS_BETWEEN_COMPACTIONS &&
                    gson.toJson(messages).length >= CONTEXT_COMPACTION_TRIGGER_CHARS
                if (shouldCompact) {
                    turn += 1
                    onProgress(Progress("Сжимаю рабочий контекст", turn, toolCalls))
                    val compactRoot = compactContext(
                        apiKey = apiKey,
                        model = model,
                        prompt = prompt,
                        messages = messages,
                        routing = routing,
                        baseUrl = baseUrl,
                        requestRunId = requestRunId,
                        turn = turn
                    )
                    returnedModel = compactRoot.string("model") ?: returnedModel
                    val compactUsage = compactRoot.getAsJsonObject("usage")
                    totalInputTokens += compactUsage?.int("prompt_tokens") ?: compactUsage?.int("input_tokens") ?: 0
                    totalOutputTokens += compactUsage?.int("completion_tokens") ?: compactUsage?.int("output_tokens") ?: 0
                    (compactUsage?.double("cost") ?: compactRoot.double("cost"))?.let {
                        totalCost += it
                        costObserved = true
                    }
                    val compactChoice = compactRoot.getAsJsonArray("choices")
                        ?.firstOrNull()
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: error("OpenRouter не вернул результат сжатия контекста")
                    val checkpoint = extractText(compactChoice.getAsJsonObject("message")?.get("content")).trim()
                    if (checkpoint.isBlank()) error("Не удалось создать рабочий checkpoint Local Shell")
                    messages = JsonArray().apply {
                        add(message("system", systemPrompt))
                        add(message("user", prompt))
                        add(message("system", "===== СЖАТЫЙ РАБОЧИЙ CHECKPOINT =====\n" + checkpoint + "\n===== КОНЕЦ CHECKPOINT ====="))
                    }
                    lastCompactionTurn = turn
                    DiagnosticLog.record(
                        context,
                        "LOCAL_SHELL_CONTEXT",
                        "compacted; turn=$turn; checkpointChars=${checkpoint.length}; input=$totalInputTokens; output=$totalOutputTokens"
                    )
                    continue
                }

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
                    if (toolCalls >= maxToolCalls) {
                        error("Локальный Shell достиг лимита $maxToolCalls вызовов инструментов")
                    }
                    val call = element.asJsonObject
                    val callId = call.string("id") ?: UUID.randomUUID().toString()
                    val function = call.getAsJsonObject("function")
                    val name = function?.string("name").orEmpty()
                    val args = function?.string("arguments") ?: "{}"
                    toolCalls += 1
                    onProgress(Progress(toolLabel(name), turn, toolCalls))
                    val signature = name + "\n" + args.trim()
                    if (signature == lastToolSignature) repeatedToolSignature += 1 else {
                        lastToolSignature = signature
                        repeatedToolSignature = 1
                    }
                    val resultText = if (repeatedToolSignature >= 3) {
                        DiagnosticLog.record(context, "LOCAL_SHELL_WATCHDOG", "Repeated identical tool call blocked; tool=$name; turn=$turn")
                        gson.toJson(mapOf(
                            "ok" to false,
                            "warning" to "Одинаковое локальное действие повторено несколько раз. Оно не выполнено снова: пересмотри план и выбери следующий полезный шаг."
                        ))
                    } else {
                        engine.execute(name, args)
                    }
                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", callId)
                        addProperty("content", resultText)
                    })
                }
            }
            error("Локальный Shell достиг лимита $safeMaxTurns модельных шагов без завершения")
        } finally {
            activeCall = null
        }
    }

    private fun compactContext(
        apiKey: String,
        model: String,
        prompt: String,
        messages: JsonArray,
        routing: ProviderRoutingSettings,
        baseUrl: String,
        requestRunId: String,
        turn: Int
    ): JsonObject {
        val raw = gson.toJson(messages)
        val transcript = if (raw.length <= MAX_COMPACTION_SOURCE_CHARS) {
            raw
        } else {
            val head = raw.take(COMPACTION_HEAD_CHARS)
            val tail = raw.takeLast(MAX_COMPACTION_SOURCE_CHARS - COMPACTION_HEAD_CHARS)
            head + "\n... [середина журнала сокращена перед checkpoint] ...\n" + tail
        }
        val compactMessages = JsonArray().apply {
            add(message(
                "system",
                "Ты создаёшь точный рабочий checkpoint для продолжающейся задачи Local Shell. " +
                    "Не решай задачу заново и не добавляй новых предположений. Сохрани: что уже сделано, " +
                    "изменённые файлы, найденные ошибки, результаты проверок, важные решения, ограничения " +
                    "и конкретный следующий план. Пиши компактно, но не теряй данные, нужные для продолжения."
            ))
            add(message(
                "user",
                "Исходная задача пользователя:\n" + prompt +
                    "\n\nТекущий журнал работы:\n" + transcript
            ))
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", compactMessages)
            addProperty("stream", false)
            add("metadata", JsonObject().apply {
                addProperty("umnik_local_shell", "true")
                addProperty("umnik_context_compaction", "true")
                addProperty("umnik_request_id", requestRunId)
                addProperty("umnik_turn", turn.toString())
            })
            add("usage", JsonObject().apply { addProperty("include", true) })
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
        return execute(request)
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
        "local_list", "local_read", "local_search", "local_archive", "local_git", "local_fetch" -> "Изучаю проект"
        "local_write", "local_replace" -> "Исправляю файлы"
        "local_command", "local_python" -> "Запускаю проверки"
        "local_export" -> "Готовлю результат"
        else -> "Выполняю локальное действие"
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
        private const val DEFAULT_MAX_TURNS = 24
        private const val MIN_TOOL_CALLS = 64
        private const val MAX_TOOL_CALLS = 100_000
        private const val CONTEXT_COMPACTION_TRIGGER_CHARS = 140_000
        private const val MIN_TURNS_BETWEEN_COMPACTIONS = 8
        private const val MAX_COMPACTION_SOURCE_CHARS = 260_000
        private const val COMPACTION_HEAD_CHARS = 40_000
    }
}
