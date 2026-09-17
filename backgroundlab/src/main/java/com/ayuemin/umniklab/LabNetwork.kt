package com.ayuemin.umniklab

import android.content.Context
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LabNetwork {
    data class Result(
        val httpCode: Int,
        val generationId: String?,
        val elapsedMs: Long,
        val textChars: Int
    )

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    fun execute(context: Context, mode: String): Result {
        val apiKey = LabState.apiKey(context)
        val prompt = LabState.prompt(context)
        require(apiKey.isNotBlank()) { "API key is empty" }

        val payload = JSONObject().apply {
            put("model", "openrouter/auto")
            put("stream", false)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }.toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Background Lab")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val started = System.currentTimeMillis()
        val call = client.newCall(request)
        activeCall = call
        LabState.log(context, mode, "POST start bytes=${payload.toByteArray().size}")
        try {
            call.execute().use { response ->
                val generationId = response.header("X-Generation-Id")
                val body = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                LabState.log(
                    context,
                    mode,
                    "HTTP ${response.code} generation=${generationId ?: "none"} elapsed=${elapsed}ms bodyBytes=${body.toByteArray().size}"
                )
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${body.take(500)}")
                }
                val textChars = runCatching {
                    val root = JSONObject(body)
                    root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").length
                }.getOrDefault(0)
                return Result(response.code, generationId, elapsed, textChars)
            }
        } finally {
            activeCall = null
        }
    }
}
