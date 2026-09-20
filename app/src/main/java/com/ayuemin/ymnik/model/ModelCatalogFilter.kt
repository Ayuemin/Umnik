package com.ayuemin.ymnik.model

data class ModelCapabilityFilter(
    val multimodal: Boolean = false,
    val imageInput: Boolean = false,
    val imageOutput: Boolean = false,
    val audioInput: Boolean = false,
    val videoInput: Boolean = false,
    val reasoning: Boolean = false,
    val tools: Boolean = false,
    val streaming: Boolean = false,
    val requiredInputModalities: Set<String> = emptySet(),
    val requiredOutputModalities: Set<String> = emptySet(),
    val requiredParameters: Set<String> = emptySet(),
    val minContextTokens: Int? = null,
    val minMaxCompletionTokens: Int? = null
) {
    val active: Boolean
        get() = multimodal || imageInput || imageOutput || audioInput || videoInput ||
            reasoning || tools || streaming || requiredInputModalities.isNotEmpty() ||
            requiredOutputModalities.isNotEmpty() || requiredParameters.isNotEmpty() ||
            minContextTokens != null || minMaxCompletionTokens != null

    val activeCount: Int
        get() = listOf(multimodal, imageInput, imageOutput, audioInput, videoInput, reasoning, tools, streaming).count { it } +
            requiredInputModalities.size + requiredOutputModalities.size + requiredParameters.size +
            listOf(minContextTokens, minMaxCompletionTokens).count { it != null }
}

object ModelCatalogFilter {
    fun apply(
        models: List<ModelInfo>,
        query: String = "",
        category: ModelCategory? = null,
        variant: ModelVariant? = null,
        price: ModelPriceFilter = ModelPriceFilter.ALL,
        capabilities: ModelCapabilityFilter = ModelCapabilityFilter(),
        limit: Int = Int.MAX_VALUE
    ): List<ModelInfo> {
        val needle = query.trim()
        val filtered = models.asSequence()
            .filter { model ->
                needle.isBlank() || listOfNotNull(
                    model.id,
                    model.name,
                    model.description,
                    model.canonicalSlug,
                    model.huggingFaceId,
                    model.providerId
                ).any { it.contains(needle, ignoreCase = true) }
            }
            .filter { model -> category == null || category in model.categories }
            .filter { model ->
                when (variant) {
                    null -> true
                    ModelVariant.STANDARD -> model.variants == setOf(ModelVariant.STANDARD)
                    else -> variant in model.variants
                }
            }
            .filter { model ->
                when {
                    price == ModelPriceFilter.ALL -> true
                    price.freeOnly -> model.isFreeFor(category)
                    else -> {
                        val value = model.catalogPriceFor(category) ?: return@filter false
                        val ceiling = when (category) {
                            ModelCategory.IMAGE -> price.imageCeilingUsd1K
                            else -> price.textCeilingUsdPerMillion
                        }
                        ceiling?.let { value <= it } ?: true
                    }
                }
            }
            // "Мультимодальный чат" is intentionally strict: the same model must
            // understand text+images and be able to return both text and images.
            .filter { model -> !capabilities.multimodal || model.isMultimodalChat }
            .filter { model -> !capabilities.imageInput || model.accepts("image") }
            .filter { model -> !capabilities.imageOutput || model.outputs("image") }
            .filter { model -> !capabilities.audioInput || model.accepts("audio") }
            .filter { model -> !capabilities.videoInput || model.accepts("video") }
            .filter { model -> !capabilities.reasoning || model.supportsReasoning }
            .filter { model -> !capabilities.tools || model.supportsTools }
            .filter { model -> !capabilities.streaming || model.supportsStreaming == true }
            .filter { model ->
                capabilities.requiredInputModalities.all { required -> model.accepts(required) }
            }
            .filter { model ->
                capabilities.requiredOutputModalities.all { required -> model.outputs(required) }
            }
            .filter { model ->
                capabilities.requiredParameters.all { required -> required.lowercase() in model.supportedParameters }
            }
            .filter { model ->
                capabilities.minContextTokens?.let { minimum ->
                    maxOf(model.contextLength ?: 0, model.topProviderContextLength ?: 0) >= minimum
                } ?: true
            }
            .filter { model ->
                capabilities.minMaxCompletionTokens?.let { minimum ->
                    (model.maxCompletionTokens ?: 0) >= minimum
                } ?: true
            }
            .toList()

        val sorted = if (price == ModelPriceFilter.ALL) filtered else filtered.sortedWith(
            compareBy<ModelInfo>({ !it.isFreeFor(category) }, { it.catalogPriceFor(category) ?: Double.MAX_VALUE }, { it.id })
        )
        return sorted.take(limit.coerceAtLeast(0))
    }
}
