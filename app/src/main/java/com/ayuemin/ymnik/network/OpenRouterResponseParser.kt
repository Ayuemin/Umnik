package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal object OpenRouterResponseParser {
    data class Completion(
        val message: JsonObject,
        val id: String,
        val provider: String,
        val finishReason: String,
        val nativeFinishReason: String,
        val completionTokens: Int?,
        val reasoningTokens: Int?
    )

    fun parse(body: String, allowEmpty: Boolean = false): Completion {
        val root = Gson().fromJson(body, JsonObject::class.java)
            ?: error("OpenRouter вернул ответ без JSON")
        val rootError = root.get("error")?.takeUnless { it.isJsonNull }
        if (rootError != null) error("OpenRouter: ${errorMessage(rootError)}")
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: error("OpenRouter вернул ответ без choices; id=${root.get("id")?.asString.orEmpty()}")
        val finish = choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val nativeFinish = choice.get("native_finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val choiceError = choice.get("error")?.takeUnless { it.isJsonNull }
        if (choiceError != null || finish == "error") {
            error("Ошибка генерации OpenRouter: ${choiceError?.let(::errorMessage) ?: "finish_reason=error"}")
        }
        val usage = root.getAsJsonObject("usage")
        val reasoningTokens = runCatching {
            usage?.getAsJsonObject("completion_tokens_details")?.get("reasoning_tokens")?.asInt
        }.getOrNull()
        val message = choice.getAsJsonObject("message") ?: error("OpenRouter не вернул сообщение модели")
        val toolCount = message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
        if (!allowEmpty && toolCount == 0 && contentText(message.get("content")).isBlank()) {
            error("Модель вернула пустой текст (finish_reason=${finish.ifBlank { "не указан" }}, reasoning_tokens=${reasoningTokens ?: "неизвестно"}). Попробуйте уменьшить рассуждение или повторить запрос.")
        }
        return Completion(
            message = message,
            id = root.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            provider = root.get("provider")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            finishReason = finish,
            nativeFinishReason = nativeFinish,
            completionTokens = runCatching { usage?.get("completion_tokens")?.asInt }.getOrNull(),
            reasoningTokens = reasoningTokens
        )
    }

    private fun contentText(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.mapNotNull { part ->
            when {
                part.isJsonPrimitive -> part.asString
                part.isJsonObject -> part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            }
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun errorMessage(value: JsonElement): String = runCatching {
        if (value.isJsonObject) value.asJsonObject.get("message")?.asString.orEmpty().take(250)
        else value.toString().take(250)
    }.getOrDefault("поставщик не указал причину")
}
