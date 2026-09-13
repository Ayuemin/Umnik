package com.ayuemin.ymnik.model

data class ModelCapabilityFilter(
    val imageInput: Boolean = false,
    val audioInput: Boolean = false,
    val videoInput: Boolean = false,
    val reasoning: Boolean = false,
    val tools: Boolean = false
) {
    val active: Boolean
        get() = imageInput || audioInput || videoInput || reasoning || tools
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
        return models.asSequence()
            .filter { model -> needle.isBlank() || model.id.contains(needle, ignoreCase = true) }
            .filter { model -> category == null || category in model.categories }
            .filter { model ->
                when (variant) {
                    null -> true
                    ModelVariant.STANDARD -> model.variants == setOf(ModelVariant.STANDARD)
                    else -> variant in model.variants
                }
            }
            .filter { model ->
                if (price == ModelPriceFilter.ALL) return@filter true
                val value = model.maxTextPriceUsdPerMillion ?: return@filter false
                if (price.freeOnly) value <= 0.0
                else price.ceilingUsdPerMillion?.let { value <= it } ?: true
            }
            .filter { model -> !capabilities.imageInput || model.accepts("image") }
            .filter { model -> !capabilities.audioInput || model.accepts("audio") }
            .filter { model -> !capabilities.videoInput || model.accepts("video") }
            .filter { model -> !capabilities.reasoning || model.supportsReasoning }
            .filter { model -> !capabilities.tools || model.supportsTools }
            .take(limit.coerceAtLeast(0))
            .toList()
    }
}
