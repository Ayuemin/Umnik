package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelVariant
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads OpenRouter's complete model catalog.
 *
 * The regular /models endpoint defaults to text output. `output_modalities=all`
 * is therefore mandatory for Umnik's "All" view. Dedicated image, video and
 * embeddings model endpoints are also merged in because they expose richer
 * capability descriptors than the general catalog for those model families.
 */
class OpenRouterCatalogClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter catalog"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter catalog") }
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun allModels(
        apiKey: String,
        baseUrl: String = "https://openrouter.ai/api/v1"
    ): List<ModelInfo> = withContext(Dispatchers.IO) {
        val all = fetch(apiKey, modelsUrl(baseUrl, "all"))
        val enriched = coroutineScope {
            DEDICATED_MODEL_PATHS.map { path ->
                async {
                    runCatching { fetch(apiKey, endpoint(baseUrl, path)) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        merge(all + enriched)
    }

    suspend fun modelsForOutput(
        apiKey: String,
        outputModality: String,
        baseUrl: String = "https://openrouter.ai/api/v1"
    ): List<ModelInfo> = withContext(Dispatchers.IO) {
        fetch(apiKey, modelsUrl(baseUrl, outputModality.lowercase()))
    }

    private fun fetch(apiKey: String, url: String): List<ModelInfo> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenRouter catalog HTTP ${response.code}: ${body.take(500)}")
            val root = gson.fromJson(body, JsonObject::class.java)
            return root.getAsJsonArray("data")
                ?.mapNotNull(OpenRouterModelCatalog::parse)
                .orEmpty()
        }
    }

    private fun merge(items: List<ModelInfo>): List<ModelInfo> = items
        .groupBy { it.id }
        .values
        .map { duplicates -> duplicates.reduce(OpenRouterModelCatalog::merge) }
        .sortedBy { it.id }

    private fun modelsUrl(baseUrl: String, outputModality: String): String =
        "${baseUrl.trimEnd('/')}/models?output_modalities=$outputModality"

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    companion object {
        private val DEDICATED_MODEL_PATHS = listOf(
            "images/models",
            "videos/models",
            "embeddings/models"
        )
    }
}

internal object OpenRouterModelCatalog {
    private val KNOWN_VARIANTS = mapOf(
        "batch" to ModelVariant.BATCH,
        "free" to ModelVariant.FREE,
        "thinking" to ModelVariant.THINKING,
        "extended" to ModelVariant.EXTENDED,
        "online" to ModelVariant.ONLINE,
        "nitro" to ModelVariant.NITRO,
        "floor" to ModelVariant.FLOOR
    )

    fun parse(element: JsonElement): ModelInfo? {
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = item.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val architecture = item.getAsJsonObject("architecture")
        val inputModalities = stringSet(architecture?.get("input_modalities")).ifEmpty { setOf("text") }
        val outputModalities = stringSet(architecture?.get("output_modalities"))
            .ifEmpty { inferOutputModalities(item, id) }
        val supportedParameters = parameterNames(item.get("supported_parameters"))
        val reasoningInfo = item.getAsJsonObject("reasoning")
        val reasoningEfforts = stringSet(reasoningInfo?.get("supported_efforts"))
        val contextLength = intOrNull(item.get("context_length"))?.takeIf { it > 0 }
        val maxCompletionTokens = intOrNull(item.getAsJsonObject("top_provider")?.get("max_completion_tokens"))
            ?.takeIf { it > 0 }
        val parameterOptions = parameterOptions(item.get("supported_parameters"))
        val pricing = item.getAsJsonObject("pricing")
        val promptPriceUsdPerMillion = pricePerMillion(pricing?.get("prompt"))
        val completionPriceUsdPerMillion = pricePerMillion(pricing?.get("completion"))
        val imagePriceUsd = priceUsd(pricing?.get("image"))
        val imageTokenPriceUsd = priceUsd(pricing?.get("image_token"))
        val imageOutputPriceUsd = priceUsd(pricing?.get("image_output"))
        val variants = variants(id)

        return ModelInfo(
            id = id,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            supportedParameters = supportedParameters,
            reasoningEfforts = reasoningEfforts,
            parameterOptions = parameterOptions,
            contextLength = contextLength,
            maxCompletionTokens = maxCompletionTokens,
            reasoningMandatory = boolOrFalse(reasoningInfo?.get("mandatory")),
            reasoningDefaultEnabled = boolOrFalse(reasoningInfo?.get("default_enabled")),
            promptPriceUsdPerMillion = promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = completionPriceUsdPerMillion,
            imagePriceUsd = imagePriceUsd,
            imageTokenPriceUsd = imageTokenPriceUsd,
            imageOutputPriceUsd = imageOutputPriceUsd,
            variants = variants
        )
    }

    fun merge(first: ModelInfo, second: ModelInfo): ModelInfo {
        require(first.id == second.id)
        return first.copy(
            inputModalities = first.inputModalities + second.inputModalities,
            outputModalities = first.outputModalities + second.outputModalities,
            supportedParameters = first.supportedParameters + second.supportedParameters,
            reasoningEfforts = first.reasoningEfforts + second.reasoningEfforts,
            parameterOptions = (first.parameterOptions.keys + second.parameterOptions.keys).associateWith { key ->
                (first.parameterOptions[key].orEmpty() + second.parameterOptions[key].orEmpty()).distinct()
            }.filterValues { it.isNotEmpty() },
            contextLength = maxOfNullable(first.contextLength, second.contextLength),
            maxCompletionTokens = maxOfNullable(first.maxCompletionTokens, second.maxCompletionTokens),
            reasoningMandatory = first.reasoningMandatory || second.reasoningMandatory,
            reasoningDefaultEnabled = first.reasoningDefaultEnabled || second.reasoningDefaultEnabled,
            promptPriceUsdPerMillion = first.promptPriceUsdPerMillion ?: second.promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = first.completionPriceUsdPerMillion ?: second.completionPriceUsdPerMillion,
            imagePriceUsd = first.imagePriceUsd ?: second.imagePriceUsd,
            imageTokenPriceUsd = first.imageTokenPriceUsd ?: second.imageTokenPriceUsd,
            imageOutputPriceUsd = first.imageOutputPriceUsd ?: second.imageOutputPriceUsd,
            variants = first.variants + second.variants
        )
    }

    fun variants(id: String): Set<ModelVariant> {
        val found = id.split(':')
            .drop(1)
            .mapNotNull { KNOWN_VARIANTS[it.lowercase()] }
            .toSet()
        return found.ifEmpty { setOf(ModelVariant.STANDARD) }
    }

    private fun inferOutputModalities(item: JsonObject, id: String): Set<String> {
        val explicit = stringSet(item.get("output_modalities"))
        if (explicit.isNotEmpty()) return explicit
        val lower = id.lowercase()
        return when {
            "embedding" in lower || "/embed" in lower -> setOf("embeddings")
            "rerank" in lower -> setOf("rerank")
            "whisper" in lower || "transcrib" in lower -> setOf("transcription")
            else -> setOf("text")
        }
    }

    private fun stringSet(element: JsonElement?): Set<String> = element
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { value ->
            value.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
        }
        ?.toSet()
        .orEmpty()

    private fun parameterNames(element: JsonElement?): Set<String> = when {
        element == null -> emptySet()
        element.isJsonArray -> element.asJsonArray
            .mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
            .toSet()
        element.isJsonObject -> element.asJsonObject.keySet().map { it.lowercase() }.toSet()
        else -> emptySet()
    }

    private fun parameterOptions(element: JsonElement?): Map<String, List<String>> = element
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.entrySet()
        ?.mapNotNull { (name, descriptor) ->
            val values = descriptor.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("values")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    value.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                }
                .orEmpty()
                .distinct()
            if (values.isEmpty()) null else name.lowercase() to values
        }
        ?.toMap()
        .orEmpty()

    private fun priceUsd(element: JsonElement?): Double? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()?.takeIf { it >= 0.0 }

    private fun pricePerMillion(element: JsonElement?): Double? =
        priceUsd(element)?.times(1_000_000.0)

    private fun intOrNull(element: JsonElement?): Int? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun boolOrFalse(element: JsonElement?): Boolean = runCatching {
        element?.takeUnless { it.isJsonNull }?.asBoolean == true
    }.getOrDefault(false)

    private fun maxOfNullable(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }
}
