package com.ayuemin.ymnik.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * User-maintained site bookmarks for Local Browser. No credentials, tokens or
 * cookies are stored here; authentication remains in the WebView's cookie jar.
 * Password vault and agent access grants are deliberately separate features.
 */
data class BrowserSite(
    val id: String,
    val title: String,
    val loginUrl: String,
    val host: String
)

class BrowserSitesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("umnik_browser_sites", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<BrowserSite>>() {}.type

    fun list(): List<BrowserSite> =
        runCatching {
            gson.fromJson<List<BrowserSite>>(prefs.getString("sites", "[]"), listType).orEmpty()
        }.getOrDefault(emptyList()).filter { it.host.isNotBlank() && it.loginUrl.isNotBlank() }

    fun add(title: String, loginUrl: String): BrowserSite {
        val uri = URI(loginUrl.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для сохранённого входа требуется адрес HTTPS"
        }
        require(uri.rawUserInfo == null && uri.rawFragment == null) {
            "Адрес входа не должен содержать учётные данные или фрагмент"
        }
        val hostRaw = uri.host ?: error("В адресе отсутствует домен")
        val host = IDN.toASCII(hostRaw).lowercase(Locale.ROOT)
        require(host.length <= 253 && host.contains('.') && !host.startsWith('.') &&
            host != "localhost" && !host.endsWith(".localhost") && ':' !in host) {
            "Введите публичный домен сайта"
        }
        require(uri.port == -1 || uri.port == 443) { "Нестандартный порт запрещён" }
        require(uri.rawQuery.isNullOrBlank()) {
            "Ссылка входа с параметрами может содержать токены; сохраните адрес без параметров"
        }
        val canonicalUrl = URI("https", null, host, -1, uri.rawPath.ifBlank { "/" }, null, null).toString()
        val record = BrowserSite(UUID.randomUUID().toString(), title.trim().ifBlank { host }.take(80), canonicalUrl, host)
        val current = list().filterNot { it.loginUrl == record.loginUrl }
        prefs.edit().putString("sites", gson.toJson((current + record).takeLast(40))).apply()
        return record
    }

    fun remove(id: String) {
        prefs.edit().putString("sites", gson.toJson(list().filterNot { it.id == id })).apply()
    }
}
