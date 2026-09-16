package com.ayuemin.ymnik.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ayuemin.ymnik.network.OpenRouterRequestEnhancer
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object DiagnosticLog {
    private const val PREFS_NAME = "ymnik"
    private const val KEY_ENABLED = "diagnostic_logging"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val TRIM_TO_BYTES = 6L * 1024L * 1024L
    private const val FILE_NAME = "umnik-diagnostic.log"
    private val sequence = AtomicLong(0L)
    @Volatile private var sessionId: String = "process-${UUID.randomUUID().toString().take(8)}"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean(KEY_ENABLED, false)
        if (wasEnabled == enabled) return

        if (!enabled && wasEnabled) {
            append(context, "SYSTEM", "Запись логов выключена пользователем")
        }
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            sessionId = UUID.randomUUID().toString().take(8)
            sequence.set(0L)
            val packageInfo = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            append(
                context,
                "SYSTEM",
                buildString {
                    append("Новая диагностическая сессия; ")
                    append("Umnik ${packageInfo?.versionName ?: "?"}; ")
                    append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); ")
                    append("устройство ${Build.MANUFACTURER} ${Build.MODEL}; ")
                    append("logLimit=8MB")
                }
            )
        }
    }

    fun size(context: Context): Long = logFile(context).takeIf { it.isFile }?.length() ?: 0L

    fun file(context: Context): File? = logFile(context).takeIf { it.isFile && it.length() > 0L }

    @Synchronized
    fun clear(context: Context) {
        logFile(context).delete()
        sequence.set(0L)
    }

    fun record(context: Context, area: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(context)) return
        val details = buildString {
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.javaClass.simpleName)
                throwable.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
                append(" | stack=")
                append(throwable.stackTraceToString().take(12000))
            }
        }
        append(context, area, details)
    }

    fun action(context: Context, action: String, details: String = "") {
        record(context, "ACTION", if (details.isBlank()) action else "$action; $details")
    }

    @Synchronized
    private fun append(context: Context, area: String, rawMessage: String) {
        val file = logFile(context)
        file.parentFile?.mkdirs()
        trimIfNeeded(file)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val seq = sequence.incrementAndGet()
        val line = "$timestamp | session=$sessionId | seq=$seq | ${area.take(24)} | ${sanitize(rawMessage)}\n"
        runCatching { file.appendText(line, Charsets.UTF_8) }
    }

    private fun logFile(context: Context): File =
        File(File(context.filesDir, "diagnostics"), FILE_NAME)

    private fun trimIfNeeded(file: File) {
        if (!file.isFile || file.length() < MAX_BYTES) return
        runCatching {
            val bytes = file.readBytes()
            val keep = TRIM_TO_BYTES.coerceAtMost(bytes.size.toLong()).toInt()
            val start = (bytes.size - keep).coerceAtLeast(0)
            file.writeBytes(bytes.copyOfRange(start, bytes.size))
            file.appendText("\n--- старые записи обрезаны автоматически; сохранены последние ~6 МБ ---\n", Charsets.UTF_8)
        }
    }

    private fun sanitize(value: String): String {
        var safe = value
        safe = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,}]+")
            .replace(safe, "$1<redacted>")
        safe = Regex("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,}]+")
            .replace(safe, "$1<redacted>")
        safe = Regex("\\b(?:sk-[A-Za-z0-9_-]{12,}|nvapi-[A-Za-z0-9_-]{12,})\\b")
            .replace(safe, "<redacted-key>")
        safe = Regex("(?i)([?&](?:key|token|api_key)=)[^&\\s]+")
            .replace(safe, "$1<redacted>")
        return safe.replace('\n', ' ').replace('\r', ' ').take(16000)
    }
}

class DiagnosticHttpInterceptor(
    private val context: Context,
    private val source: String,
    private val requestId: String? = null,
    private val requestChatId: String? = null,
    private val onPreparedOpenRouterRequest: ((Request) -> Unit)? = null
) : Interceptor {
    private val openRouterEnhancer by lazy {
        OpenRouterRequestEnhancer(context.applicationContext, requestId, requestChatId)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        if (source == "OpenRouter") {
            val enhanced = openRouterEnhancer.enhance(request)
            enhanced.response?.let { return it }
            request = enhanced.request ?: request
            onPreparedOpenRouterRequest?.invoke(request)
        }

        if (!DiagnosticLog.isEnabled(context)) return chain.proceed(request)

        val url = request.url
        val safeUrl = "${url.scheme}://${url.host}${url.encodedPath}"
        val bodyBytes = runCatching { request.body?.contentLength() ?: 0L }.getOrDefault(-1L)
        val started = SystemClock.elapsedRealtime()
        DiagnosticLog.record(context, "HTTP", "$source -> ${request.method} $safeUrl; body=$bodyBytes B")

        return try {
            val response = chain.proceed(request)
            val elapsed = SystemClock.elapsedRealtime() - started
            DiagnosticLog.record(
                context,
                "HTTP",
                "$source <- HTTP ${response.code}; ${elapsed} ms; type=${response.header("Content-Type").orEmpty()}; length=${response.body?.contentLength() ?: -1L}"
            )
            response
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - started
            DiagnosticLog.record(context, "HTTP", "$source !! after ${elapsed} ms", t)
            throw t
        }
    }
}
