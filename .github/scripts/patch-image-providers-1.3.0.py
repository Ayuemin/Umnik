from pathlib import Path
import re
import json

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly one match, got {count}')
    return text.replace(old, new, 1)


def replace_block(text, start, end, new_block, label):
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f'{label}: start marker not found')
    b = text.find(end, a + len(start))
    if b < 0:
        raise RuntimeError(f'{label}: end marker not found')
    return text[:a] + new_block.rstrip() + '\n\n' + text[b:]


# ---------------------------------------------------------------------------
# Model layer
# ---------------------------------------------------------------------------
models_path = 'app/src/main/java/com/ayuemin/ymnik/model/Models.kt'
models = read(models_path)
models = replace_once(
    models,
    '''enum class ProviderType {
    OPENROUTER,
    OPENAI_COMPATIBLE
}
''',
    '''enum class ProviderType {
    OPENROUTER,
    NVIDIA,
    OPENAI_COMPATIBLE
}

enum class ImageApiProtocol {
    AUTO,
    OPENAI_COMPATIBLE,
    NVIDIA_NIM
}
''',
    'ProviderType/ImageApiProtocol'
)
models = replace_once(
    models,
    '''data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String
)
''',
    '''data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val imageEnabled: Boolean? = null,
    val imageBaseUrl: String? = null,
    val imageProtocol: ImageApiProtocol? = null,
    val useSameImageApiKey: Boolean? = null,
    val useProviderDefaults: Boolean? = null
)
''',
    'ConnectionProfile'
)
write(models_path, models)


# ---------------------------------------------------------------------------
# Encrypted secret storage: optional separate key for image API
# ---------------------------------------------------------------------------
secret_store = r'''package com.ayuemin.ymnik.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "ymnik_openrouter_key"

    fun saveApiKey(value: String) = saveProfileApiKey("openrouter", value)

    fun getApiKey(): String? = getProfileApiKey("openrouter")

    fun saveProfileApiKey(profileId: String, value: String) = saveSecret(prefKey(profileId), value)

    fun getProfileApiKey(profileId: String): String? = readSecret(prefKey(profileId))

    fun saveProfileImageApiKey(profileId: String, value: String) = saveSecret(imagePrefKey(profileId), value)

    fun getProfileImageApiKey(profileId: String): String? = readSecret(imagePrefKey(profileId))

    fun deleteProfileImageApiKey(profileId: String) {
        prefs.edit().remove(imagePrefKey(profileId)).apply()
    }

    fun deleteProfileApiKey(profileId: String) {
        if (profileId == "openrouter") return
        prefs.edit()
            .remove(prefKey(profileId))
            .remove(imagePrefKey(profileId))
            .apply()
    }

    private fun saveSecret(prefKey: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(prefKey).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(prefKey, payload).apply()
    }

    private fun readSecret(prefKey: String): String? {
        val payload = prefs.getString(prefKey, null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun prefKey(profileId: String): String = if (profileId == "openrouter") {
        "api_key"
    } else {
        "api_key_profile_" + safeId(profileId)
    }

    private fun imagePrefKey(profileId: String): String = "image_api_key_profile_" + safeId(profileId)

    private fun safeId(profileId: String): String = profileId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
'''
write('app/src/main/java/com/ayuemin/ymnik/data/SecretStore.kt', secret_store)


# ---------------------------------------------------------------------------
# Provider registry. Bundled fallback + cached remote JSON from this repo.
# ---------------------------------------------------------------------------
provider_registry = r'''package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ProviderType
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ProviderRegistry(context: Context) {
    data class ImageModelDefinition(
        val id: String = "",
        val inputModalities: List<String> = listOf("text"),
        val parameterOptions: Map<String, List<String>> = emptyMap()
    )

    data class ProviderDefinition(
        val textBaseUrl: String = "",
        val imageBaseUrl: String = "",
        val imageProtocol: String = "AUTO",
        val imageModels: List<ImageModelDefinition> = emptyList()
    )

    data class RegistryDocument(
        val version: Int = 1,
        val providers: Map<String, ProviderDefinition> = emptyMap()
    )

    private val prefs = context.getSharedPreferences("provider_registry", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var current: RegistryDocument = loadCached() ?: fallback()

    fun textBaseUrl(type: ProviderType): String? = provider(type)?.textBaseUrl?.trim()?.takeIf { it.isNotBlank() }

    fun imageBaseUrl(type: ProviderType): String? = provider(type)?.imageBaseUrl?.trim()?.takeIf { it.isNotBlank() }

    fun imageModels(type: ProviderType): List<ModelInfo> = provider(type)?.imageModels.orEmpty()
        .filter { it.id.isNotBlank() }
        .map { definition ->
            val options = definition.parameterOptions
                .mapValues { (_, values) -> values.map(String::trim).filter(String::isNotBlank).distinct() }
                .filterValues { it.isNotEmpty() }
            ModelInfo(
                id = definition.id.trim(),
                inputModalities = definition.inputModalities.map { it.lowercase() }.toSet().ifEmpty { setOf("text") },
                supportedParameters = options.keys,
                parameterOptions = options
            )
        }
        .distinctBy { it.id }

    suspend fun refreshIfStale(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < REFRESH_INTERVAL_MS) return@withContext false
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val request = Request.Builder()
            .url(REMOTE_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        val raw = runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext false

        val parsed = parseAndValidate(raw) ?: return@withContext false
        val previous = gson.toJson(current)
        val next = gson.toJson(parsed)
        current = parsed
        prefs.edit().putString(KEY_JSON, raw).apply()
        previous != next
    }

    private fun provider(type: ProviderType): ProviderDefinition? = when (type) {
        ProviderType.OPENROUTER -> current.providers["openrouter"]
        ProviderType.NVIDIA -> current.providers["nvidia"]
        ProviderType.OPENAI_COMPATIBLE -> null
    }

    private fun loadCached(): RegistryDocument? = prefs.getString(KEY_JSON, null)
        ?.let(::parseAndValidate)

    private fun parseAndValidate(raw: String): RegistryDocument? = runCatching {
        gson.fromJson(raw, RegistryDocument::class.java)
    }.getOrNull()?.takeIf { doc ->
        val openRouter = doc.providers["openrouter"]
        val nvidia = doc.providers["nvidia"]
        doc.version >= 1 &&
            openRouter?.textBaseUrl?.startsWith("https://") == true &&
            openRouter.imageBaseUrl.startsWith("https://") &&
            nvidia?.textBaseUrl?.startsWith("https://") == true &&
            nvidia.imageBaseUrl.startsWith("https://") &&
            nvidia.imageModels.any { it.id.isNotBlank() }
    }

    private fun fallback(): RegistryDocument = RegistryDocument(
        version = 1,
        providers = mapOf(
            "openrouter" to ProviderDefinition(
                textBaseUrl = DEFAULT_OPENROUTER_BASE_URL,
                imageBaseUrl = DEFAULT_OPENROUTER_BASE_URL,
                imageProtocol = "OPENROUTER"
            ),
            "nvidia" to ProviderDefinition(
                textBaseUrl = DEFAULT_NVIDIA_TEXT_BASE_URL,
                imageBaseUrl = DEFAULT_NVIDIA_IMAGE_BASE_URL,
                imageProtocol = "NVIDIA_NIM",
                imageModels = listOf(
                    ImageModelDefinition("black-forest-labs/flux.2-klein-4b"),
                    ImageModelDefinition(
                        "black-forest-labs/flux.1-schnell",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    ),
                    ImageModelDefinition(
                        "black-forest-labs/flux.1-dev",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    ),
                    ImageModelDefinition(
                        "stabilityai/stable-diffusion-3-medium",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    ),
                    ImageModelDefinition("stabilityai/stable-diffusion-xl")
                )
            )
        )
    )

    companion object {
        const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_NVIDIA_TEXT_BASE_URL = "https://integrate.api.nvidia.com/v1"
        const val DEFAULT_NVIDIA_IMAGE_BASE_URL = "https://ai.api.nvidia.com/v1/genai"
        const val REMOTE_URL = "https://raw.githubusercontent.com/Ayuemin/Umnik/main/docs/provider-registry.json"

        private const val KEY_JSON = "registry_json"
        private const val KEY_LAST_CHECK = "registry_last_check"
        private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private val COMMON_RATIOS = listOf("1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3")
    }
}
'''
write('app/src/main/java/com/ayuemin/ymnik/network/ProviderRegistry.kt', provider_registry)


