package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenRouterFilesClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Files"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Files") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private val http1 = http.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    data class RemoteFile(
        val id: String,
        val name: String? = null,
        val bytes: Long? = null,
        val createdAt: Long? = null
    )

    suspend fun upload(
        apiKey: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        baseUrl: String = DEFAULT_BASE_URL
    ): RemoteFile = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Файл пуст" }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                name,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            )
            .build()
        val request = Request.Builder()
            .url(endpoint(baseUrl, "files"))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .post(multipart)
            .build()
        executeWithHttp1Fallback(request, "upload:$name").use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            parseRemoteFile(body, name)
        }
    }

    suspend fun download(
        apiKey: String,
        fileId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray = getBytes(apiKey, endpoint(baseUrl, "files/${fileId.trim()}/content"))

    suspend fun downloadContainerFile(
        apiKey: String,
        containerId: String,
        fileId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray = getBytes(
        apiKey,
        endpoint(baseUrl, "containers/${containerId.trim()}/files/${fileId.trim()}/content")
    )

    suspend fun promoteContainerFile(
        apiKey: String,
        containerId: String,
        fileId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): RemoteFile = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "containers/${containerId.trim()}/files/${fileId.trim()}/promote"))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        executeWithHttp1Fallback(request, "promote:$fileId").use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            parseRemoteFile(body, null)
        }
    }

    suspend fun delete(
        apiKey: String,
        fileId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "files/${fileId.trim()}"))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .delete()
            .build()
        executeWithHttp1Fallback(request, "delete:$fileId").use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
        }
    }

    private suspend fun getBytes(apiKey: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        executeWithHttp1Fallback(request, "download").use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(apiError(response.code, body))
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: error("OpenRouter вернул пустой файл")
        }
    }

    private fun executeWithHttp1Fallback(request: Request, operation: String): okhttp3.Response {
        return try {
            http.newCall(request).execute()
        } catch (error: Throwable) {
            if (!isHttp2ProtocolFailure(error)) throw error
            com.ayuemin.ymnik.diagnostics.DiagnosticLog.record(
                context,
                "FILES_RETRY",
                "$operation; reason=${error.message.orEmpty().take(160)}; retry=http1"
            )
            http1.newCall(request).execute()
        }
    }

    private fun parseRemoteFile(json: String, fallbackName: String?): RemoteFile {
        val root = gson.fromJson(json, JsonObject::class.java)
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        val id = data.string("id") ?: error("OpenRouter Files API не вернул id файла")
        return RemoteFile(
            id = id,
            name = data.string("filename") ?: data.string("name") ?: fallbackName,
            bytes = data.long("bytes") ?: data.long("size"),
            createdAt = data.long("created_at")
        )
    }

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.string("message") ?: root.string("message")
        }.getOrNull()
        return "OpenRouter Files HTTP $code: ${detail ?: body.take(400)}"
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(name: String): Long? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        internal fun isHttp2ProtocolFailure(error: Throwable): Boolean {
            val messages = generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            return messages.contains("PROTOCOL_ERROR", ignoreCase = true) ||
                messages.contains("stream was reset", ignoreCase = true) ||
                (messages.contains("HTTP/2", ignoreCase = true) &&
                    messages.contains("reset", ignoreCase = true))
        }
    }
}
