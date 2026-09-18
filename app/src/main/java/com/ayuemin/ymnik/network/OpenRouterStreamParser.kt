package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okio.BufferedSource
import java.io.IOException

/** Collects OpenRouter SSE chat-completion deltas into the same
 * Completion shape used by the non-streaming parser. Tool calls are assembled by
 * index and are executed only after the stream is complete. */
internal object OpenRouterStreamParser {
    private data class ToolCallBuffer(
        var id: String = "",
        var type: String = "function",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    fun parse(
        source: BufferedSource,
        allowEmpty: Boolean = false,
        onText: (String) -> Unit = {}
    ): OpenRouterResponseParser.Completion {
        val gson = Gson()
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallBuffer>()
        val eventData = mutableListOf<String>()
        var sawDone = false
        var id = ""
        var provider = ""
        var model = ""
        var finishReason = ""
        var nativeFinishReason = ""
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null
        var reasoningTokens: Int? = null
        var costUsd: Double? = null

        fun appendPiece(target: StringBuilder, piece: String) {
            if (piece.isEmpty()) return
            target.append(piece)
        }

        fun contentText(value: JsonElement?): String = when {
            value == null || value.isJsonNull -> ""
            value.isJsonPrimitive -> value.asString
            value.isJsonArray -> value.asJsonArray.mapNotNull { part ->
                when {
                    part.isJsonPrimitive -> part.asString
                    part.isJsonObject -> part.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                    else -> null
                }
            }.joinToString("")
            else -> ""
        }

        fun errorMessage(value: JsonElement): String = runCatching {
            if (value.isJsonObject) value.asJsonObject.get("message")?.asString.orEmpty().take(250)
            else value.toString().take(250)
        }.getOrDefault("поставщик не указал причину")

        fun mergeStable(current: String, incoming: String): String = when {
            incoming.isBlank() -> current
            current.isBlank() -> incoming
            current == incoming || current.endsWith(incoming) -> current
            else -> current + incoming
        }

        fun processEvent(): Boolean {
            if (eventData.isEmpty()) return false
            val data = eventData.joinToString("\n").trim()
            eventData.clear()
            if (data.isBlank()) return false
            if (data == "[DONE]") {
                sawDone = true
                return true
            }

            val root = try {
                gson.fromJson(data, JsonObject::class.java)
            } catch (error: Throwable) {
                throw IOException("OpenRouter вернул повреждённый SSE-фрагмент", error)
            } ?: throw IOException("OpenRouter вернул пустой SSE-фрагмент")

            root.get("error")?.takeUnless { it.isJsonNull }?.let {
                throw IOException("OpenRouter: ${errorMessage(it)}")
            }
            root.get("id")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { id = it }
            root.get("provider")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { provider = it }
            root.get("model")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { model = it }

            root.getAsJsonObject("usage")?.let { usage ->
                promptTokens = runCatching { usage.get("prompt_tokens")?.asInt }.getOrNull() ?: promptTokens
                completionTokens = runCatching { usage.get("completion_tokens")?.asInt }.getOrNull() ?: completionTokens
                totalTokens = runCatching { usage.get("total_tokens")?.asInt }.getOrNull() ?: totalTokens
                reasoningTokens = runCatching {
                    usage.getAsJsonObject("completion_tokens_details")?.get("reasoning_tokens")?.asInt
                }.getOrNull() ?: reasoningTokens
                costUsd = runCatching { usage.get("cost")?.asDouble }.getOrNull() ?: costUsd
            }
            costUsd = runCatching { root.get("cost")?.takeUnless { it.isJsonNull }?.asDouble }.getOrNull() ?: costUsd

            val choice = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return false
            choice.get("error")?.takeUnless { it.isJsonNull }?.let {
                throw IOException("Ошибка генерации OpenRouter: ${errorMessage(it)}")
            }
            choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?.let { finishReason = it }
            choice.get("native_finish_reason")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?.let { nativeFinishReason = it }
            if (finishReason == "error") throw IOException("Ошибка генерации OpenRouter: finish_reason=error")

            val delta = choice.getAsJsonObject("delta") ?: choice.getAsJsonObject("message") ?: return false
            val piece = contentText(delta.get("content"))
            if (piece.isNotEmpty()) {
                appendPiece(text, piece)
                onText(piece)
            }

            delta.getAsJsonArray("tool_calls")?.forEachIndexed { fallbackIndex, element ->
                if (!element.isJsonObject) return@forEachIndexed
                val part = element.asJsonObject
                val index = runCatching { part.get("index")?.asInt }.getOrNull() ?: fallbackIndex
                val buffer = toolCalls.getOrPut(index) { ToolCallBuffer() }
                part.get("id")?.takeUnless { it.isJsonNull }?.asString?.let { buffer.id = mergeStable(buffer.id, it) }
                part.get("type")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { buffer.type = it }
                part.getAsJsonObject("function")?.let { function ->
                    function.get("name")?.takeUnless { it.isJsonNull }?.asString?.let { buffer.name = mergeStable(buffer.name, it) }
                    function.get("arguments")?.takeUnless { it.isJsonNull }?.asString?.let { buffer.arguments.append(it) }
                }
            }
            return false
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isBlank() -> if (processEvent()) break
                line.startsWith("data:") -> eventData += line.substringAfter("data:").trimStart()
                // SSE comments/keep-alives intentionally do not affect the accumulator.
                line.startsWith(":") -> Unit
            }
        }
        if (!sawDone) processEvent()

        if (!sawDone && finishReason.isBlank()) {
            throw IOException("Поток OpenRouter завершился до сигнала окончания")
        }

        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text.toString())
            if (toolCalls.isNotEmpty()) {
                add("tool_calls", com.google.gson.JsonArray().apply {
                    toolCalls.toSortedMap().values.forEach { call ->
                        add(JsonObject().apply {
                            addProperty("id", call.id)
                            addProperty("type", call.type)
                            add("function", JsonObject().apply {
                                addProperty("name", call.name)
                                addProperty("arguments", call.arguments.toString())
                            })
                        })
                    }
                })
            }
        }
        if (!allowEmpty && text.isBlank() && toolCalls.isEmpty()) {
            throw IOException(
                "Модель вернула пустой поток (finish_reason=${finishReason.ifBlank { "не указан" }}, reasoning_tokens=${reasoningTokens ?: "неизвестно"})"
            )
        }
        return OpenRouterResponseParser.Completion(
            message = message,
            id = id,
            provider = provider,
            model = model,
            finishReason = finishReason,
            nativeFinishReason = nativeFinishReason,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            reasoningTokens = reasoningTokens,
            costUsd = costUsd
        )
    }
}