# ---------------------------------------------------------------------------
# Native NVIDIA visual NIM adapter
# ---------------------------------------------------------------------------
nvidia_client = r'''package com.ayuemin.ymnik.network

import android.content.Context
import android.util.Base64
import com.ayuemin.ymnik.model.GeneratedFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class NvidiaImageClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()
    private val activeCallLock = Any()
    @Volatile private var activeCall: Call? = null

    fun cancelActiveRequest() {
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        aspectRatio: String? = null
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelEndpoint(baseUrl, model))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload(model, prompt, aspectRatio)).toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        synchronized(activeCallLock) { activeCall = call }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val files = extractImages(root)
                if (files.isEmpty()) error("NVIDIA NIM вернул ответ без изображения")
                OpenRouterClient.Result("Изображение создано.", files)
            }
        } finally {
            synchronized(activeCallLock) { activeCall = null }
        }
    }

    private fun payload(model: String, prompt: String, aspectRatio: String?): JsonObject = when {
        model.endsWith("stable-diffusion-3-medium") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("mode", "text-to-image")
            addProperty("model", "sd3")
            addProperty("output_format", "jpeg")
            aspectRatio?.takeIf { it in COMMON_RATIOS }?.let { addProperty("aspect_ratio", it) }
        }
        model.endsWith("stable-diffusion-xl") -> JsonObject().apply {
            add("text_prompts", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("text", prompt)
                    addProperty("weight", 1)
                })
            })
        }
        else -> JsonObject().apply {
            addProperty("prompt", prompt)
            when {
                model.contains("flux.2-klein-4b") -> {
                    addProperty("mode", "Image Generation")
                    addProperty("seed", 0)
                    addProperty("steps", 4)
                }
                model.contains("flux.1-schnell") -> {
                    addProperty("mode", "base")
                    addProperty("seed", 0)
                    addProperty("steps", 4)
                    flux1Dimensions(aspectRatio)?.let { (width, height) ->
                        addProperty("width", width)
                        addProperty("height", height)
                    }
                }
                model.contains("flux.1-dev") -> {
                    addProperty("mode", "base")
                    addProperty("seed", 0)
                    flux1Dimensions(aspectRatio)?.let { (width, height) ->
                        addProperty("width", width)
                        addProperty("height", height)
                    }
                }
            }
        }
    }

    private fun flux1Dimensions(aspectRatio: String?): Pair<Int, Int>? = when (aspectRatio) {
        "1:1" -> 1024 to 1024
        "16:9" -> 1344 to 768
        "9:16" -> 768 to 1344
        "5:4" -> 1152 to 896
        "4:5" -> 896 to 1152
        "3:2" -> 1216 to 832
        "2:3" -> 832 to 1216
        else -> null
    }

    private fun extractImages(root: JsonObject): List<GeneratedFile> {
        val out = mutableListOf<GeneratedFile>()

        fun consume(element: JsonElement, index: Int) {
            when {
                element.isJsonPrimitive -> {
                    val raw = element.asString
                    if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        out += downloadImage(raw, index)
                    } else if (raw.isNotBlank()) {
                        out += saveEncodedImage(raw, null, index)
                    }
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val encoded = listOf("base64", "b64_json", "image", "data")
                        .asSequence()
                        .mapNotNull { name -> obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
                    val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    val mime = listOf("media_type", "mime_type", "mimeType", "content_type")
                        .asSequence()
                        .mapNotNull { name -> obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString }
                        .firstOrNull()
                    when {
                        !encoded.isNullOrBlank() -> out += saveEncodedImage(encoded, mime, index)
                        !url.isNullOrBlank() -> out += downloadImage(url, index)
                    }
                }
            }
        }

        listOf("artifacts", "images", "data").forEach { name ->
            root.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.forEachIndexed { index, item -> consume(item, index) }
        }
        if (out.isEmpty()) {
            listOf("base64", "b64_json", "image").forEachIndexed { index, name ->
                root.get(name)?.let { consume(it, index) }
            }
        }
        return out.distinctBy { it.localPath }
    }

    private fun saveEncodedImage(raw: String, mimeHint: String?, index: Int): GeneratedFile {
        val cleaned = raw.substringAfter("base64,", raw).trim()
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        return saveImageBytes(bytes, mimeHint, index)
    }

    private fun downloadImage(url: String, index: Int): GeneratedFile {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        val mime = connection.contentType
        val bytes = connection.getInputStream().use { it.readBytes() }
        return saveImageBytes(bytes, mime, index)
    }

    private fun saveImageBytes(bytes: ByteArray, mimeHint: String?, index: Int): GeneratedFile {
        if (bytes.isEmpty()) error("NVIDIA NIM вернул пустое изображение")
        val mime = detectMime(bytes, mimeHint)
        val ext = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "nvidia_${System.currentTimeMillis()}_${index}.$ext")
        file.writeBytes(bytes)
        return GeneratedFile(
            id = UUID.randomUUID().toString(),
            name = file.name,
            mimeType = mime,
            localPath = file.absolutePath,
            size = file.length()
        )
    }

    private fun detectMime(bytes: ByteArray, hint: String?): String {
        val normalized = hint?.substringBefore(';')?.lowercase()?.takeIf { it.startsWith("image/") }
        if (normalized != null) return normalized
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 12 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP") return "image/webp"
        return "image/png"
    }

    private fun modelEndpoint(baseUrl: String, model: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith(model)) clean else "$clean/${model.trimStart('/')}"
    }

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.get("detail")?.let { detail ->
                when {
                    detail.isJsonPrimitive -> detail.asString
                    else -> detail.toString()
                }
            } ?: root.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return "NVIDIA NIM: HTTP $code${detail?.let { " · $it" }.orEmpty()}"
    }

    private companion object {
        val COMMON_RATIOS = setOf("1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3")
    }
}
'''
write('app/src/main/java/com/ayuemin/ymnik/network/NvidiaImageClient.kt', nvidia_client)


# ---------------------------------------------------------------------------
# Compatible image endpoint accepts either a base URL or a full endpoint URL.
# ---------------------------------------------------------------------------
compatible_path = 'app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt'
compatible = read(compatible_path)
compatible = replace_once(
    compatible,
    '.url(endpoint(baseUrl, "images/generations"))',
    '.url(imageGenerationEndpoint(baseUrl))',
    'CompatibleApiClient image endpoint'
)
compatible = replace_once(
    compatible,
    '''    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path
''',
    '''    private fun imageGenerationEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/images/generations")) clean else endpoint(clean, "images/generations")
    }

    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path
''',
    'CompatibleApiClient helper'
)
write(compatible_path, compatible)


# ---------------------------------------------------------------------------
# ChatViewModel provider adapters, migration, registry refresh and diagnostics
# ---------------------------------------------------------------------------
vm_path = 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
vm = read(vm_path)
vm = replace_once(
    vm,
    'import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ModelInfo',
    'import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ImageApiProtocol\nimport com.ayuemin.ymnik.model.ModelInfo',
    'VM ImageApiProtocol import'
)
vm = replace_once(
    vm,
    'import com.ayuemin.ymnik.network.CompatibleApiClient\nimport com.ayuemin.ymnik.network.OpenRouterClient',
    'import com.ayuemin.ymnik.network.CompatibleApiClient\nimport com.ayuemin.ymnik.network.NvidiaImageClient\nimport com.ayuemin.ymnik.network.OpenRouterClient\nimport com.ayuemin.ymnik.network.ProviderRegistry',
    'VM network imports'
)
vm = replace_once(
    vm,
    '''    private val api = OpenRouterClient(context)
    private val compatibleApi = CompatibleApiClient(context)
    private val gson = Gson()
''',
    '''    private val api = OpenRouterClient(context)
    private val compatibleApi = CompatibleApiClient(context)
    private val nvidiaImageApi = NvidiaImageClient(context)
    private val providerRegistry = ProviderRegistry(context)
    private val gson = Gson()
''',
    'VM clients'
)
vm = replace_once(
    vm,
    '''    init {
        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()
        refreshProviderUsage()
    }
''',
    '''    init {
        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()
        refreshProviderUsage()
        viewModelScope.launch {
            if (providerRegistry.refreshIfStale()) refreshModelCapabilities()
        }
    }
''',
    'VM init registry refresh'
)

