from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:140]!r}")
    p.write_text(text.replace(old, new, count))

# Model pricing: separate text-token pricing from image-output pricing.
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
replace(path,
'''enum class ModelPriceFilter(val ceilingUsdPerMillion: Double?, val freeOnly: Boolean = false) {
    ALL(null),
    FREE(0.0, true),
    UP_TO_0_5(0.5),
    UP_TO_1(1.0),
    UP_TO_5(5.0),
    UP_TO_10(10.0)
}
''',
'''enum class ModelPriceFilter(
    val textCeilingUsdPerMillion: Double?,
    val imageCeilingUsd1K: Double?,
    val freeOnly: Boolean = false
) {
    ALL(null, null),
    FREE(0.0, 0.0, true),
    UP_TO_0_5(0.5, 0.02),
    UP_TO_1(1.0, 0.05),
    UP_TO_5(5.0, 0.10),
    UP_TO_10(10.0, 0.20)
}
''')
replace(path,
'''    val promptPriceUsdPerMillion: Double? = null,
    val completionPriceUsdPerMillion: Double? = null,
    val variants: Set<ModelVariant> = setOf(ModelVariant.STANDARD)
''',
'''    val promptPriceUsdPerMillion: Double? = null,
    val completionPriceUsdPerMillion: Double? = null,
    val imagePriceUsd: Double? = null,
    val imageTokenPriceUsd: Double? = null,
    val imageOutputPriceUsd: Double? = null,
    val variants: Set<ModelVariant> = setOf(ModelVariant.STANDARD)
''')
replace(path,
'''    val maxTextPriceUsdPerMillion: Double?
        get() = listOfNotNull(promptPriceUsdPerMillion, completionPriceUsdPerMillion).maxOrNull()

    val categories: Set<ModelCategory>
''',
'''    val maxTextPriceUsdPerMillion: Double?
        get() = listOfNotNull(promptPriceUsdPerMillion, completionPriceUsdPerMillion).maxOrNull()

    /**
     * Approximate price of a 1K generated image from OpenRouter's image-output token rate.
     * OpenRouter image models can bill by image, megapixel or image tokens; 4096 image tokens
     * is the useful 1K baseline exposed by the general catalog. The actual request usage cost
     * remains authoritative and can vary with resolution, quality and provider.
     */
    val estimatedImageOutputUsd1K: Double?
        get() = (imageOutputPriceUsd ?: imageTokenPriceUsd)
            ?.takeIf { it >= 0.0 }
            ?.times(4096.0)

    fun isFreeFor(category: ModelCategory?): Boolean {
        if (ModelVariant.FREE in variants) return true
        fun allKnownZero(values: List<Double?>): Boolean {
            val known = values.filterNotNull()
            return known.isNotEmpty() && known.all { it <= 0.0 }
        }
        return when (category) {
            ModelCategory.TEXT -> allKnownZero(listOf(promptPriceUsdPerMillion, completionPriceUsdPerMillion))
            ModelCategory.IMAGE -> allKnownZero(
                listOf(
                    promptPriceUsdPerMillion,
                    completionPriceUsdPerMillion,
                    imagePriceUsd,
                    imageTokenPriceUsd,
                    imageOutputPriceUsd
                )
            )
            null -> categories.isNotEmpty() && categories.all { output -> isFreeFor(output) }
            else -> false
        }
    }

    fun catalogPriceFor(category: ModelCategory?): Double? = when (category) {
        ModelCategory.IMAGE -> estimatedImageOutputUsd1K
        ModelCategory.TEXT -> maxTextPriceUsdPerMillion
        null -> when {
            categories == setOf(ModelCategory.IMAGE) -> estimatedImageOutputUsd1K
            categories == setOf(ModelCategory.TEXT) -> maxTextPriceUsdPerMillion
            else -> null
        }
        else -> null
    }

    val categories: Set<ModelCategory>
''')

