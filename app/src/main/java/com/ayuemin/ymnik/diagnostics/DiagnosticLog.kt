package com.ayuemin.ymnik.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ayuemin.ymnik.network.OpenRouterRequestEnhancer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object DiagnosticLog {
    private const val PREFS_NAME = "ymnik"
    private const val KEY_ENABLED = "diagnostic_logging"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val TRIM_TO_BYTES = 6L * 1024L * 1024L
    private const val FILE_NAME = "umnik-diagnostic.log"
    private const val KEY_LAST_EXIT_TIMESTAMP = "diagnostic_last_exit_timestamp"
    private val sequence = AtomicLong(0L)
    @Volatile private var sessionId: String = "process-${UUID.randomUUID().toString().take(8)}"
    @Volatile private var crashHandlerInstalled: Boolean = false

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        synchronized(this) {
            if (crashHandlerInstalled) return
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { record(app, "UNCAUGHT", "thread=${thread.name}; id=${thread.id}", throwable) }
                previous?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
        }
    }

    fun recordPreviousProcessExit(context: Context) {
        if (!isEnabled(context) || Build.VERSION.SDK_INT < 30) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val info = runCatching { app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 5).maxByOrNull { it.timestamp } }.getOrNull() ?: return
        if (info.timestamp <= lastSeen) return
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, info.timestamp).apply()
        record(app, "PROCESS_EXIT", buildString {
            append("previous process; reason=").append(info.reason).append("(").append(exitReasonLabel(info.reason)).append("); status=").append(info.status)
            append("; importance=").append(info.importance).append("; timestamp=").append(info.timestamp)
            info.description?.takeIf { it.isNotBlank() }?.let { append("; description=").append(it.take(500)) }
        })
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"; 1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"; else -> "REASON_$reason"
    }

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean(KEY_ENABLED, false)
        if (wasEnabled == enabled) return
        if (!enabled && wasEnabled) append(context, "SYSTEM", "Запись логов выключена пользователем")
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            sessionId = UUID.randomUUID().toString().take(8); sequence.set(0L)
            val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            append(context, "SYSTEM", "Новая диагностическая сессия; Umnik ${packageInfo?.versionName ?: "?"}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); устройство ${Build.MANUFACTURER} ${Build.MODEL}; logLimit=8MB")
        }
    }

    fun size(context: Context): Long = logFile(context).takeIf { it.isFile }?.length() ?: 0L
    fun file(context: Context): File? = logFile(context).takeIf { it.isFile && it.length() > 0L }
    @Synchronized fun clear(context: Context) { logFile(context).delete(); sequence.set(0L) }

    fun record(context: Context, area: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(context)) return
        val details = buildString { append(message); if (throwable != null) { append(" | ").append(throwable.javaClass.simpleName); throwable.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }; append(" | stack=").append(throwable.stackTraceToString().take(12000)) } }
        append(context, area, details)
    }

    fun action(context: Context, action: String, details: String = "") { record(context, "ACTION", if (details.isBlank()) action else "$action; $details") }

    @Synchronized private fun append(context: Context, area: String, rawMessage: String) {
        val file = logFile(context); file.parentFile?.mkdirs(); trimIfNeeded(file)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()); val seq = sequence.incrementAndGet()
        runCatching { file.appendText("$timestamp | session=$sessionId | seq=$seq | ${area.take(24)} | ${sanitize(rawMessage)}\n", Charsets.UTF_8) }
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, "diagnostics"), FILE_NAME)
    private fun trimIfNeeded(file: File) { if (!file.isFile || file.length() < MAX_BYTES) return; runCatching { val bytes = file.readBytes(); val keep = TRIM_TO_BYTES.coerceAtMost(bytes.size.toLong()).toInt(); file.writeBytes(bytes.copyOfRange((bytes.size - keep).coerceAtLeast(0), bytes.size)); file.appendText("\n--- старые записи обрезаны автоматически; сохранены последние ~6 МБ ---\n", Charsets.UTF_8) } }
    private fun sanitize(value: String): String { var safe = value; safe = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,}]+").replace(safe, "$1<redacted>"); safe = Regex("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,}]+").replace(safe, "$1<redacted>"); safe = Regex("\\b(?:sk-[A-Za-z0-9_-]{12,}|nvapi-[A-Za-z0-9_-]{12,})\\b").replace(safe, "<redacted-key>"); safe = Regex("(?i)([?&](?:key|token|api_key)=)[^&\\s]+").replace(safe, "$1<redacted>"); return safe.replace('\n', ' ').replace('\r', ' ').take(16000) }
}