vm = replace_block(
    vm,
    '    fun addCompatibleProfile(): String {',
    '    fun setConnectionEnabled(profileId: String, enabled: Boolean) {',
    r'''    fun addCompatibleProfile(): String {
        val id = UUID.randomUUID().toString()
        val profile = ConnectionProfile(
            id = id,
            name = "Другой API",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "",
            imageEnabled = false,
            imageProtocol = ImageApiProtocol.AUTO,
            useSameImageApiKey = true,
            useProviderDefaults = false
        )
        val profiles = _state.value.connectionProfiles + profile
        saveConnectionProfiles(profiles)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            quickTextModels = loadAllQuickTextModels(profiles, _state.value.disabledConnectionIds),
            status = "Подключение добавлено. Укажите адрес API."
        )
        return id
    }

    fun saveConnectionProfile(
        profileId: String,
        name: String,
        baseUrl: String,
        apiKey: String?,
        imageEnabled: Boolean,
        imageBaseUrl: String?,
        imageProtocol: ImageApiProtocol,
        useSameImageApiKey: Boolean,
        imageApiKey: String?,
        useProviderDefaults: Boolean
    ) {
        val old = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val builtIn = old.type == ProviderType.OPENROUTER || old.type == ProviderType.NVIDIA
        if ((!builtIn || !useProviderDefaults) && cleanUrl.isBlank()) {
            _state.value = _state.value.copy(status = "Укажите адрес API")
            return
        }
        val cleanImageUrl = imageBaseUrl?.let(::normalizeBaseUrl)?.takeIf { it.isNotBlank() }
        val updated = old.copy(
            name = when (old.type) {
                ProviderType.OPENROUTER -> "OpenRouter"
                ProviderType.NVIDIA -> "NVIDIA"
                ProviderType.OPENAI_COMPATIBLE -> name.trim().ifBlank { "Другой API" }
            },
            baseUrl = cleanUrl,
            imageEnabled = imageEnabled,
            imageBaseUrl = cleanImageUrl,
            imageProtocol = imageProtocol,
            useSameImageApiKey = useSameImageApiKey,
            useProviderDefaults = if (builtIn) useProviderDefaults else false
        )
        val profiles = _state.value.connectionProfiles.map { if (it.id == profileId) updated else it }
        saveConnectionProfiles(profiles)
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profileId, apiKey)
        if (useSameImageApiKey) {
            secrets.deleteProfileImageApiKey(profileId)
        } else if (!imageApiKey.isNullOrBlank()) {
            secrets.saveProfileImageApiKey(profileId, imageApiKey)
        }

        var nextImageProfileId = _state.value.imageConnectionProfileId
        if (profileId == nextImageProfileId && !imageGenerationEnabled(updated)) {
            nextImageProfileId = profiles.firstOrNull {
                it.id !in _state.value.disabledConnectionIds && imageGenerationEnabled(it) && isImageProfileConfigured(it)
            }?.id ?: profiles.firstOrNull {
                it.id !in _state.value.disabledConnectionIds && imageGenerationEnabled(it)
            }?.id ?: nextImageProfileId
            prefs.edit().putString("image_connection_profile", nextImageProfileId).apply()
        }
        val nextImageProfile = profiles.firstOrNull { it.id == nextImageProfileId } ?: updated
        val nextImageModel = loadImageModelForProfile(nextImageProfile.id)
        val active = _state.value.activeConnectionProfileId == profileId
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            apiKeyConfigured = if (active) isProfileConfigured(updated) else _state.value.apiKeyConfigured,
            imageConnectionProfileId = nextImageProfileId,
            imageModel = nextImageModel,
            imageAspectRatio = loadImageParameter("aspect_ratio", nextImageProfileId, nextImageModel),
            imageResolution = loadImageParameter("resolution", nextImageProfileId, nextImageModel),
            availableTextModels = if (active) emptyList() else _state.value.availableTextModels,
            availableImageModels = if (profileId == _state.value.imageConnectionProfileId || nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение сохранено"
        )
        if ((active || profileId == nextImageProfileId) && profileId !in _state.value.disabledConnectionIds) refreshModelCapabilities()
        if (updated.type == ProviderType.OPENROUTER) refreshProviderUsage()
    }

    fun checkConnection(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Проверяю подключение…", status = null)
            providerRegistry.refreshIfStale(force = true)
            val textCheck = runCatching { textModelsForProfile(profile) }
            val imageCheck = if (!imageGenerationEnabled(profile)) {
                null
            } else if (!isImageProfileConfigured(profile)) {
                Result.failure(IllegalStateException(imageConnectionSetupMessage(profile)))
            } else {
                runCatching { imageModelsForProfile(profile) }
            }

            val textStatus = textCheck.fold(
                onSuccess = { "Текст: ✓ ${it.size} моделей" },
                onFailure = { "Текст: ✕ ${it.message ?: "ошибка"}" }
            )
            val imageStatus = when {
                !imageGenerationEnabled(profile) -> "Изображения: выкл"
                imageCheck == null -> "Изображения: —"
                imageCheck.isSuccess -> {
                    val count = imageCheck.getOrNull().orEmpty().size
                    if (resolvedImageProtocol(profile) == ImageApiProtocol.NVIDIA_NIM)
                        "Изображения: ✓ $count моделей (без пробной генерации)"
                    else
                        "Изображения: ✓ $count моделей"
                }
                else -> "Изображения: ✕ ${imageCheck.exceptionOrNull()?.message ?: "ошибка"}"
            }
            _state.value = _state.value.copy(
                isLoading = false,
                busyLabel = null,
                status = "$textStatus · $imageStatus"
            )
        }
    }''',
    'VM add/save/check connection block'
)

# Better image fallback when a connection is disabled.
vm = replace_once(
    vm,
    '''            val fallbackImage = _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && it.id != profileId && isProfileConfigured(it)
            } ?: _state.value.connectionProfiles.firstOrNull { it.id !in disabled && it.id != profileId }
''',
    '''            val fallbackImage = _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && it.id != profileId && imageGenerationEnabled(it) && isImageProfileConfigured(it)
            } ?: _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && it.id != profileId && imageGenerationEnabled(it)
            }
''',
    'VM disabled image fallback'
)

vm = replace_block(
    vm,
    '    fun deleteConnectionProfile(profileId: String) {',
    '    fun loadConnectionModels(profileId: String) {',
    r'''    fun deleteConnectionProfile(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.type == ProviderType.OPENROUTER || profile.type == ProviderType.NVIDIA) return
        secrets.deleteProfileApiKey(profileId)
        prefs.edit()
            .remove(profilePrefKey("text_model", profileId))
            .remove(profilePrefKey("image_model", profileId))
            .remove(profilePrefKey("quick_text_models_json", profileId))
            .apply()
        val profiles = _state.value.connectionProfiles.filterNot { it.id == profileId }
        val disabled = _state.value.disabledConnectionIds - profileId
        val fallback = profiles.firstOrNull { it.id !in disabled && isProfileConfigured(it) }
            ?: profiles.firstOrNull { it.id !in disabled }
            ?: profiles.first()
        val imageFallback = profiles.firstOrNull {
            it.id !in disabled && imageGenerationEnabled(it) && isImageProfileConfigured(it)
        } ?: profiles.firstOrNull { it.id !in disabled && imageGenerationEnabled(it) }
            ?: fallback
        val nextImageProfileId = if (_state.value.imageConnectionProfileId == profileId) imageFallback.id else _state.value.imageConnectionProfileId
        if (nextImageProfileId != _state.value.imageConnectionProfileId) {
            prefs.edit().putString("image_connection_profile", nextImageProfileId).apply()
        }
        val chats = _state.value.chats.map { chat ->
            if (chat.connectionProfileId == profileId) chat.copy(
                connectionProfileId = fallback.id,
                textModelOverride = null,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        saveConnectionProfiles(profiles)
        prefs.edit().putStringSet("disabled_connection_profiles", disabled).apply()
        chatsRepository.save(chats)
        val nextImageModel = loadImageModelForProfile(nextImageProfileId)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            disabledConnectionIds = disabled,
            chats = chats,
            quickTextModels = loadAllQuickTextModels(profiles, disabled),
            imageConnectionProfileId = nextImageProfileId,
            imageModel = nextImageModel,
            imageAspectRatio = loadImageParameter("aspect_ratio", nextImageProfileId, nextImageModel),
            imageResolution = loadImageParameter("resolution", nextImageProfileId, nextImageModel),
            availableImageModels = if (nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение «${profile.name}» удалено"
        )
        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile(fallback.id)
        else if (isImageProfileConfigured(imageConnectionProfile())) refreshModelCapabilities()
    }''',
    'VM delete connection'
)

vm = replace_block(
    vm,
    '    fun loadConnectionModels(profileId: String) {',
    '    fun selectImageModel(profileId: String, model: String) {',
    r'''    fun loadConnectionModels(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching { textModelsForProfile(profile) }
                .onSuccess { infos ->
                    _state.value = _state.value.copy(
                        modelCatalogConnectionId = profile.id,
                        modelCatalog = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        busyLabel = null,
                        status = it.message ?: "Не удалось загрузить модели подключения"
                    )
                }
        }
    }

    fun loadImageConnectionModels(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        if (!imageGenerationEnabled(profile)) {
            _state.value = _state.value.copy(status = "Генерация изображений выключена для «${profile.name}»")
            return
        }
        if (!isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели изображений…", status = null)
            runCatching { imageModelsForProfile(profile) }
                .onSuccess { infos ->
                    _state.value = _state.value.copy(
                        modelCatalogConnectionId = profile.id,
                        modelCatalog = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        busyLabel = null,
                        status = it.message ?: "Не удалось загрузить модели изображений"
                    )
                }
        }
    }''',
    'VM model loaders'
)

