package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
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
        val textModels: List<String> = emptyList(),
        val imageBaseUrl: String = "",
        val imageProtocol: String = "AUTO",
        val imageModels: List<ImageModelDefinition> = emptyList()
    )

    data class RegistryDocument(
        val version: Int = 4,
        val providers: Map<String, ProviderDefinition> = emptyMap()
    )

    private val prefs = context.getSharedPreferences("provider_registry", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "Provider registry"))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var current: RegistryDocument = loadCached() ?: fallback()

    fun textBaseUrl(type: ProviderType): String? = provider(type)?.textBaseUrl?.trim()?.takeIf { it.isNotBlank() }

    /**
     * A deliberately conservative list for provider catalogues that mix chat,
     * embeddings, rerankers and models that are no longer exposed by the hosted API.
     * The remote registry can be updated without publishing a new APK.
     */
    fun textModels(type: ProviderType): List<ModelInfo> = provider(type)?.textModels.orEmpty()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .map(::ModelInfo)

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
        doc.version >= MIN_REGISTRY_VERSION &&
            openRouter?.textBaseUrl?.startsWith("https://") == true &&
            openRouter.imageBaseUrl.startsWith("https://") &&
            nvidia?.textBaseUrl?.startsWith("https://") == true &&
            nvidia.textModels.any { it.isNotBlank() } &&
            nvidia.imageBaseUrl.startsWith("https://") &&
            nvidia.imageModels.any { it.id.isNotBlank() }
    }

    private fun fallback(): RegistryDocument = RegistryDocument(
        version = 4,
        providers = mapOf(
            "openrouter" to ProviderDefinition(
                textBaseUrl = DEFAULT_OPENROUTER_BASE_URL,
                imageBaseUrl = DEFAULT_OPENROUTER_BASE_URL,
                imageProtocol = "OPENROUTER"
            ),
            "nvidia" to ProviderDefinition(
                textBaseUrl = DEFAULT_NVIDIA_TEXT_BASE_URL,
                // Ordered on purpose: the first surviving model becomes a safe default
                // when an old saved NVIDIA model disappears from the hosted service.
                textModels = VERIFIED_NVIDIA_TEXT_MODELS,
                imageBaseUrl = DEFAULT_NVIDIA_IMAGE_BASE_URL,
                imageProtocol = "NVIDIA_NIM",
                imageModels = listOf(
                    ImageModelDefinition("black-forest-labs/flux.2-klein-4b"),
                    ImageModelDefinition(
                        "black-forest-labs/flux.1-dev",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    )
                )
            )
        )
    )

    companion object {
        const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_NVIDIA_TEXT_BASE_URL = "https://integrate.api.nvidia.com/v1"
        const val DEFAULT_NVIDIA_IMAGE_BASE_URL = "https://ai.api.nvidia.com/v1/genai"
        const val REMOTE_URL = "https://raw.githubusercontent.com/Ayuemin/Umnik/main/docs/provider-registry.json"

        private const val MIN_REGISTRY_VERSION = 4
        private const val KEY_JSON = "registry_json"
        private const val KEY_LAST_CHECK = "registry_last_check"
        private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private val COMMON_RATIOS = listOf("1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3")

        // NVIDIA's hosted /v1/models currently contains entries from several NIM
        // families and may also retain entries that return 404 for a particular
        // account. Keep only chat-capable candidates we actively want to expose.
        // The list is also mirrored in docs/provider-registry.json so it can be
        // corrected remotely as NVIDIA changes the hosted catalogue.
        private val VERIFIED_NVIDIA_TEXT_MODELS = listOf(
            "z-ai/glm-5.3-flash",
            "z-ai/glm-5.2",
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "deepseek-ai/deepseek-v4-pro-0813",
            "deepseek-ai/deepseek-v4-pro",
            "deepseek-ai/deepseek-v4-flash-0731",
            "mistralai/mistral-large-3-675b-instruct-2512",
            "mistralai/mistral-small-4-119b-2603",
            "meta/llama-3.1-8b-instruct",
            "meta/llama-3.1-70b-instruct",
            "meta/llama-3.3-70b-instruct",
            "nvidia/llama-3.3-nemotron-super-49b-v1.5",
            "qwen/qwen3-next-80b-a3b-instruct",
            "qwen/qwen3-next-80b-a3b-thinking",
            "qwen/qwen3-32b",
            "qwen/qwen2.5-coder-32b-instruct",
            "moonshotai/kimi-k3",
            "moonshotai/kimi-k2.6",
            "sarvamai/sarvam-m",
            "stockmark/stockmark-2-100b-instruct"
        )
    }
}
