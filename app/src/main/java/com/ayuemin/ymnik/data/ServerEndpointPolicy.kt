package com.ayuemin.ymnik.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates the personal-server address before a bearer token can be sent to it. */
internal object ServerEndpointPolicy {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "Укажите адрес личного сервера" }
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = candidate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Некорректный адрес личного сервера")
        require(url.scheme == "https") {
            "Для личного сервера нужен HTTPS, чтобы токен не передавался открытым текстом"
        }
        require(url.username.isBlank() && url.password.isBlank()) {
            "Логин и пароль не нужно указывать в адресе сервера"
        }
        require(url.query == null && url.fragment == null) {
            "Адрес сервера не должен содержать параметры или фрагмент"
        }
        return url.toString().trimEnd('/')
    }
}