# selectImageModel configuration check
vm = replace_once(
    vm,
    '''        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        prefs.edit()
            .putString("image_connection_profile", profile.id)
''',
    '''        if (!imageGenerationEnabled(profile)) {
            _state.value = _state.value.copy(status = "Генерация изображений выключена для «${profile.name}»")
            return
        }
        if (!isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return
        }
        prefs.edit()
            .putString("image_connection_profile", profile.id)
''',
    'VM selectImageModel check'
)

# setMode image configuration
vm = replace_once(
    vm,
    '''            if (imageProfile.id in _state.value.disabledConnectionIds || !isProfileConfigured(imageProfile)) {
                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте выбранное подключение")
                return
            }
''',
    '''            if (imageProfile.id in _state.value.disabledConnectionIds || !imageGenerationEnabled(imageProfile) || !isImageProfileConfigured(imageProfile)) {
                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте Image API выбранного подключения")
                return
            }
''',
    'VM setMode image config'
)

vm = replace_once(
    vm,
    '''    fun defaultTextModelForConnection(profileId: String): String {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return ""
        return loadTextModelForProfile(profile)
    }
''',
    '''    fun defaultTextModelForConnection(profileId: String): String {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return ""
        return loadTextModelForProfile(profile)
    }

    fun defaultImageModelForConnection(profileId: String): String = loadImageModelForProfile(profileId)

    fun connectionTextEndpoint(profileId: String): String = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::effectiveTextBaseUrl)
        .orEmpty()

    fun connectionImageEndpoint(profileId: String): String = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::effectiveImageBaseUrl)
        .orEmpty()

    fun connectionImageEnabled(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::imageGenerationEnabled)
        ?: false

    fun connectionUsesSameImageKey(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::usesSameImageApiKey)
        ?: true

    fun connectionUsesProviderDefaults(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::usesProviderDefaults)
        ?: false
''',
    'VM public connection helpers'
)

# Replace refreshModels implementation.
vm = replace_block(
    vm,
    '    fun refreshModels(mode: ChatMode) {',
    '    fun refreshProviderUsage(delayMs: Long = 0L) {',
    r'''    fun refreshModels(mode: ChatMode) {
        val profile = if (mode == ChatMode.IMAGE) imageConnectionProfile() else activeConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (mode == ChatMode.IMAGE) {
            if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
                _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
                return
            }
        } else if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                if (mode == ChatMode.TEXT) textModelsForProfile(profile) else imageModelsForProfile(profile)
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        var selectedModel = loadTextModelForProfile(profile)
                        if (profile.type != ProviderType.OPENROUTER && infos.none { it.id == selectedModel }) {
                            selectedModel = infos.firstOrNull()?.id.orEmpty()
                            if (selectedModel.isNotBlank()) {
                                prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                            }
                        }
                        val effectiveId = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } } ?: selectedModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            textModel = selectedModel,
                            currentChatTextModel = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } },
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> {
                        val selectedModel = chooseImageModel(profile, infos)
                        val info = infos.firstOrNull { it.id == selectedModel }
                        _state.value.copy(
                            availableImageModels = infos,
                            imageConnectionProfileId = profile.id,
                            imageModel = selectedModel,
                            imageAspectRatio = validatedImageParameter("aspect_ratio", profile.id, selectedModel, info),
                            imageResolution = validatedImageParameter("resolution", profile.id, selectedModel, info),
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Не удалось загрузить модели"
                )
            }
        }
    }''',
    'VM refreshModels'
)

# OpenRouter usage should follow the effective provider endpoint.
vm = vm.replace('runCatching { api.keyUsage(key, profile.baseUrl) }', 'runCatching { api.keyUsage(key, effectiveTextBaseUrl(profile)) }')

# Replace model capability refresh.
vm = replace_block(
    vm,
    '    private fun refreshModelCapabilities() {',
    '    private fun currentTextModelId(): String =',
    r'''    private fun refreshModelCapabilities() {
        val profile = activeConnectionProfile()
        val imageProfile = imageConnectionProfile()
        viewModelScope.launch {
            val textInfos = if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) {
                runCatching { textModelsForProfile(profile) }.getOrNull()
            } else null
            val imageInfos = if (
                imageProfile.id !in _state.value.disabledConnectionIds &&
                imageGenerationEnabled(imageProfile) &&
                isImageProfileConfigured(imageProfile)
            ) {
                runCatching { imageModelsForProfile(imageProfile) }.getOrNull()
            } else emptyList()

            var next = _state.value
            if (textInfos != null) {
                var selectedModel = loadTextModelForProfile(profile)
                if (profile.type != ProviderType.OPENROUTER && textInfos.none { it.id == selectedModel }) {
                    selectedModel = textInfos.firstOrNull()?.id.orEmpty()
                    if (selectedModel.isNotBlank()) prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                }
                val effectiveId = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } } ?: selectedModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    textModel = selectedModel,
                    currentChatTextModel = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } },
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            } else {
                next = next.copy(availableTextModels = emptyList(), reasoningEnabled = false)
            }

            val safeImageInfos = imageInfos ?: emptyList()
            val selectedImageModel = chooseImageModel(imageProfile, safeImageInfos)
            val selectedImageInfo = safeImageInfos.firstOrNull { it.id == selectedImageModel }
            next = next.copy(
                availableImageModels = safeImageInfos,
                imageConnectionProfileId = imageProfile.id,
                imageModel = selectedImageModel,
                imageAspectRatio = validatedImageParameter("aspect_ratio", imageProfile.id, selectedImageModel, selectedImageInfo),
                imageResolution = validatedImageParameter("resolution", imageProfile.id, selectedImageModel, selectedImageInfo),
                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)
            )
            _state.value = next
        }
    }

    private fun currentTextModelId(): String =''',
    'VM refreshModelCapabilities'
)

# Remove duplicate '=' introduced by marker retention if present.
vm = vm.replace('private fun currentTextModelId(): String = =', 'private fun currentTextModelId(): String =')

# Update image attachment policy.
vm = replace_block(
    vm,
    '    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {',
    '    private fun attachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {',
    r'''    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {
        if (!attachment.mimeType.startsWith("image/")) {
            return false to "Для генерации изображения можно добавить только изображение-референс"
        }
        val profile = imageConnectionProfile()
        if (profile.type != ProviderType.OPENROUTER) {
            return false to when (resolvedImageProtocol(profile)) {
                ImageApiProtocol.NVIDIA_NIM -> "Облачный NVIDIA NIM сейчас принимает в Umnik текстовый промпт; произвольные референсы для этого API не отправляются"
                else -> "Формат image edit у этого API не стандартизирован; используйте текстовый промпт без референса"
            }
        }
        val info = currentImageModelInfo()
        return if (info == null || info.accepts("image")) true to null
        else false to "Выбранная модель изображений не принимает изображения-референсы"
    }''',
    'VM imageAttachmentAllowed'
)

# prepareImageGeneration checks the image API, not only the text connection.
vm = replace_once(
    vm,
    '''        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return false
        }
''',
    '''        if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return false
        }
''',
    'VM prepareImageGeneration config'
)

# stop/cancel NVIDIA visual requests too.
vm = replace_once(
    vm,
    '''        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''',
    '''        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        nvidiaImageApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''',
    'VM stopGeneration cancel'
)

# send(): image mode must use image configuration/key.
vm = replace_once(
    vm,
    '''        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
''',
    '''        if (_state.value.mode == ChatMode.IMAGE) {
            if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
                _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
                return
            }
        } else if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = if (_state.value.mode == ChatMode.IMAGE) imageApiKey(profile) else secrets.getProfileApiKey(profile.id).orEmpty()
''',
    'VM send config/key'
)

# Text endpoints in send() follow registry/manual overrides.
vm = vm.replace('                                profile.baseUrl\n                            )', '                                effectiveTextBaseUrl(profile)\n                            )', 1)
vm = vm.replace('                                profile.baseUrl,\n                                textModel,', '                                effectiveTextBaseUrl(profile),\n                                textModel,', 1)

# Replace image generation branches in send() and sendImagePrompt().
old_branch = r'''                        if (profile.type == ProviderType.OPENROUTER) {
                            generateOpenRouterImageWithResolutionFallback(
                                profileId = profile.id,
                                apiKey = key,
                                model = imageModel,
                                prompt = imagePrompt,
                                attachments = pending + projectImages,
                                baseUrl = profile.baseUrl,
                                aspectRatio = imageAspectRatio,
                                resolution = imageResolution
                            )
                        } else {
                            compatibleApi.generateImage(key, profile.baseUrl, imageModel, imagePrompt)
                        }'''
new_branch = r'''                        generateImageForProfile(
                            profile = profile,
                            apiKey = key,
                            model = imageModel,
                            prompt = imagePrompt,
                            attachments = pending + projectImages,
                            aspectRatio = imageAspectRatio,
                            resolution = imageResolution
                        )'''
vm = replace_once(vm, old_branch, new_branch, 'VM send image branch')

