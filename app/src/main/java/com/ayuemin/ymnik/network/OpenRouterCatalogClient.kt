package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelParameterCapability
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
        val id = stringOrNull(item.get("id")) ?: return null
        val architecture = item.getAsJsonObject("architecture")
        val topProvider = item.getAsJsonObject("top_provider")
        val inputModalities = stringSet(architecture?.get("input_modalities")).ifEmpty { setOf("text") }
        val outputModalities = stringSet(architecture?.get("output_modalities"))
            .ifEmpty { inferOutputModalities(item, id) }
        val supportedParameters = parameterNames(item.get("supported_parameters"))
        val parameterCapabilities = parameterCapabilities(item.get("supported_parameters"))
        val reasoningInfo = item.getAsJsonObject("reasoning")
        val reasoningEfforts = stringSet(reasoningInfo?.get("supported_efforts"))
        val contextLength = intOrNull(item.get("context_length"))?.takeIf { it > 0 }
        val topProviderContextLength = intOrNull(topProvider?.get("context_length"))?.takeIf { it > 0 }
        val maxCompletionTokens = intOrNull(topProvider?.get("max_completion_tokens"))?.takeIf { it > 0 }
        val parameterOptions = parameterCapabilities
            .mapValues { it.value.values }
            .filterValues { it.isNotEmpty() }
        val pricing = item.getAsJsonObject("pricing")
        val pricingUsd = numberMap(pricing)
        val promptPriceUsdPerMillion = pricePerMillion(pricing?.get("prompt"))
        val completionPriceUsdPerMillion = pricePerMillion(pricing?.get("completion"))
        val imagePriceUsd = priceUsd(pricing?.get("image"))
        val imageTokenPriceUsd = priceUsd(pricing?.get("image_token"))
        val imageOutputPriceUsd = priceUsd(pricing?.get("image_output"))
        val variants = variants(id)
        val capabilityValues = buildMap {
            putAll(listCapabilityMap(item, "supported_resolutions", "resolutions"))
            putAll(listCapabilityMap(item, "supported_aspect_ratios", "aspect_ratios"))
            putAll(listCapabilityMap(item, "supported_sizes", "sizes"))
            putAll(listCapabilityMap(item, "supported_durations", "durations"))
            putAll(listCapabilityMap(item, "supported_frame_images", "frame_images"))
        }
        val capabilityFlags = buildMap {
            boolOrNull(item.get("generate_audio"))?.let { put("generate_audio", it) }
            boolOrNull(item.get("seed"))?.let { put("seed", it) }
        }

        return ModelInfo(
            id = id,
            name = stringOrNull(item.get("name")),
            description = stringOrNull(item.get("description")),
            canonicalSlug = stringOrNull(item.get("canonical_slug")),
            huggingFaceId = stringOrNull(item.get("hugging_face_id")),
            createdAtEpochSeconds = longOrNull(item.get("created")),
            architectureModality = stringOrNull(architecture?.get("modality")),
            tokenizer = stringOrNull(architecture?.get("tokenizer")),
            instructType = stringOrNull(architecture?.get("instruct_type")),
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            supportedParameters = supportedParameters,
            reasoningEfforts = reasoningEfforts,
            parameterOptions = parameterOptions,
            parameterCapabilities = parameterCapabilities,
            contextLength = contextLength,
            topProviderContextLength = topProviderContextLength,
            maxCompletionTokens = maxCompletionTokens,
            reasoningMandatory = boolOrFalse(reasoningInfo?.get("mandatory")),
            reasoningDefaultEnabled = boolOrFalse(reasoningInfo?.get("default_enabled")),
            supportsStreaming = boolOrNull(item.get("supports_streaming"))
                ?: boolOrNull(topProvider?.get("supports_streaming")),
            topProviderModerated = boolOrNull(topProvider?.get("is_moderated")),
            promptPriceUsdPerMillion = promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = completionPriceUsdPerMillion,
            imagePriceUsd = imagePriceUsd,
            imageTokenPriceUsd = imageTokenPriceUsd,
            imageOutputPriceUsd = imageOutputPriceUsd,
            pricingUsd = pricingUsd,
            capabilityValues = capabilityValues,
            capabilityFlags = capabilityFlags,
            pricingSkusUsd = numberMap(item.getAsJsonObject("pricing_skus")),
            allowedPassthroughParameters = stringSet(item.get("allowed_passthrough_parameters")),
            rawOpenRouterMetadata = listOf(gson.toJson(item)),
            variants = variants
        )
    }

    fun merge(first: ModelInfo, second: ModelInfo): ModelInfo {
        require(first.id == second.id)
        return first.copy(
            name = first.name ?: second.name,
            description = richerText(first.description, second.description),
            canonicalSlug = first.canonicalSlug ?: second.canonicalSlug,
            huggingFaceId = first.huggingFaceId ?: second.huggingFaceId,
            createdAtEpochSeconds = maxOfNullable(first.createdAtEpochSeconds, second.createdAtEpochSeconds),
            architectureModality = first.architectureModality ?: second.architectureModality,
            tokenizer = first.tokenizer ?: second.tokenizer,
            instructType = first.instructType ?: second.instructType,
            inputModalities = first.inputModalities + second.inputModalities,
            outputModalities = first.outputModalities + second.outputModalities,
            supportedParameters = first.supportedParameters + second.supportedParameters,
            reasoningEfforts = first.reasoningEfforts + second.reasoningEfforts,
            parameterOptions = mergeStringLists(first.parameterOptions, second.parameterOptions),
            parameterCapabilities = mergeParameterCapabilities(first.parameterCapabilities, second.parameterCapabilities),
            contextLength = maxOfNullable(first.contextLength, second.contextLength),
            topProviderContextLength = maxOfNullable(first.topProviderContextLength, second.topProviderContextLength),
            maxCompletionTokens = maxOfNullable(first.maxCompletionTokens, second.maxCompletionTokens),
            reasoningMandatory = first.reasoningMandatory || second.reasoningMandatory,
            reasoningDefaultEnabled = first.reasoningDefaultEnabled || second.reasoningDefaultEnabled,
            supportsStreaming = when {
                first.supportsStreaming == true || second.supportsStreaming == true -> true
                first.supportsStreaming == false && second.supportsStreaming == false -> false
                else -> first.supportsStreaming ?: second.supportsStreaming
            },
            topProviderModerated = first.topProviderModerated ?: second.topProviderModerated,
            promptPriceUsdPerMillion = first.promptPriceUsdPerMillion ?: second.promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = first.completionPriceUsdPerMillion ?: second.completionPriceUsdPerMillion,
            imagePriceUsd = first.imagePriceUsd ?: second.imagePriceUsd,
            imageTokenPriceUsd = first.imageTokenPriceUsd ?: second.imageTokenPriceUsd,
            imageOutputPriceUsd = first.imageOutputPriceUsd ?: second.imageOutputPriceUsd,
            pricingUsd = first.pricingUsd + second.pricingUsd,
            capabilityValues = mergeStringLists(first.capabilityValues, second.capabilityValues),
            capabilityFlags = first.capabilityFlags + second.capabilityFlags,
            pricingSkusUsd = first.pricingSkusUsd + second.pricingSkusUsd,
            allowedPassthroughParameters = first.allowedPassthroughParameters + second.allowedPassthroughParameters,
            rawOpenRouterMetadata = (first.rawOpenRouterMetadata + second.rawOpenRouterMetadata).distinct(),
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

    private fun parameterCapabilities(element: JsonElement?): Map<String, ModelParameterCapability> = when {
        element == null -> emptyMap()
        element.isJsonArray -> element.asJsonArray
            .mapNotNull { value -> stringOrNull(value)?.lowercase() }
            .associateWith { ModelParameterCapability() }
        element.isJsonObject -> element.asJsonObject.entrySet().associate { (name, descriptor) ->
            val obj = descriptor.takeIf { it.isJsonObject }?.asJsonObject
            val values = obj?.get("values")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull(::stringOrNull)
                ?.distinct()
                .orEmpty()
            name.lowercase() to ModelParameterCapability(
                type = stringOrNull(obj?.get("type")),
                values = values,
                min = doubleOrNull(obj?.get("min")),
                max = doubleOrNull(obj?.get("max"))
            )
        }
        else -> emptyMap()
    }

    private fun numberMap(obj: JsonObject?): Map<String, Double> = obj
        ?.entrySet()
        ?.mapNotNull { (key, value) -> doubleOrNull(value)?.let { key.lowercase() to it } }
        ?.toMap()
        .orEmpty()

    private fun listCapabilityMap(item: JsonObject, sourceKey: String, targetKey: String): Map<String, List<String>> {
        val values = item.get(sourceKey)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { value ->
                when {
                    value.isJsonPrimitive -> value.asString
                    else -> null
                }
            }
            ?.distinct()
            .orEmpty()
        return if (values.isEmpty()) emptyMap() else mapOf(targetKey to values)
    }

    private fun mergeStringLists(
        first: Map<String, List<String>>,
        second: Map<String, List<String>>
    ): Map<String, List<String>> = (first.keys + second.keys).associateWith { key ->
        (first[key].orEmpty() + second[key].orEmpty()).distinct()
    }.filterValues { it.isNotEmpty() }

    private fun mergeParameterCapabilities(
        first: Map<String, ModelParameterCapability>,
        second: Map<String, ModelParameterCapability>
    ): Map<String, ModelParameterCapability> = (first.keys + second.keys).associateWith { key ->
        val a = first[key]
        val b = second[key]
        ModelParameterCapability(
            type = a?.type ?: b?.type,
            values = (a?.values.orEmpty() + b?.values.orEmpty()).distinct(),
            min = listOfNotNull(a?.min, b?.min).minOrNull(),
            max = listOfNotNull(a?.max, b?.max).maxOrNull()
        )
    }

    private fun richerText(first: String?, second: String?): String? = when {
        first.isNullOrBlank() -> second
        second.isNullOrBlank() -> first
        second.length > first.length -> second
        else -> first
    }


    private fun priceUsd(element: JsonElement?): Double? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()?.takeIf { it >= 0.0 }

    private fun pricePerMillion(element: JsonElement?): Double? =
        priceUsd(element)?.times(1_000_000.0)

    private fun stringOrNull(element: JsonElement?): String? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun doubleOrNull(element: JsonElement?): Double? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()

    private fun longOrNull(element: JsonElement?): Long? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()

    private fun boolOrNull(element: JsonElement?): Boolean? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asBoolean
    }.getOrNull()

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

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }
}