class DiagnosticHttpInterceptor(
    private val context: Context,
    private val source: String,
    private val requestId: String? = null,
    private val requestChatId: String? = null,
    private val onPreparedOpenRouterRequest: ((Request) -> Unit)? = null
) : Interceptor {
    private val openRouterEnhancer by lazy { OpenRouterRequestEnhancer(context.applicationContext, requestId, requestChatId) }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (source == "OpenRouter") {
            val enhanced = openRouterEnhancer.enhance(request); enhanced.response?.let { return it }; request = enhanced.request ?: request; onPreparedOpenRouterRequest?.invoke(request)
        }

        if (source == LOCAL_SHELL_SOURCE) {
            synchronized(SHELL_HEALTH_LOCK) {
                if (pauseNextShellCall) {
                    pauseNextShellCall = false
                    throw IOException("LOCAL_SHELL_NETWORK_PAUSE: несколько ответов подряд начинались медленнее 10 с; состояние сохранено, автоматический повтор запрещён")
                }
            }
        }

        val effectiveChain = if (source == LOCAL_SHELL_SOURCE) chain.withReadTimeout(SHELL_READ_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS) else chain
        val loggingEnabled = DiagnosticLog.isEnabled(context)
        if (!loggingEnabled && source != LOCAL_SHELL_SOURCE) return effectiveChain.proceed(request)

        val localShellRequest = if (source == LOCAL_SHELL_SOURCE) requestJson(request) else null
        if (loggingEnabled && localShellRequest != null) recordLocalShellTask(localShellRequest)
        val url = request.url; val safeUrl = "${url.scheme}://${url.host}${url.encodedPath}"; val bodyBytes = runCatching { request.body?.contentLength() ?: 0L }.getOrDefault(-1L); val started = SystemClock.elapsedRealtime()
        if (loggingEnabled) DiagnosticLog.record(context, "HTTP", "$source -> ${request.method} $safeUrl; body=$bodyBytes B")

        return try {
            val response = effectiveChain.proceed(request); val elapsed = SystemClock.elapsedRealtime() - started
            if (source == LOCAL_SHELL_SOURCE) updateSlowStartHealth(elapsed)
            if (loggingEnabled) DiagnosticLog.record(context, "HTTP", "$source <- HTTP ${response.code}; ${elapsed} ms; type=${response.header("Content-Type").orEmpty()}; length=${response.body?.contentLength() ?: -1L}")
            if (source == LOCAL_SHELL_SOURCE && localShellRequest != null) recordLocalShellResponse(localShellRequest, response, elapsed, loggingEnabled)
            response
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val effectiveError = if (
                source == LOCAL_SHELL_SOURCE &&
                elapsed >= SHELL_START_TIMEOUT_CLASSIFY_MS &&
                t is IOException &&
                t.message.orEmpty().contains("cancel", ignoreCase = true)
            ) IOException("LOCAL_SHELL_START_TIMEOUT: no response headers within 60s", t) else t
            if (source == LOCAL_SHELL_SOURCE && loggingEnabled) { DiagnosticLog.record(context, "LOCAL_SHELL_LLM", "transport_error=${effectiveError.javaClass.simpleName}; elapsedMs=$elapsed; message=${effectiveError.message.orEmpty().take(1200)}"); DiagnosticLog.record(context, "SHELL_BUDGET", LocalShellBudgetTelemetry.snapshot().line()) }
            if (loggingEnabled) DiagnosticLog.record(context, "HTTP", "$source !! after ${elapsed} ms", effectiveError)
            throw effectiveError
        }
    }

    private fun updateSlowStartHealth(elapsedMs: Long) {
        synchronized(SHELL_HEALTH_LOCK) {
            if (elapsedMs >= SHELL_SLOW_START_MS) slowShellStarts += 1 else slowShellStarts = 0
            if (slowShellStarts >= SHELL_SLOW_START_LIMIT) {
                pauseNextShellCall = true
                DiagnosticLog.record(context, "LOCAL_SHELL_NETWORK_HEALTH", "slow_start_streak=$slowShellStarts; lastHeadersMs=$elapsedMs; action=pause_next_model_call")
            }
        }
    }

    private fun requestJson(request: Request): JsonObject? {
        val body = request.body ?: return null
        val raw = runCatching { val buffer = Buffer(); body.writeTo(buffer); buffer.readUtf8() }.getOrNull() ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    }

    private fun recordLocalShellTask(root: JsonObject) {
        val metadata = root.getAsJsonObject("metadata") ?: return
        if (metadata.get("umnik_local_shell")?.asString != "true" || metadata.get("umnik_turn")?.asString != "1" || metadata.get("umnik_context_compaction")?.asString == "true") return
        val messages = root.getAsJsonArray("messages") ?: return; var task = ""
        for (item in messages) { if (!item.isJsonObject) continue; val message = item.asJsonObject; if (message.get("role")?.asString != "user") continue; task = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(); if (task.isNotBlank()) break }
        if (task.isBlank()) return
        val oneLine = task.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
        DiagnosticLog.record(context, "LOCAL_SHELL_TASK", buildString { append("chars=").append(task.length).append("; task=").append(oneLine.take(4_000)); if (oneLine.length > 4_000) append("; truncated=true") })
    }

    private fun recordLocalShellResponse(requestRoot: JsonObject, response: Response, elapsedMs: Long, loggingEnabled: Boolean) {
        val metadata = requestRoot.getAsJsonObject("metadata"); val requestedModel = requestRoot.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(); val requestRun = metadata?.get("umnik_request_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(); val turn = metadata?.get("umnik_turn")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(); val compact = metadata?.get("umnik_context_compaction")?.takeIf { it.isJsonPrimitive }?.asString == "true"
        val body = runCatching { response.peekBody(MAX_SHELL_DIAGNOSTIC_BODY_BYTES).string() }.getOrDefault(""); val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        if (!response.isSuccessful) {
            if (loggingEnabled) {
                val detail = root?.getAsJsonObject("error")?.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: root?.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: body.take(2_000)
                DiagnosticLog.record(context, "LOCAL_SHELL_PROVIDER_ERROR", "http=${response.code}; request=$requestRun; turn=$turn; requestedModel=$requestedModel; body=${detail.take(2_000)}")
            }
            return
        }
        val usage = root?.getAsJsonObject("usage")
        fun number(name: String): String = usage?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val input = number("prompt_tokens").ifBlank { number("input_tokens") }; val output = number("completion_tokens").ifBlank { number("output_tokens") }
        val cached = usage?.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asString ?: usage?.getAsJsonObject("input_tokens_details")?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val cost = number("cost").ifBlank { root?.get("cost")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }; val actualModel = root?.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        LocalShellBudgetTelemetry.recordLlm(turn.toIntOrNull(), input.toLongOrNull(), output.toLongOrNull(), cached.toLongOrNull(), cost.toDoubleOrNull())
        if (loggingEnabled) {
            DiagnosticLog.record(context, "LOCAL_SHELL_LLM", "request=$requestRun; turn=$turn; compact=$compact; requestedModel=$requestedModel; actualModel=${actualModel.ifBlank { "unknown" }}; input=${input.ifBlank { "unknown" }}; output=${output.ifBlank { "unknown" }}; cached=${cached.ifBlank { "unknown" }}; cost=${cost.ifBlank { "unknown" }}; elapsedMs=$elapsedMs; bodyChars=${body.length}")
            DiagnosticLog.record(context, "SHELL_BUDGET", LocalShellBudgetTelemetry.snapshot().line())
        }
    }

    companion object {
        private const val LOCAL_SHELL_SOURCE = "Local Shell Model"
        private const val MAX_SHELL_DIAGNOSTIC_BODY_BYTES = 512L * 1024L
        private const val SHELL_READ_IDLE_TIMEOUT_SECONDS = 90
        private const val SHELL_SLOW_START_MS = 10_000L
        private const val SHELL_SLOW_START_LIMIT = 3
        private const val SHELL_START_TIMEOUT_CLASSIFY_MS = 59_000L
        private val SHELL_HEALTH_LOCK = Any()
        private var slowShellStarts = 0
        private var pauseNextShellCall = false
    }
}