# sendImagePrompt config and key.
vm = replace_once(
    vm,
    '''        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return false
        }

        val clean = text.trim()
''',
    '''        if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return false
        }

        val clean = text.trim()
''',
    'VM sendImagePrompt config'
)
vm = replace_once(
    vm,
    '        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        val imageModel = _state.value.imageModel',
    '        val key = imageApiKey(profile)\n        val imageModel = _state.value.imageModel',
    'VM sendImagePrompt key'
)
old_branch2 = r'''                if (profile.type == ProviderType.OPENROUTER) {
                    generateOpenRouterImageWithResolutionFallback(
                        profileId = profile.id,
                        apiKey = key,
                        model = imageModel,
                        prompt = prompt,
                        attachments = pending,
                        baseUrl = profile.baseUrl,
                        aspectRatio = imageAspectRatio,
                        resolution = imageResolution
                    )
                } else {
                    compatibleApi.generateImage(
                        apiKey = key,
                        baseUrl = profile.baseUrl,
                        model = imageModel,
                        prompt = prompt
                    )
                }'''
new_branch2 = r'''                generateImageForProfile(
                    profile = profile,
                    apiKey = key,
                    model = imageModel,
                    prompt = prompt,
                    attachments = pending,
                    aspectRatio = imageAspectRatio,
                    resolution = imageResolution
                )'''
vm = replace_once(vm, old_branch2, new_branch2, 'VM sendImagePrompt image branch')

# Add adapter dispatcher before existing OpenRouter fallback helper.
marker = '    private suspend fun generateOpenRouterImageWithResolutionFallback('
idx = vm.find(marker)
if idx < 0:
    raise RuntimeError('VM OpenRouter image helper marker missing')
generate_dispatch = r'''    private suspend fun generateImageForProfile(
        profile: ConnectionProfile,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>,
        aspectRatio: String?,
        resolution: String?
    ): OpenRouterClient.Result {
        if (profile.type == ProviderType.OPENROUTER) {
            return generateOpenRouterImageWithResolutionFallback(
                profileId = profile.id,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = effectiveImageBaseUrl(profile),
                aspectRatio = aspectRatio,
                resolution = resolution
            )
        }
        return when (resolvedImageProtocol(profile)) {
            ImageApiProtocol.NVIDIA_NIM -> nvidiaImageApi.generateImage(
                apiKey = apiKey,
                baseUrl = effectiveImageBaseUrl(profile),
                model = model,
                prompt = prompt,
                aspectRatio = aspectRatio
            )
            ImageApiProtocol.OPENAI_COMPATIBLE, ImageApiProtocol.AUTO -> compatibleApi.generateImage(
                apiKey = apiKey,
                baseUrl = effectiveImageBaseUrl(profile),
                model = model,
                prompt = prompt
            )
        }
    }

'''
vm = vm[:idx] + generate_dispatch + vm[idx:]

# onCleared NVIDIA cancellation.
vm = replace_once(
    vm,
    '''    override fun onCleared() {
        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''',
    '''    override fun onCleared() {
        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        nvidiaImageApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''',
    'VM onCleared cancel'
)

