package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class UmnikServerClient {
    data class Capabilities(
        val protocol: Int = 0,
        val durable_chat_jobs: Boolean = false,
        val idempotent_client_request_id: Boolean = false,
        val streaming: Boolean = false
    )

    data class Job(
        val id: String = "",
        val client_request_id: String = "",
        val status: String = "",
        val response: JsonObject? = null,
        val error: String? = null,
        val created_at: Long = 0L,
        val updated_at: Long = 0L
    ) {
        val terminal: Boolean
            get() = status == "completed" || status == "failed"
    }

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint(baseUrl, "health")).get().build()
        runCatching {
            http.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.let { body ->
                    gson.fromJson(body, JsonObject::class.java).get("ok")?.asBoolean == true
                } == true
            }
        }.getOrDefault(false)
    }

    suspend fun capabilities(baseUrl: String, token: String): Capabilities = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "v1/capabilities"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(serverError(response.code, body))
            gson.fromJson(body, Capabilities::class.java)
        }
    }

    suspend fun createChatJob(
        baseUrl: String,
        token: String,
        clientRequestId: String,
        payload: JsonObject
    ): Job = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("client_request_id", clientRequestId)
            add("payload", payload)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "v1/chat/jobs"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(serverError(response.code, raw))
            gson.fromJson(raw, Job::class.java)
        }
    }

    suspend fun getChatJob(baseUrl: String, token: String, jobId: String): Job = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "v1/chat/jobs/$jobId"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(serverError(response.code, raw))
            gson.fromJson(raw, Job::class.java)
        }
    }

    suspend fun awaitChatJob(
        baseUrl: String,
        token: String,
        initial: Job,
        pollIntervalMs: Long = 1_500L,
        timeoutMs: Long = 15L * 60L * 1000L,
        onStatus: (String) -> Unit = {}
    ): JsonObject {
        val startedAt = System.currentTimeMillis()
        var job = initial
        var transientFailures = 0
        while (true) {
            onStatus(job.status)
            when (job.status) {
                "completed" -> return job.response ?: error("Сервер завершил задачу без ответа")
                "failed" -> error(job.error?.takeIf { it.isNotBlank() } ?: "Серверная задача завершилась ошибкой")
            }
            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                error("Сервер продолжает работу дольше ожидаемого. Задачу можно проверить повторно по ID ${job.id}.")
            }
            delay(pollIntervalMs.coerceAtLeast(500L))
            val refreshed = runCatching { getChatJob(baseUrl, token, job.id) }
            refreshed.onSuccess {
                transientFailures = 0
                job = it
            }.onFailure {
                transientFailures += 1
                onStatus("reconnecting")
                // Polling is read-only. A temporary phone/network failure must never turn
                // into a second paid generation, so keep asking for the same server job.
                delay((1_000L * transientFailures.coerceAtMost(8)).coerceAtMost(8_000L))
            }
        }
    }

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    private fun serverError(code: Int, body: String): String {
        val detail = runCatching {
            gson.fromJson(body, JsonObject::class.java).get("detail")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return detail ?: "Umnik Server: HTTP $code"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