# Parse all relevant image pricing signals from the live general catalog.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterCatalogClient.kt"
replace(path,
'''        val promptPriceUsdPerMillion = pricePerMillion(pricing?.get("prompt"))
        val completionPriceUsdPerMillion = pricePerMillion(pricing?.get("completion"))
        val variants = variants(id)
''',
'''        val promptPriceUsdPerMillion = pricePerMillion(pricing?.get("prompt"))
        val completionPriceUsdPerMillion = pricePerMillion(pricing?.get("completion"))
        val imagePriceUsd = priceUsd(pricing?.get("image"))
        val imageTokenPriceUsd = priceUsd(pricing?.get("image_token"))
        val imageOutputPriceUsd = priceUsd(pricing?.get("image_output"))
        val variants = variants(id)
''')
replace(path,
'''            promptPriceUsdPerMillion = promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = completionPriceUsdPerMillion,
            variants = variants
''',
'''            promptPriceUsdPerMillion = promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = completionPriceUsdPerMillion,
            imagePriceUsd = imagePriceUsd,
            imageTokenPriceUsd = imageTokenPriceUsd,
            imageOutputPriceUsd = imageOutputPriceUsd,
            variants = variants
''')
replace(path,
'''            promptPriceUsdPerMillion = first.promptPriceUsdPerMillion ?: second.promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = first.completionPriceUsdPerMillion ?: second.completionPriceUsdPerMillion,
            variants = first.variants + second.variants
''',
'''            promptPriceUsdPerMillion = first.promptPriceUsdPerMillion ?: second.promptPriceUsdPerMillion,
            completionPriceUsdPerMillion = first.completionPriceUsdPerMillion ?: second.completionPriceUsdPerMillion,
            imagePriceUsd = first.imagePriceUsd ?: second.imagePriceUsd,
            imageTokenPriceUsd = first.imageTokenPriceUsd ?: second.imageTokenPriceUsd,
            imageOutputPriceUsd = first.imageOutputPriceUsd ?: second.imageOutputPriceUsd,
            variants = first.variants + second.variants
''')
replace(path,
'''    private fun pricePerMillion(element: JsonElement?): Double? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()?.takeIf { it >= 0.0 }?.times(1_000_000.0)
''',
'''    private fun priceUsd(element: JsonElement?): Double? = runCatching {
        element?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()?.takeIf { it >= 0.0 }

    private fun pricePerMillion(element: JsonElement?): Double? =
        priceUsd(element)?.times(1_000_000.0)
''')

# Price filtering is modality-aware and sorted cheapest-first whenever active.
path = "app/src/main/java/com/ayuemin/ymnik/model/ModelCatalogFilter.kt"
p = Path(path)
p.write_text('''package com.ayuemin.ymnik.model

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
        val filtered = models.asSequence()
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
            .filter { model -> !capabilities.imageInput || model.accepts("image") }
            .filter { model -> !capabilities.audioInput || model.accepts("audio") }
            .filter { model -> !capabilities.videoInput || model.accepts("video") }
            .filter { model -> !capabilities.reasoning || model.supportsReasoning }
            .filter { model -> !capabilities.tools || model.supportsTools }
            .toList()

        val sorted = if (price == ModelPriceFilter.ALL) filtered else filtered.sortedWith(
            compareBy<ModelInfo>({ !it.isFreeFor(category) }, { it.catalogPriceFor(category) ?: Double.MAX_VALUE }, { it.id })
        )
        return sorted.take(limit.coerceAtLeast(0))
    }
}
''')