# Replace provider/profile helper section.
vm = replace_block(
    vm,
    '    private fun defaultOpenRouterProfile() = ConnectionProfile(',
    '    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {',
    r'''    private fun defaultOpenRouterProfile() = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL,
        imageEnabled = true,
        imageProtocol = ImageApiProtocol.AUTO,
        useSameImageApiKey = true,
        useProviderDefaults = true
    )

    private fun defaultNvidiaProfile() = ConnectionProfile(
        id = "nvidia",
        name = "NVIDIA",
        type = ProviderType.NVIDIA,
        baseUrl = ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL,
        imageEnabled = true,
        imageProtocol = ImageApiProtocol.AUTO,
        useSameImageApiKey = true,
        useProviderDefaults = true
    )

    private fun loadConnectionProfiles(): List<ConnectionProfile> = runCatching {
        val type = object : TypeToken<List<ConnectionProfile>>() {}.type
        val stored = gson.fromJson<List<ConnectionProfile>>(
            prefs.getString("connection_profiles_json", "[]") ?: "[]",
            type
        ).orEmpty().filter { it.id.isNotBlank() }

        val storedOpenRouter = stored.firstOrNull { it.id == "openrouter" }
        val openRouter = storedOpenRouter?.copy(
            name = "OpenRouter",
            type = ProviderType.OPENROUTER,
            baseUrl = normalizeBaseUrl(storedOpenRouter.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL },
            imageEnabled = storedOpenRouter.imageEnabled ?: true,
            imageProtocol = storedOpenRouter.imageProtocol ?: ImageApiProtocol.AUTO,
            useSameImageApiKey = storedOpenRouter.useSameImageApiKey ?: true,
            useProviderDefaults = storedOpenRouter.useProviderDefaults ?: (
                normalizeBaseUrl(storedOpenRouter.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL } == ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL
            )
        ) ?: defaultOpenRouterProfile()

        val nvidiaSource = stored.firstOrNull { profile ->
            profile.id != "openrouter" && (
                profile.type == ProviderType.NVIDIA ||
                    profile.id.equals("nvidia", true) ||
                    profile.name.equals("nvidia", true) ||
                    normalizeBaseUrl(profile.baseUrl).contains("integrate.api.nvidia.com", ignoreCase = true)
                )
        }
        val nvidia = nvidiaSource?.copy(
            name = "NVIDIA",
            type = ProviderType.NVIDIA,
            baseUrl = normalizeBaseUrl(nvidiaSource.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL },
            imageEnabled = nvidiaSource.imageEnabled ?: true,
            imageProtocol = nvidiaSource.imageProtocol ?: ImageApiProtocol.AUTO,
            useSameImageApiKey = nvidiaSource.useSameImageApiKey ?: true,
            useProviderDefaults = nvidiaSource.useProviderDefaults ?: (
                normalizeBaseUrl(nvidiaSource.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL } == ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL
            )
        ) ?: defaultNvidiaProfile()

        val remaining = stored.filterNot { it.id == "openrouter" || it.id == nvidiaSource?.id }
            .map { profile ->
                profile.copy(
                    imageEnabled = profile.imageEnabled ?: !prefs.getString(profilePrefKey("image_model", profile.id), null).isNullOrBlank(),
                    imageProtocol = profile.imageProtocol ?: ImageApiProtocol.AUTO,
                    useSameImageApiKey = profile.useSameImageApiKey ?: true,
                    useProviderDefaults = false
                )
            }
        listOf(openRouter, nvidia) + remaining
    }.getOrElse { listOf(defaultOpenRouterProfile(), defaultNvidiaProfile()) }

    private fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        prefs.edit().putString("connection_profiles_json", gson.toJson(profiles)).apply()
    }

    private fun openRouterProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER } ?: defaultOpenRouterProfile()

    private fun activeConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }
            ?: openRouterProfile()

    private fun imageConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.imageConnectionProfileId }
            ?: openRouterProfile()

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

    private fun usesProviderDefaults(profile: ConnectionProfile): Boolean = profile.useProviderDefaults ?: when (profile.type) {
        ProviderType.OPENROUTER -> normalizeBaseUrl(profile.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL } == ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL
        ProviderType.NVIDIA -> normalizeBaseUrl(profile.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL } == ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL
        ProviderType.OPENAI_COMPATIBLE -> false
    }

    private fun effectiveTextBaseUrl(profile: ConnectionProfile): String {
        if (usesProviderDefaults(profile)) {
            providerRegistry.textBaseUrl(profile.type)?.let { return normalizeBaseUrl(it) }
        }
        return normalizeBaseUrl(profile.baseUrl)
    }

    private fun effectiveImageBaseUrl(profile: ConnectionProfile): String {
        if (usesProviderDefaults(profile)) {
            providerRegistry.imageBaseUrl(profile.type)?.let { return normalizeBaseUrl(it) }
        }
        profile.imageBaseUrl?.let(::normalizeBaseUrl)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (profile.type) {
            ProviderType.NVIDIA -> ProviderRegistry.DEFAULT_NVIDIA_IMAGE_BASE_URL
            else -> effectiveTextBaseUrl(profile)
        }
    }

    private fun imageGenerationEnabled(profile: ConnectionProfile): Boolean = profile.imageEnabled ?: when (profile.type) {
        ProviderType.OPENROUTER, ProviderType.NVIDIA -> true
        ProviderType.OPENAI_COMPATIBLE -> false
    }

    private fun usesSameImageApiKey(profile: ConnectionProfile): Boolean = profile.useSameImageApiKey ?: true

    private fun imageApiKey(profile: ConnectionProfile): String = if (usesSameImageApiKey(profile)) {
        secrets.getProfileApiKey(profile.id).orEmpty()
    } else {
        secrets.getProfileImageApiKey(profile.id).orEmpty()
    }

    private fun resolvedImageProtocol(profile: ConnectionProfile): ImageApiProtocol {
        val explicit = profile.imageProtocol ?: ImageApiProtocol.AUTO
        if (explicit != ImageApiProtocol.AUTO) return explicit
        return if (profile.type == ProviderType.NVIDIA) ImageApiProtocol.NVIDIA_NIM else ImageApiProtocol.OPENAI_COMPATIBLE
    }

    private fun isProfileConfigured(profile: ConnectionProfile): Boolean = when (profile.type) {
        ProviderType.OPENROUTER, ProviderType.NVIDIA -> effectiveTextBaseUrl(profile).isNotBlank() && !secrets.getProfileApiKey(profile.id).isNullOrBlank()
        ProviderType.OPENAI_COMPATIBLE -> effectiveTextBaseUrl(profile).isNotBlank()
    }

    private fun isImageProfileConfigured(profile: ConnectionProfile): Boolean {
        if (!imageGenerationEnabled(profile) || effectiveImageBaseUrl(profile).isBlank()) return false
        return when (profile.type) {
            ProviderType.OPENROUTER, ProviderType.NVIDIA -> imageApiKey(profile).isNotBlank()
            ProviderType.OPENAI_COMPATIBLE -> true
        }
    }

    private fun connectionSetupMessage(profile: ConnectionProfile): String = when (profile.type) {
        ProviderType.OPENROUTER -> "Откройте «Подключения» и сохраните API-ключ OpenRouter"
        ProviderType.NVIDIA -> "Откройте «Подключения» и сохраните API-ключ NVIDIA"
        ProviderType.OPENAI_COMPATIBLE -> "Откройте «Подключения» и укажите адрес совместимого API"
    }

    private fun imageConnectionSetupMessage(profile: ConnectionProfile): String = when {
        !imageGenerationEnabled(profile) -> "В «Подключениях» включите генерацию изображений для «${profile.name}»"
        effectiveImageBaseUrl(profile).isBlank() -> "Укажите адрес API изображений для «${profile.name}»"
        !usesSameImageApiKey(profile) && imageApiKey(profile).isBlank() -> "Укажите отдельный API-ключ изображений для «${profile.name}»"
        profile.type == ProviderType.OPENROUTER && imageApiKey(profile).isBlank() -> "Сохраните API-ключ OpenRouter"
        profile.type == ProviderType.NVIDIA && imageApiKey(profile).isBlank() -> "Сохраните API-ключ NVIDIA"
        else -> "Проверьте настройки Image API для «${profile.name}»"
    }

    private suspend fun textModelsForProfile(profile: ConnectionProfile): List<ModelInfo> {
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        return if (profile.type == ProviderType.OPENROUTER) {
            api.models(key, effectiveTextBaseUrl(profile))
        } else {
            compatibleApi.models(key, effectiveTextBaseUrl(profile))
        }
    }

    private suspend fun imageModelsForProfile(profile: ConnectionProfile): List<ModelInfo> {
        if (!imageGenerationEnabled(profile)) return emptyList()
        val key = imageApiKey(profile)
        if (profile.type == ProviderType.OPENROUTER) {
            return api.imageModels(key, effectiveImageBaseUrl(profile))
        }
        return when (resolvedImageProtocol(profile)) {
            ImageApiProtocol.NVIDIA_NIM -> {
                val registryModels = providerRegistry.imageModels(ProviderType.NVIDIA)
                val saved = loadImageModelForProfile(profile.id)
                if (profile.type == ProviderType.OPENAI_COMPATIBLE && saved.isNotBlank() && registryModels.none { it.id == saved }) {
                    listOf(ModelInfo(saved)) + registryModels
                } else registryModels
            }
            ImageApiProtocol.OPENAI_COMPATIBLE, ImageApiProtocol.AUTO -> {
                val saved = loadImageModelForProfile(profile.id)
                if (saved.isBlank()) emptyList() else listOf(ModelInfo(saved))
            }
        }
    }

    private fun chooseImageModel(profile: ConnectionProfile, infos: List<ModelInfo>): String {
        var selected = loadImageModelForProfile(profile.id)
        if (infos.isNotEmpty() && infos.none { it.id == selected }) {
            selected = infos.first().id
            prefs.edit().putString(profilePrefKey("image_model", profile.id), selected).apply()
        }
        return selected
    }

    private fun profilePrefKey(base: String, profileId: String): String =
        if (profileId == "openrouter") base else "${base}_profile_$profileId"

    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        return prefs.getString(profilePrefKey("text_model", profile.id), fallback) ?: fallback
    }

    private fun loadImageModelForProfile(profileId: String): String {
        val profile = initialProfiles.firstOrNull { it.id == profileId }
            ?: runCatching { _state.value.connectionProfiles.firstOrNull { it.id == profileId } }.getOrNull()
        val fallback = when (profile?.type) {
            ProviderType.OPENROUTER -> "bytedance-seed/seedream-4.5"
            ProviderType.NVIDIA -> providerRegistry.imageModels(ProviderType.NVIDIA).firstOrNull()?.id.orEmpty()
            else -> ""
        }
        return prefs.getString(profilePrefKey("image_model", profileId), fallback) ?: fallback
    }

    private fun imageParameterPrefKey(parameter: String, profileId: String, modelId: String): String =
        "image_parameter_${parameter}_${profileId}_${modelId}"

    private fun loadImageParameter(parameter: String, profileId: String, modelId: String): String? =
        prefs.getString(imageParameterPrefKey(parameter, profileId, modelId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun validatedImageParameter(
        parameter: String,
        profileId: String,
        modelId: String,
        info: ModelInfo?
    ): String? {
        val stored = loadImageParameter(parameter, profileId, modelId) ?: return null
        if (info == null) return stored
        return stored.takeIf { it in info.parameterValues(parameter) }
    }''',
    'VM provider helper section'
)

# Initial image profile should only use image-enabled connections.
vm = replace_once(
    vm,
    '''    private val initialImageProfileId = prefs.getString("image_connection_profile", "openrouter")
        ?.takeIf { id -> initialProfiles.any { it.id == id } && id !in initialDisabledConnectionIds }
        ?: initialProfiles.firstOrNull { it.id == "openrouter" && it.id !in initialDisabledConnectionIds }?.id
        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds }?.id
        ?: "openrouter"
''',
    '''    private val initialImageProfileId = prefs.getString("image_connection_profile", "openrouter")
        ?.takeIf { id -> initialProfiles.any { it.id == id && imageGenerationEnabled(it) } && id !in initialDisabledConnectionIds }
        ?: initialProfiles.firstOrNull { it.type == ProviderType.OPENROUTER && it.id !in initialDisabledConnectionIds && imageGenerationEnabled(it) }?.id
        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds && imageGenerationEnabled(it) }?.id
        ?: "openrouter"
''',
    'VM initial image profile'
)

write(vm_path, vm)


# ---------------------------------------------------------------------------
# Compose UI: provider labels, advanced Image API settings and clean model picker
# ---------------------------------------------------------------------------
ui_path = 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
ui = read(ui_path)
ui = replace_once(
    ui,
    'import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ModelInfo',
    'import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ImageApiProtocol\nimport com.ayuemin.ymnik.model.ModelInfo',
    'UI ImageApiProtocol import'
)
ui = replace_once(
    ui,
    '''    val imageConnectionAvailable = imageProfile.id !in state.disabledConnectionIds
''',
    '''    val imageConnectionAvailable = imageProfile.id !in state.disabledConnectionIds && vm.connectionImageEnabled(imageProfile.id)
''',
    'UI image connection availability'
)

# Add settings editor state.
ui = replace_once(
    ui,
    '''    var connectionName by remember(editingProfile.id, editingProfile.name) { mutableStateOf(editingProfile.name) }
    var connectionUrl by remember(editingProfile.id, editingProfile.baseUrl) { mutableStateOf(editingProfile.baseUrl) }
    var connectionKey by remember(editingProfile.id) { mutableStateOf("") }
''',
    '''    var connectionName by remember(editingProfile.id, editingProfile.name) { mutableStateOf(editingProfile.name) }
    var connectionUrl by remember(editingProfile.id, editingProfile.baseUrl, editingProfile.useProviderDefaults) {
        mutableStateOf(vm.connectionTextEndpoint(editingProfile.id))
    }
    var connectionKey by remember(editingProfile.id) { mutableStateOf("") }
    var connectionAdvancedExpanded by remember(editingProfile.id) { mutableStateOf(false) }
    var connectionImageEnabled by remember(editingProfile.id, editingProfile.imageEnabled) {
        mutableStateOf(vm.connectionImageEnabled(editingProfile.id))
    }
    var connectionImageUrl by remember(editingProfile.id, editingProfile.imageBaseUrl, editingProfile.useProviderDefaults) {
        mutableStateOf(vm.connectionImageEndpoint(editingProfile.id))
    }
    var connectionImageProtocol by remember(editingProfile.id, editingProfile.imageProtocol) {
        mutableStateOf(editingProfile.imageProtocol ?: ImageApiProtocol.AUTO)
    }
    var connectionSameImageKey by remember(editingProfile.id, editingProfile.useSameImageApiKey) {
        mutableStateOf(vm.connectionUsesSameImageKey(editingProfile.id))
    }
    var connectionImageKey by remember(editingProfile.id) { mutableStateOf("") }
    var connectionUseProviderDefaults by remember(editingProfile.id, editingProfile.useProviderDefaults, editingProfile.baseUrl) {
        mutableStateOf(vm.connectionUsesProviderDefaults(editingProfile.id))
    }
''',
    'UI connection editor state'
)

