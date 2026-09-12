package com.ayuemin.ymnik.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val PREFS_NAME = "ymnik"
    private const val KEY_ENABLED = "diagnostic_logging"
    private const val MAX_BYTES = 1024 * 1024
    private const val TRIM_TO_BYTES = 640 * 1024
    private const val FILE_NAME = "umnik-diagnostic.log"

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
                    append("устройство ${Build.MANUFACTURER} ${Build.MODEL}")
                }
            )
        }
    }

    fun size(context: Context): Long = logFile(context).takeIf { it.isFile }?.length() ?: 0L

    fun file(context: Context): File? = logFile(context).takeIf { it.isFile && it.length() > 0L }

    @Synchronized
    fun clear(context: Context) {
        logFile(context).delete()
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
            }
        }
        append(context, area, details)
    }

    @Synchronized
    private fun append(context: Context, area: String, rawMessage: String) {
        val file = logFile(context)
        file.parentFile?.mkdirs()
        trimIfNeeded(file)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp | ${area.take(24)} | ${sanitize(rawMessage)}\n"
        runCatching { file.appendText(line, Charsets.UTF_8) }
    }

    private fun logFile(context: Context): File =
        File(File(context.filesDir, "diagnostics"), FILE_NAME)

    private fun trimIfNeeded(file: File) {
        if (!file.isFile || file.length() < MAX_BYTES) return
        runCatching {
            val bytes = file.readBytes()
            val start = (bytes.size - TRIM_TO_BYTES).coerceAtLeast(0)
            file.writeBytes(bytes.copyOfRange(start, bytes.size))
            file.appendText("\n--- старые записи обрезаны автоматически ---\n", Charsets.UTF_8)
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
        return safe.replace('\n', ' ').replace('\r', ' ').take(8000)
    }
}

class DiagnosticHttpInterceptor(
    private val context: Context,
    private val source: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!DiagnosticLog.isEnabled(context)) return chain.proceed(request)

        val url = request.url
        val safeUrl = "${url.scheme}://${url.host}${url.encodedPath}"
        val bodyBytes = runCatching { request.body?.contentLength() ?: 0L }.getOrDefault(-1L)
        val started = SystemClock.elapsedRealtime()
        DiagnosticLog.record(
            context,
            "HTTP",
            "$source -> ${request.method} $safeUrl; body=$bodyBytes B"
        )

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
