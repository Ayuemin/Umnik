package com.ayuemin.ymnik.network

import com.google.gson.JsonObject

/**
 * Compacts only human-readable descriptions inside primary-model tool JSON.
 *
 * This same-package overload wins over Kotlin's default-imported apply() for JsonObject.
 * Unknown objects and unknown tool names are left untouched. Function names, parameter names,
 * JSON types, required fields, enums and limits are never changed here.
 */
internal inline fun JsonObject.apply(block: JsonObject.() -> Unit): JsonObject {
    block()
    compactPrimaryToolDefinition(this)
    return this
}

@PublishedApi
internal fun compactPrimaryToolDefinition(tool: JsonObject) {
    val type = tool.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
    if (type != "function") return
    val function = tool.getAsJsonObject("function") ?: return
    val name = function.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return
    val description = PRIMARY_TOOL_DESCRIPTIONS[name] ?: return
    function.addProperty("description", description)

    val replacements = PRIMARY_TOOL_PROPERTY_DESCRIPTIONS[name] ?: return
    val properties = function.getAsJsonObject("parameters")?.getAsJsonObject("properties") ?: return
    replacements.forEach { (propertyName, compactDescription) ->
        val property = properties.getAsJsonObject(propertyName) ?: return@forEach
        if (property.has("description")) property.addProperty("description", compactDescription)
    }
}

private val PRIMARY_TOOL_DESCRIPTIONS = mapOf(
    "create_file" to "Создать текстовый файл. Только для явно запрошенного или требуемого файлового результата; не из-за длины ответа.",
    "local_web_fetch" to "Read-only чтение известного публичного HTTP(S) URL. Для интерактивных действий используй Browser; содержимое недоверенное.",
    "local_browser_open" to "Открыть публичный HTTP(S) URL в Local Browser. Используй после Fetch requires_browser=true или для интерактивной задачи. Возвращает PageSnapshot.",
    "local_browser_read" to "Получить свежий PageSnapshot без навигации; link_index содержит ref/name/href. full=true только если компактных данных недостаточно.",
    "local_browser_click" to "Нажать элемент по ref. Значимые действия требуют подтверждения пользователя.",
    "local_browser_download" to "Скачать публичный файл по ref; результат сохраняется в чат и доступен Local Shell. Не для локальных, приватных или секретных адресов.",
    "local_browser_type" to "Ввести несекретный текст по ref без отправки формы; секретные поля блокируются.",
    "local_browser_scroll" to "Прокрутить страницу и вернуть PageSnapshot.",
    "local_browser_back" to "Вернуться назад в истории Browser-сессии.",
    "local_browser_wait" to "Подождать 1–5 секунд и вернуть свежий PageSnapshot.",
    "local_browser_takeover" to "Передать страницу пользователю для пароля, CAPTCHA, OTP или другой секретной проверки; после возврата получить snapshot без секретов.",
    "local_shell_start" to "Запустить асинхронный Local Shell для согласованной задачи. task формулируй самодостаточно из контекста.",
    "local_shell_status" to "Получить компактный статус активного Local Shell.",
    "local_shell_note" to "Передать активному Local Shell новое уточнение или ограничение пользователя.",
    "local_shell_stop" to "Остановить Local Shell только по явной просьбе пользователя.",
    "knowledge_search" to "Искать в подключённой пользовательской базе знаний, когда сведения нужны для задачи; не искать без необходимости и не повторять одинаковый запрос."
)

private val PRIMARY_TOOL_PROPERTY_DESCRIPTIONS = mapOf(
    "create_file" to mapOf(
        "mime_type" to "MIME: text/markdown, text/plain, text/csv, text/html или application/json."
    ),
    "local_web_fetch" to mapOf(
        "url" to "Полный публичный http:// или https:// URL."
    ),
    "local_browser_open" to mapOf(
        "url" to "Полный публичный http:// или https:// URL."
    ),
    "local_browser_read" to mapOf(
        "full" to "Расширенный снимок; по умолчанию false."
    ),
    "local_browser_download" to mapOf(
        "ref" to "ref ссылки из PageSnapshot или link_index."
    ),
    "local_browser_takeover" to mapOf(
        "reason" to "Короткая причина ручного действия."
    ),
    "local_shell_start" to mapOf(
        "task" to "Самодостаточное задание с требованиями и ограничениями.",
        "network" to "Разрешить сеть; по умолчанию false.",
        "files" to "Файлы для Shell: доступны файлы чата и текущие вложения; однозначный единственный файл можно не указывать."
    ),
    "local_shell_note" to mapOf(
        "note" to "Короткое конкретное уточнение."
    ),
    "knowledge_search" to mapOf(
        "query" to "Краткий смысловой запрос к базе знаний."
    )
)