# Image settings subtitle blank handling.
ui = ui.replace(
    'subtitle = "${state.imageModel.substringAfterLast(\'/\').ifBlank { state.imageModel }} · $imageConnectionName",',
    'subtitle = "${state.imageModel.substringAfterLast(\'/\').ifBlank { "не выбрана" }} · $imageConnectionName",',
    1
)

# Replace the whole Connections settings card.
connections_start = '''            item {
                val enabledCount = state.connectionProfiles.count { it.id !in state.disabledConnectionIds }
                ExpandableSettingsCard(
                    title = "Подключения",'''
connections_end = '''            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),'''
new_connections = r'''            item {
                val enabledCount = state.connectionProfiles.count { it.id !in state.disabledConnectionIds }
                ExpandableSettingsCard(
                    title = "Подключения",
                    subtitle = "Включено: $enabledCount из ${state.connectionProfiles.size}",
                    icon = Icons.Outlined.Language,
                    expanded = connectionsExpanded,
                    onToggle = { connectionsExpanded = !connectionsExpanded }
                ) {
                    Text(
                        "Для OpenRouter и NVIDIA Umnik знает стандартные адреса сам. Для других сервисов можно настроить отдельный Image API в дополнительных параметрах.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(9.dp))
                    state.connectionProfiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    editingProfileId = profile.id
                                    connectionAdvancedExpanded = false
                                    connectionKey = ""
                                    connectionImageKey = ""
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        profile.name,
                                        fontWeight = if (editingProfileId == profile.id) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        providerTypeLabel(profile.type),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            Switch(
                                checked = profile.id !in state.disabledConnectionIds,
                                onCheckedChange = { vm.setConnectionEnabled(profile.id, it) }
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val id = vm.addCompatibleProfile()
                            editingProfileId = id
                            connectionAdvancedExpanded = true
                            connectionKey = ""
                            connectionImageKey = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить подключение")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        editingProfile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE) {
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = connectionName,
                            onValueChange = { connectionName = it.take(60) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = connectionKey,
                        onValueChange = { connectionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API-ключ") },
                        placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { vm.checkConnection(editingProfile.id) },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Проверить подключение")
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { connectionAdvancedExpanded = !connectionAdvancedExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (connectionAdvancedExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Дополнительные настройки")
                    }
                    if (connectionAdvancedExpanded) {
                        if (editingProfile.type != ProviderType.OPENAI_COMPATIBLE) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Автоматические адреса")
                                    Text(
                                        "Получать актуальные стандартные адреса из реестра Umnik",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = connectionUseProviderDefaults,
                                    onCheckedChange = { connectionUseProviderDefaults = it }
                                )
                            }
                            Spacer(Modifier.height(7.dp))
                        }
                        OutlinedTextField(
                            value = connectionUrl,
                            onValueChange = { connectionUrl = it.trim().take(300) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Адрес API текста") },
                            placeholder = { Text("https://example.com/v1") },
                            singleLine = true,
                            enabled = editingProfile.type == ProviderType.OPENAI_COMPATIBLE || !connectionUseProviderDefaults
                        )
                        Text(
                            if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE)
                                "Для текста используются стандартные /models и /chat/completions."
                            else if (connectionUseProviderDefaults)
                                "Адрес обновляется из реестра провайдеров; встроенная копия остаётся запасным вариантом."
                            else
                                "Ручной адрес имеет приоритет над встроенным реестром.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Генерация изображений")
                                Text(
                                    "Подключить Image API этого сервиса",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = connectionImageEnabled, onCheckedChange = { connectionImageEnabled = it })
                        }
                        if (connectionImageEnabled) {
                            Spacer(Modifier.height(8.dp))
                            if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE) {
                                Text("Протокол изображений", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(5.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(ImageApiProtocol.entries) { protocol ->
                                        FilterChip(
                                            selected = connectionImageProtocol == protocol,
                                            onClick = { connectionImageProtocol = protocol },
                                            label = { Text(imageProtocolLabel(protocol)) }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    if (editingProfile.type == ProviderType.NVIDIA)
                                        "Протокол изображений определяется автоматически: NVIDIA NIM."
                                    else
                                        "Протокол изображений определяется автоматически: OpenRouter Image API.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(7.dp))
                            OutlinedTextField(
                                value = connectionImageUrl,
                                onValueChange = { connectionImageUrl = it.trim().take(320) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Адрес API изображений") },
                                placeholder = { Text("https://example.com/v1") },
                                singleLine = true,
                                enabled = editingProfile.type == ProviderType.OPENAI_COMPATIBLE || !connectionUseProviderDefaults
                            )
                            Text(
                                "Для известных провайдеров Umnik подставляет этот адрес сам. Для своего сервера можно указать отдельный адрес.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Использовать тот же API-ключ", modifier = Modifier.weight(1f))
                                Switch(checked = connectionSameImageKey, onCheckedChange = { connectionSameImageKey = it })
                            }
                            if (!connectionSameImageKey) {
                                Spacer(Modifier.height(7.dp))
                                OutlinedTextField(
                                    value = connectionImageKey,
                                    onValueChange = { connectionImageKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("API-ключ изображений") },
                                    placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveConnectionProfile(
                                profileId = editingProfile.id,
                                name = connectionName,
                                baseUrl = connectionUrl,
                                apiKey = connectionKey.takeIf { it.isNotBlank() },
                                imageEnabled = connectionImageEnabled,
                                imageBaseUrl = connectionImageUrl.takeIf { it.isNotBlank() },
                                imageProtocol = if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE) connectionImageProtocol else ImageApiProtocol.AUTO,
                                useSameImageApiKey = connectionSameImageKey,
                                imageApiKey = connectionImageKey.takeIf { it.isNotBlank() },
                                useProviderDefaults = if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE) false else connectionUseProviderDefaults
                            )
                            connectionKey = ""
                            connectionImageKey = ""
                        },
                        enabled = connectionUrl.isNotBlank() || (editingProfile.type != ProviderType.OPENAI_COMPATIBLE && connectionUseProviderDefaults),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить подключение") }
                    if (editingProfile.type == ProviderType.OPENAI_COMPATIBLE) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                vm.deleteConnectionProfile(editingProfile.id)
                                editingProfileId = state.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }?.id ?: "openrouter"
                                connectionKey = ""
                                connectionImageKey = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить подключение")
                        }
                    }
                    Text(
                        "Ключи хранятся локально и шифруются через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

'''
ui = replace_block(ui, connections_start, connections_end, new_connections + connections_end, 'UI connections card')
# replace_block retains end marker via new content above; remove accidental duplicate marker if present.
dupe = connections_end + '\n' + connections_end
ui = ui.replace(dupe, connections_end, 1)

# Replace ModelPickerDialog with capability-aware picker and manual custom image model entry.
model_picker_start = '''@Composable
private fun ModelPickerDialog(
    mode: ChatMode,'''