# Hub UI: modality-aware price labels, honest image estimate, and visible model context menu.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
replace(path, 'import androidx.compose.material3.CardDefaults\n', 'import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.DropdownMenuItem\n')
replace(path, 'Text("Umnik 1.6.1 · полный каталог и возможности"', 'Text("Umnik 1.6.2 · полный каталог и возможности"')
replace(path,
'''        Text("Цена (макс. вход/выход за 1M токенов)", modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ModelPriceFilter.entries) { item ->
                FilterChip(selected = price == item, onClick = { price = item }, label = { Text(priceFilterLabel(item)) })
            }
        }
''',
'''        val priceOptions = remember(category) {
            when (category) {
                ModelCategory.TEXT, ModelCategory.IMAGE -> ModelPriceFilter.entries.toList()
                else -> listOf(ModelPriceFilter.ALL, ModelPriceFilter.FREE)
            }
        }
        Text(priceSectionLabel(category), modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(priceOptions) { item ->
                FilterChip(selected = price == item, onClick = { price = item }, label = { Text(priceFilterLabel(item, category)) })
            }
        }
''')
replace(path,
'''@Composable
private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController) {
    ElevatedCard(
''',
'''@Composable
private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController) {
    val context = LocalContext.current
    var menuOpen by remember(model.id) { mutableStateOf(false) }
    ElevatedCard(
''')
replace(path,
'''        Column(Modifier.padding(12.dp)) {
            Text(model.id, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
''',
'''        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    model.id,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (ModelCategory.TEXT in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для чата") },
                                onClick = { menuOpen = false; controller.useAsTextModel(model) }
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить / убрать из быстрых") },
                                onClick = { menuOpen = false; controller.toggleQuickTextModel(model) }
                            )
                        }
                        if (ModelCategory.IMAGE in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для изображений") },
                                onClick = { menuOpen = false; controller.useAsImageModel(model) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Копировать ID модели") },
                            onClick = { menuOpen = false; copyToClipboard(context, model.id) }
                        )
                    }
                }
            }
            Text(
''')
replace(path,
'''            if (model.promptPriceUsdPerMillion != null || model.completionPriceUsdPerMillion != null) {
                Text(
                    "Цена / 1M: вход ${formatCatalogPrice(model.promptPriceUsdPerMillion)} · выход ${formatCatalogPrice(model.completionPriceUsdPerMillion)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
''',
'''            catalogPriceText(model)?.let { priceText ->
                Text(
                    priceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
''')
replace(path,
'''private fun priceFilterLabel(value: ModelPriceFilter): String = when (value) {
    ModelPriceFilter.ALL -> "Все"
    ModelPriceFilter.FREE -> "Бесплатно"
    ModelPriceFilter.UP_TO_0_5 -> "≤ \\$0.5"
    ModelPriceFilter.UP_TO_1 -> "≤ \\$1"
    ModelPriceFilter.UP_TO_5 -> "≤ \\$5"
    ModelPriceFilter.UP_TO_10 -> "≤ \\$10"
}

private fun formatCatalogPrice(value: Double?): String = when {
''',
'''private fun priceSectionLabel(category: ModelCategory?): String = when (category) {
    ModelCategory.IMAGE -> "Цена изображения (≈ для 1K; точная зависит от параметров)"
    ModelCategory.TEXT -> "Цена текста (макс. вход/выход за 1M токенов)"
    else -> "Цена"
}

private fun priceFilterLabel(value: ModelPriceFilter, category: ModelCategory?): String = when (value) {
    ModelPriceFilter.ALL -> "Все"
    ModelPriceFilter.FREE -> "Бесплатно"
    ModelPriceFilter.UP_TO_0_5 -> if (category == ModelCategory.IMAGE) "≤ \\$0.02" else "≤ \\$0.5/M"
    ModelPriceFilter.UP_TO_1 -> if (category == ModelCategory.IMAGE) "≤ \\$0.05" else "≤ \\$1/M"
    ModelPriceFilter.UP_TO_5 -> if (category == ModelCategory.IMAGE) "≤ \\$0.10" else "≤ \\$5/M"
    ModelPriceFilter.UP_TO_10 -> if (category == ModelCategory.IMAGE) "≤ \\$0.20" else "≤ \\$10/M"
}

private fun catalogPriceText(model: ModelInfo): String? {
    if (ModelVariant.FREE in model.variants) return "Цена: бесплатно (:free)"
    if (ModelCategory.IMAGE in model.categories) {
        model.estimatedImageOutputUsd1K?.let { estimate ->
            if (estimate > 0.0) return "Изображение: ≈ ${formatCatalogPrice(estimate)} за 1K · итог зависит от размера/качества"
        }
    }
    if (model.promptPriceUsdPerMillion != null || model.completionPriceUsdPerMillion != null) {
        val bothZero = (model.promptPriceUsdPerMillion ?: 0.0) <= 0.0 && (model.completionPriceUsdPerMillion ?: 0.0) <= 0.0
        if (ModelCategory.IMAGE in model.categories && bothZero) return "Изображение: цена зависит от image-тарифа OpenRouter"
        return "Цена / 1M: вход ${formatCatalogPrice(model.promptPriceUsdPerMillion)} · выход ${formatCatalogPrice(model.completionPriceUsdPerMillion)}"
    }
    return null
}

private fun formatCatalogPrice(value: Double?): String = when {
''')

