package com.ayuemin.ymnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.network.OpenRouterResponseParser
import com.ayuemin.ymnik.network.OpenRouterStreamParser
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RuntimeOpenRouterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private lateinit var store: RuntimeTransportStore
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val http by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(240, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build()
    }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        store = RuntimeTransportStore(applicationContext)
        createChannel()
        DiagnosticLog.record(applicationContext, "RUNTIME_TRANSPORT", "runtime created pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        val transportId = intent?.getStringExtra(EXTRA_TRANSPORT_ID).orEmpty()
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (transportId.isNotBlank()) cancelTransport(transportId)
                maybeStop(startId)
                return START_NOT_STICKY
            }
            ACTION_EXECUTE -> {
                if (transportId.isBlank()) {
                    maybeStop(startId)
                    return START_NOT_STICKY
                }
                if (!activeCalls.containsKey(transportId)) {
                    acquireWakeLock()
                    scope.launch { executeTransport(transportId, startId) }
                }
                return START_STICKY
            }
            else -> {
                maybeStop(startId)
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun executeTransport(transportId: String, startId: Int) {
        val record = store.get(transportId)
        if (record == null) {
            DiagnosticLog.record(applicationContext, "RUNTIME_TRANSPORT", "missing mailbox id=${transportId.take(8)}")
            maybeStop(startId)
            return
        }
        val apiKey = SecretStore(applicationContext).getProfileApiKey(record.profileId).orEmpty()
        if (apiKey.isBlank()) {
            store.update(transportId) { it.copy(phase = "failed", error = "Runtime could not read connection credentials") }
            maybeStop(startId)
            return
        }
        store.update(transportId) { it.copy(phase = "running", error = null) }
        val request = Request.Builder()
            .url(endpoint(record.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            .post(record.payloadJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        activeCalls[transportId] = call
        try {
            DiagnosticLog.record(
                applicationContext,
                "RUNTIME_TRANSPORT",
                "POST start id=${transportId.take(8)} request=${record.requestId.take(8)} bytes=${record.payloadJson.toByteArray().size} pid=${Process.myPid()}"
            )
            call.execute().use { response ->
                val generationId = response.header("X-Generation-Id")
                val cacheStatus = response.header("X-OpenRouter-Cache-Status")
                store.update(transportId) {
                    it.copy(phase = "headers", generationId = generationId, cacheStatus = cacheStatus, httpCode = response.code)
                }
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    store.update(transportId) {
                        it.copy(
                            phase = "failed",
                            generationId = generationId,
                            cacheStatus = cacheStatus,
                            httpCode = response.code,
                            error = "OpenRouter ${response.code}: ${body.take(800)}"
                        )
                    }
                    return@use
                }

                val body = response.body ?: throw IOException("OpenRouter вернул ответ без тела")
                val completion: OpenRouterResponseParser.Completion = if (
                    response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
                ) {
                    val preview = StringBuilder()
                    var lastSavedAt = 0L
                    var lastSavedLength = 0
                    OpenRouterStreamParser.parse(body.source(), record.allowEmpty) { delta ->
                        if (delta.isNotEmpty()) {
                            preview.append(delta)
                            val now = System.currentTimeMillis()
                            if (
                                lastSavedLength == 0 ||
                                now - lastSavedAt >= PARTIAL_WRITE_INTERVAL_MS ||
                                preview.length - lastSavedLength >= PARTIAL_WRITE_MIN_CHARS
                            ) {
                                val snapshot = preview.toString().take(MAX_PARTIAL_CHARS)
                                store.update(transportId) {
                                    it.copy(
                                        phase = "streaming",
                                        generationId = generationId,
                                        cacheStatus = cacheStatus,
                                        partialText = snapshot
                                    )
                                }
                                lastSavedAt = now
                                lastSavedLength = preview.length
                            }
                        }
                    }
                } else {
                    OpenRouterResponseParser.parse(body.string(), record.allowEmpty)
                }

                store.update(transportId) {
                    it.copy(
                        phase = "completed",
                        generationId = generationId,
                        cacheStatus = cacheStatus,
                        completionJson = gson.toJson(completion),
                        partialText = "",
                        error = null
                    )
                }
                DiagnosticLog.record(
                    applicationContext,
                    "RUNTIME_TRANSPORT",
                    "completed id=${transportId.take(8)} generation=${generationId?.take(12) ?: "none"} finish=${completion.finishReason} pid=${Process.myPid()}"
                )
            }
        } catch (error: Throwable) {
            val cancelled = call.isCanceled()
            val current = store.get(transportId)
            store.update(transportId) {
                it.copy(
                    phase = if (cancelled) "cancelled" else "failed",
                    error = if (cancelled) "Запрос отменён" else "${error::class.java.simpleName}: ${error.message ?: "runtime transport failed"}"
                )
            }
            DiagnosticLog.record(
                applicationContext,
                "RUNTIME_TRANSPORT",
                "failed id=${transportId.take(8)} generation=${current?.generationId?.take(12) ?: "none"} cancel=$cancelled pid=${Process.myPid()}",
                error
            )
        } finally {
            activeCalls.remove(transportId)
            if (activeCalls.isEmpty()) releaseWakeLock()
            maybeStop(startId)
        }
    }

    private fun cancelTransport(transportId: String) {
        activeCalls.remove(transportId)?.cancel()
        store.update(transportId) { it.copy(phase = "cancelled", error = "Запрос отменён") }
        if (activeCalls.isEmpty()) releaseWakeLock()
        DiagnosticLog.record(applicationContext, "RUNTIME_TRANSPORT", "cancel id=${transportId.take(8)} pid=${Process.myPid()}")
    }

    private fun ensureForeground() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification_umnik)
            .setContentTitle("Umnik · фоновый runtime")
            .setContentText("OpenRouter отвечает в отдельном процессе")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Фоновый runtime", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Отдельный процесс сетевых запросов Umnik"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:runtime-transport").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun maybeStop(startId: Int) {
        if (activeCalls.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(applicationContext, "RUNTIME_TRANSPORT", "task removed; active=${activeCalls.size} pid=${Process.myPid()}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.record(
            applicationContext,
            "RUNTIME_TRANSPORT",
            "foreground timeout startId=$startId type=$fgsType active=${activeCalls.size} pid=${Process.myPid()}"
        )
        activeCalls.keys.toList().forEach(::cancelTransport)
        stopSelf(startId)
    }

    override fun onDestroy() {
        activeCalls.values.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
        releaseWakeLock()
        scope.cancel()
        DiagnosticLog.record(applicationContext, "RUNTIME_TRANSPORT", "runtime destroyed pid=${Process.myPid()}")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun endpoint(baseUrl: String, path: String): String {
        val root = baseUrl.trim().trimEnd('/')
        return "$root/${path.trimStart('/')}"
    }

    companion object {
        private const val CHANNEL_ID = "umnik_runtime_transport"
        private const val NOTIFICATION_ID = 4112
        private const val ACTION_EXECUTE = "com.ayuemin.ymnik.RUNTIME_TRANSPORT_EXECUTE"
        private const val ACTION_CANCEL = "com.ayuemin.ymnik.RUNTIME_TRANSPORT_CANCEL"
        private const val EXTRA_TRANSPORT_ID = "transport_id"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        private const val PARTIAL_WRITE_INTERVAL_MS = 160L
        private const val PARTIAL_WRITE_MIN_CHARS = 128
        private const val MAX_PARTIAL_CHARS = 120_000

        fun start(context: Context, transportId: String) {
            val intent = Intent(context.applicationContext, RuntimeOpenRouterService::class.java)
                .setAction(ACTION_EXECUTE)
                .putExtra(EXTRA_TRANSPORT_ID, transportId)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun cancel(context: Context, transportId: String) {
            val intent = Intent(context.applicationContext, RuntimeOpenRouterService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TRANSPORT_ID, transportId)
            runCatching { context.applicationContext.startService(intent) }
        }
    }
}