model_picker_end = '''@Composable
private fun EmptyChatCard(mode: ChatMode) {'''
new_picker = r'''@Composable
private fun ModelPickerDialog(
    mode: ChatMode,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var query by remember(mode) { mutableStateOf("") }
    val enabledConnections = state.connectionProfiles.filter { profile ->
        profile.id !in state.disabledConnectionIds && (mode == ChatMode.TEXT || vm.connectionImageEnabled(profile.id))
    }
    var selectedTextConnectionId by remember(mode, enabledConnections.map { it.id }) {
        mutableStateOf(
            state.activeConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }
                ?: enabledConnections.firstOrNull()?.id
        )
    }
    var selectedImageConnectionId by remember(mode, enabledConnections.map { it.id }) {
        mutableStateOf(
            state.imageConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }
                ?: enabledConnections.firstOrNull()?.id
        )
    }
    val selectedTextConnection = enabledConnections.firstOrNull { it.id == selectedTextConnectionId }
    val selectedImageConnection = enabledConnections.firstOrNull { it.id == selectedImageConnectionId }
    val selectedConnectionId = if (mode == ChatMode.TEXT) selectedTextConnectionId else selectedImageConnectionId
    val selectedConnection = if (mode == ChatMode.TEXT) selectedTextConnection else selectedImageConnection
    val models = when {
        state.modelCatalogConnectionId == selectedConnectionId -> state.modelCatalog
        mode == ChatMode.TEXT && selectedConnectionId == state.activeConnectionProfileId -> state.availableTextModels
        mode == ChatMode.IMAGE && selectedConnectionId == state.imageConnectionProfileId -> state.availableImageModels
        else -> emptyList()
    }
    val current = if (mode == ChatMode.TEXT) {
        selectedTextConnectionId?.let(vm::defaultTextModelForConnection).orEmpty()
    } else {
        selectedImageConnectionId?.let(vm::defaultImageModelForConnection).orEmpty()
    }
    var manualImageModel by remember(selectedImageConnectionId, current) { mutableStateOf(current) }

    LaunchedEffect(mode, selectedTextConnectionId, selectedImageConnectionId) {
        if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
        else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
    }

    val filtered = remember(models, query) {
        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(300)
    }
    val customImageConnection = mode == ChatMode.IMAGE && selectedConnection?.type == ProviderType.OPENAI_COMPATIBLE

    FullScreenPanel(
        title = if (mode == ChatMode.TEXT) "Текстовая модель" else "Модель изображений",
        onBack = onDismiss
    ) {
        Text(
            "Сейчас: ${selectedConnection?.name ?: "Подключение"} · ${current.ifBlank { "не выбрана" }}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (enabledConnections.isEmpty()) {
            Text(
                if (mode == ChatMode.IMAGE) "Нет подключений с включённой генерацией изображений." else "Нет включённых подключений.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(enabledConnections, key = { it.id }) { connection ->
                    FilterChip(
                        selected = selectedConnectionId == connection.id,
                        onClick = {
                            if (mode == ChatMode.TEXT) selectedTextConnectionId = connection.id
                            else selectedImageConnectionId = connection.id
                            query = ""
                        },
                        label = { Text(connection.name, maxLines = 1) }
                    )
                }
            }
            if (mode == ChatMode.IMAGE) {
                Text(
                    when (selectedConnection?.type) {
                        ProviderType.OPENROUTER -> "Показаны только модели OpenRouter Image API."
                        ProviderType.NVIDIA -> "Показаны только генераторы изображений NVIDIA NIM из обновляемого реестра Umnik."
                        ProviderType.OPENAI_COMPATIBLE -> "У произвольного API нет универсального каталога генераторов. Укажите ID image-модели вручную; Umnik не будет выдавать общий /models за список генераторов."
                        null -> ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "У каждого подключения своя модель по умолчанию. Выбор здесь не переключает текущий чат на другой сервис.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (customImageConnection) {
            OutlinedTextField(
                value = manualImageModel,
                onValueChange = { manualImageModel = it.trim().take(180) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("ID модели изображений") },
                placeholder = { Text("provider/image-model") },
                singleLine = true
            )
            FilledTonalButton(
                onClick = {
                    selectedImageConnectionId?.let { vm.selectImageModel(it, manualImageModel) }
                    onDismiss()
                },
                enabled = manualImageModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("Использовать эту модель") }
        }

        if (!customImageConnection) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск модели") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
                else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
            }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
        }
        if (filtered.isEmpty()) {
            if (!customImageConnection) {
                Text(
                    if (state.isLoading) "Загрузка списка…" else "Модели не найдены",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { modelInfo ->
                    TextButton(
                        onClick = {
                            if (mode == ChatMode.TEXT) selectedTextConnectionId?.let { vm.selectDefaultTextModel(it, modelInfo.id) }
                            else selectedImageConnectionId?.let { vm.selectImageModel(it, modelInfo.id) }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

'''
ui = replace_block(ui, model_picker_start, model_picker_end, new_picker + model_picker_end, 'UI ModelPickerDialog')
dupe_picker = model_picker_end + '\n' + model_picker_end
ui = ui.replace(dupe_picker, model_picker_end, 1)

# Add labels near bottom helpers.
label_marker = '''private fun profileScopeLabel(scope: UserProfileScope): String = when (scope) {'''
idx = ui.find(label_marker)
if idx < 0:
    raise RuntimeError('UI profileScopeLabel marker missing')
labels = r'''private fun providerTypeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENROUTER -> "OpenRouter"
    ProviderType.NVIDIA -> "NVIDIA NIM"
    ProviderType.OPENAI_COMPATIBLE -> "OpenAI-совместимое"
}

private fun imageProtocolLabel(protocol: ImageApiProtocol): String = when (protocol) {
    ImageApiProtocol.AUTO -> "Авто"
    ImageApiProtocol.OPENAI_COMPATIBLE -> "OpenAI-compatible"
    ImageApiProtocol.NVIDIA_NIM -> "NVIDIA NIM"
}

'''
ui = ui[:idx] + labels + ui[idx:]
write(ui_path, ui)


# ---------------------------------------------------------------------------
# Public remote registry. Older installed versions can refresh this file.
# ---------------------------------------------------------------------------
registry = {
    "version": 1,
    "updated": "2026-09-12",
    "providers": {
        "openrouter": {
            "textBaseUrl": "https://openrouter.ai/api/v1",
            "imageBaseUrl": "https://openrouter.ai/api/v1",
            "imageProtocol": "OPENROUTER",
            "imageModels": []
        },
        "nvidia": {
            "textBaseUrl": "https://integrate.api.nvidia.com/v1",
            "imageBaseUrl": "https://ai.api.nvidia.com/v1/genai",
            "imageProtocol": "NVIDIA_NIM",
            "imageModels": [
                {"id": "black-forest-labs/flux.2-klein-4b", "inputModalities": ["text"], "parameterOptions": {}},
                {"id": "black-forest-labs/flux.1-schnell", "inputModalities": ["text"], "parameterOptions": {"aspect_ratio": ["1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3"]}},
                {"id": "black-forest-labs/flux.1-dev", "inputModalities": ["text"], "parameterOptions": {"aspect_ratio": ["1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3"]}},
                {"id": "stabilityai/stable-diffusion-3-medium", "inputModalities": ["text"], "parameterOptions": {"aspect_ratio": ["1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3"]}},
                {"id": "stabilityai/stable-diffusion-xl", "inputModalities": ["text"], "parameterOptions": {}}
            ]
        }
    }
}
write('docs/provider-registry.json', json.dumps(registry, ensure_ascii=False, indent=2) + '\n')


# ---------------------------------------------------------------------------
# Release metadata
# ---------------------------------------------------------------------------
gradle_path = 'app/build.gradle.kts'
gradle = read(gradle_path)
gradle = replace_once(gradle, '// Umnik v1.2.2', '// Umnik v1.3.0', 'version comment')
gradle = replace_once(gradle, 'versionCode = 41', 'versionCode = 42', 'versionCode')
gradle = replace_once(gradle, 'versionName = "1.2.2"', 'versionName = "1.3.0"', 'versionName')
write(gradle_path, gradle)

changelog_path = 'CHANGELOG.md'
changelog = read(changelog_path)
entry = '''## v1.3.0 - 2026-09-12

- Генерация изображений разделена на адаптеры провайдеров: OpenRouter использует свой Image API, а NVIDIA NIM — отдельный `ai.api.nvidia.com/v1/genai/<model>` вместо общего `/models` и `/images/generations`.
- Встроено подключение NVIDIA с отдельным списком настоящих генераторов изображений: FLUX.2 Klein 4B, FLUX.1 Schnell, FLUX.1 Dev, Stable Diffusion 3 Medium и Stable Diffusion XL.
- Исправлена смена провайдера изображений: модель от предыдущего сервиса больше не остаётся выбранной под другим подключением.
- В «Подключениях» появились дополнительные настройки Image API: включение генерации, отдельный адрес, протокол `Авто / OpenAI-compatible / NVIDIA NIM`, общий или отдельный API-ключ.
- Для OpenRouter и NVIDIA стандартные адреса подставляются автоматически; при необходимости пользователь может отключить автоматические адреса и задать свои вручную.
- Добавлен обновляемый реестр провайдеров `docs/provider-registry.json`: Umnik раз в сутки проверяет актуальные адреса и список NVIDIA image-моделей, хранит последнюю рабочую копию и имеет встроенный офлайн-резерв.
- Старое пользовательское подключение Nvidia автоматически распознаётся и переводится на встроенный NVIDIA-адаптер без потери сохранённого ключа и настроек модели.
- Для произвольного OpenAI-совместимого сервиса Umnik больше не выдаёт общий `/models` за список генераторов: ID image-модели задаётся явно.
- Добавлена кнопка «Проверить подключение» с диагностикой текстового API и Image API без платной пробной генерации.

'''
changelog = replace_once(changelog, '## Unreleased\n\n', '## Unreleased\n\n' + entry, 'changelog v1.3.0')
write(changelog_path, changelog)

print('Umnik v1.3.0 image provider patch applied successfully')