# Hub controller: context-menu action for quick models.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
replace(path,
'''    fun useAsImageModel(model: ModelInfo) {
''',
'''    fun toggleQuickTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) return
        val profile = openRouterProfile() ?: return
        viewModel.toggleQuickTextModelForConnection(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = "Список быстрых моделей обновлён")
    }

    fun useAsImageModel(model: ModelInfo) {
''')

# Tests: paid image models with zero text-token price must never pass the Free filter.
path = "app/src/test/java/com/ayuemin/ymnik/model/ModelCatalogFilterTest.kt"
replace(path,
'''        ModelInfo(
            id = "vendor/image",
            inputModalities = setOf("text"),
            outputModalities = setOf("image")
        ),
''',
'''        ModelInfo(
            id = "vendor/image-paid",
            inputModalities = setOf("text"),
            outputModalities = setOf("image"),
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            imageOutputPriceUsd = 0.0000311377245508982
        ),
        ModelInfo(
            id = "vendor/image:free",
            inputModalities = setOf("text"),
            outputModalities = setOf("image"),
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            imageOutputPriceUsd = 0.0,
            variants = setOf(ModelVariant.FREE)
        ),
''')
replace(path, 'assertEquals(4, ModelCatalogFilter.apply(models).size)', 'assertEquals(5, ModelCatalogFilter.apply(models).size)')
replace(path,
'''    @Test
    fun capabilityFiltersCanBeCombined() {
''',
'''    @Test
    fun imageFreeFilterUsesImageOutputPriceNotZeroTextPrice() {
        assertEquals(
            listOf("vendor/image:free"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.FREE
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/image:free"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.UP_TO_0_5
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/image:free", "vendor/image-paid"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.UP_TO_10
            ).map { it.id }
        )
    }

    @Test
    fun capabilityFiltersCanBeCombined() {
''')

path = "app/src/test/java/com/ayuemin/ymnik/network/OpenRouterModelCatalogTest.kt"
replace(path,
'''    @Test
    fun parsesSpecializedOutputCategories() {
''',
'''    @Test
    fun parsesImageOutputPricingSeparatelyFromTextPricing() {
        val image = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "sourceful/riverflow-v2.5-pro",
                  "architecture": {"input_modalities":["text","image"],"output_modalities":["image"]},
                  "pricing": {"prompt":"0","completion":"0","image_output":"0.0000311377245508982"}
                }
                """.trimIndent()
            )
        )!!
        assertEquals(0.0, image.promptPriceUsdPerMillion!!, 0.000001)
        assertEquals(0.0000311377245508982, image.imageOutputPriceUsd!!, 0.000000000001)
        assertTrue(image.estimatedImageOutputUsd1K!! > 0.12)
        assertTrue(!image.isFreeFor(ModelCategory.IMAGE))
    }

    @Test
    fun parsesSpecializedOutputCategories() {
''')

# Version + changelog.
path = "app/build.gradle.kts"
replace(path, '// Umnik v1.6.1', '// Umnik v1.6.2')
replace(path, 'versionCode = 61\n        versionName = "1.6.1"', 'versionCode = 62\n        versionName = "1.6.2"')

path = "CHANGELOG.md"
replace(path,
'''## Unreleased


''',
'''## Unreleased


## v1.6.2 - 2026-09-13

- Исправлен фильтр цены OpenRouter Hub: нулевые prompt/completion у генераторов изображений больше не считаются признаком бесплатной генерации.
- Для image-моделей учитывается image_output/image_token pricing; фильтр «Бесплатно» больше не показывает платные модели вроде Riverflow без суффикса :free.
- Для категории «Изображения» ценовые пороги показываются в ориентировочной стоимости 1K-изображения, а карточка больше не выводит вводящий в заблуждение «вход $0 · выход $0».
- При активном ценовом фильтре модели сортируются от более дешёвых к дорогим.
- В карточку модели OpenRouter Hub возвращено контекстное меню: выбрать для чата/изображений, добавить или убрать из быстрых, скопировать ID модели.
- Версия: 1.6.2 / versionCode 62.

''')
