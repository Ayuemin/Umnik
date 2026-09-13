package com.ayuemin.ymnik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.BatchJobStatus
import com.ayuemin.ymnik.model.ModelCapabilityFilter
import com.ayuemin.ymnik.model.ModelCatalogFilter
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelVariant
import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.RagSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.VideoJobStatus
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import kotlinx.coroutines.delay
import java.util.Locale

private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, SHELL }

@Composable
fun UmnikV16Root(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val controller = remember(viewModel) { OpenRouterHubController(context.applicationContext, viewModel) }
    var open by remember { mutableStateOf(false) }
    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }
    val asyncSequence by AsyncJobEvents.sequence.collectAsState()
    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    LaunchedEffect(Unit) {
        controller.refreshCatalog()
    }

    LaunchedEffect(asyncSequence) {
        if (asyncSequence <= 0L) return@LaunchedEffect
        repeat(120) {
            val state = viewModel.state.value
            if (!state.isLoading && state.pendingAttachments.isEmpty()) {
                viewModel.switchChat(state.currentChatId)
                controller.refreshJobs()
                return@LaunchedEffect
            }
            delay(1_000L)
        }
    }

    LaunchedEffect(hubRequest) {
        when (hubRequest) {
            "jobs", "batch" -> {
                requestedPage = HubPage.JOBS
                controller.refreshJobs()
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "models" -> {
                requestedPage = HubPage.MODELS
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            YmnikApp(viewModel)
            SmallFloatingActionButton(
                onClick = { requestedPage = HubPage.MODELS; open = true; controller.refreshJobs() },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text("OR", fontWeight = FontWeight.Bold)
            }
        }
        if (open) {
            OpenRouterHubDialog(controller = controller, viewModel = viewModel, initialPage = requestedPage, onDismiss = { open = false })
        }
    }
}

@Composable
private fun OpenRouterHubDialog(
    controller: OpenRouterHubController,
    viewModel: ChatViewModel,
    initialPage: HubPage,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("OpenRouter Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Umnik 1.6 · полный каталог и возможности", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                        }
                        HubPageBar(page = page, onPage = { page = it })
                        if (state.loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.operation?.let {
                                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    state.status?.let { status ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)) {
                            Text(status, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when (page) {
                        HubPage.MODELS -> ModelsPage(state, controller)
                        HubPage.ROUTING -> RoutingPage(state.routing, controller::updateRouting)
                        HubPage.TOOLS -> ToolsPage(state.tools, state.rag, controller)
                        HubPage.JOBS -> JobsPage(state, controller)
                        HubPage.MEDIA -> MediaPage(state, controller)
                        HubPage.SHELL -> ShellPage(state, controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun HubPageBar(page: HubPage, onPage: (HubPage) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { HubPageChip("Модели", HubPage.MODELS, page, onPage) }
        item { HubPageChip("Маршрутизация", HubPage.ROUTING, page, onPage) }
        item { HubPageChip("Tools + RAG", HubPage.TOOLS, page, onPage) }
        item { HubPageChip("Задания", HubPage.JOBS, page, onPage) }
        item { HubPageChip("Медиа", HubPage.MEDIA, page, onPage) }
        item { HubPageChip("Shell", HubPage.SHELL, page, onPage) }
    }
}

@Composable
private fun HubPageChip(label: String, value: HubPage, selected: HubPage, onPage: (HubPage) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onPage(value) }, label = { Text(label) })
}

@Composable
private fun ModelsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ModelCategory?>(null) }
    var variant by remember { mutableStateOf<ModelVariant?>(null) }
    var capabilities by remember { mutableStateOf(ModelCapabilityFilter()) }
    val filtered = remember(state.catalog, query, category, variant, capabilities) {
        ModelCatalogFilter.apply(state.catalog, query, category, variant, capabilities, limit = 700)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск по всему OpenRouter") }
            )
            IconButton(onClick = { controller.refreshCatalog(forceMessage = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить каталог")
            }
        }
        Text("Категории", modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.labelMedium)
        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Все") }) }
            items(ModelCategory.entries) { item ->
                FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item)) })
            }
        }
        Text("Варианты", modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = variant == null, onClick = { variant = null }, label = { Text("Все") }) }
            items(listOf(ModelVariant.BATCH, ModelVariant.FREE, ModelVariant.THINKING, ModelVariant.EXTENDED, ModelVariant.ONLINE, ModelVariant.NITRO, ModelVariant.FLOOR)) { item ->
                FilterChip(selected = variant == item, onClick = { variant = item }, label = { Text(variantLabel(item)) })
            }
        }
        LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { CapabilityChip("Vision", capabilities.imageInput) { capabilities = capabilities.copy(imageInput = !capabilities.imageInput) } }
            item { CapabilityChip("Audio", capabilities.audioInput) { capabilities = capabilities.copy(audioInput = !capabilities.audioInput) } }
            item { CapabilityChip("Video", capabilities.videoInput) { capabilities = capabilities.copy(videoInput = !capabilities.videoInput) } }
            item { CapabilityChip("Reasoning", capabilities.reasoning) { capabilities = capabilities.copy(reasoning = !capabilities.reasoning) } }
            item { CapabilityChip("Tools", capabilities.tools) { capabilities = capabilities.copy(tools = !capabilities.tools) } }
        }
        Text("Показано ${filtered.size} из ${state.catalog.size}", modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item { Text(if (state.loading) "Каталог загружается…" else "По фильтрам моделей нет", modifier = Modifier.padding(16.dp)) }
            }
            items(filtered, key = { it.id }) { model -> ModelCatalogCard(model, controller) }
        }
    }
}

@Composable
private fun CapabilityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(model.id, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(model.categories.joinToString(" · ") { categoryLabel(it) })
                    val variants = model.variants.filterNot { it == ModelVariant.STANDARD }
                    if (variants.isNotEmpty()) append(" · " + variants.joinToString(" · ") { variantLabel(it) })
                    model.contextLength?.let { append(" · контекст ${it / 1000}K") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ModelCategory.TEXT in model.categories) SmallAssignButton(if (model.isBatch) "Чат / Batch" else "В чат") { controller.useAsTextModel(model) }
                if (ModelCategory.IMAGE in model.categories) SmallAssignButton("Изображения") { controller.useAsImageModel(model) }
                if (model.isBatch) SmallAssignButton("Batch") { controller.assignModel(model, ModelCategory.TEXT) }
                if (ModelCategory.VIDEO in model.categories) SmallAssignButton("Видео") { controller.assignModel(model, ModelCategory.VIDEO) }
                if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) SmallAssignButton("Озвучка") { controller.assignModel(model, ModelCategory.SPEECH) }
                if (ModelCategory.TRANSCRIPTION in model.categories) SmallAssignButton("Распознавание") { controller.assignModel(model, ModelCategory.TRANSCRIPTION) }
                if (ModelCategory.EMBEDDINGS in model.categories) SmallAssignButton("Embedding") { controller.assignModel(model, ModelCategory.EMBEDDINGS) }
                if (ModelCategory.RERANK in model.categories) SmallAssignButton("Rerank") { controller.assignModel(model, ModelCategory.RERANK) }
            }
        }
    }
}

@Composable
private fun SmallAssignButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun RoutingPage(value: ProviderRoutingSettings, save: (ProviderRoutingSettings) -> Unit) {
    var order by remember(value.providerOrder) { mutableStateOf(value.providerOrder.joinToString(", ")) }
    var only by remember(value.providerOnly) { mutableStateOf(value.providerOnly.joinToString(", ")) }
    var ignore by remember(value.providerIgnore) { mutableStateOf(value.providerIgnore.joinToString(", ")) }
    var quantizations by remember(value.quantizations) { mutableStateOf(value.quantizations.joinToString(", ")) }
    var fallbacks by remember(value.fallbackModels) { mutableStateOf(value.fallbackModels.joinToString(", ")) }
    var promptPrice by remember(value.maxPromptUsdPerMillion) { mutableStateOf(value.maxPromptUsdPerMillion?.toString().orEmpty()) }
    var completionPrice by remember(value.maxCompletionUsdPerMillion) { mutableStateOf(value.maxCompletionUsdPerMillion?.toString().orEmpty()) }
    var imagePrice by remember(value.maxImageUsd) { mutableStateOf(value.maxImageUsd?.toString().orEmpty()) }
    var requestPrice by remember(value.maxRequestUsd) { mutableStateOf(value.maxRequestUsd?.toString().orEmpty()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Стратегия провайдера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ProviderRouteStrategy.entries) { strategy ->
                    FilterChip(
                        selected = value.strategy == strategy,
                        onClick = { save(value.copy(strategy = strategy)) },
                        label = { Text(routeLabel(strategy)) }
                    )
                }
            }
        }
        item { ToggleRow("Разрешить fallback провайдера", value.allowProviderFallbacks) { save(value.copy(allowProviderFallbacks = it)) } }
        item { ToggleRow("Требовать поддержку параметров", value.requireParameters) { save(value.copy(requireParameters = it)) } }
        item { ToggleRow("Zero Data Retention", value.zeroDataRetention) { save(value.copy(zeroDataRetention = it)) } }
        item { ToggleRow("Запретить сбор данных", value.denyDataCollection) { save(value.copy(denyDataCollection = it)) } }
        item { CsvField("Приоритет провайдеров (order)", order) { order = it } }
        item { CsvField("Разрешить только (only)", only) { only = it } }
        item { CsvField("Исключить (ignore)", ignore) { ignore = it } }
        item { CsvField("Квантизации", quantizations) { quantizations = it } }
        item { CsvField("Fallback-модели", fallbacks) { fallbacks = it } }
        item {
            Text("Максимальная цена", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PriceField("Prompt / 1M", promptPrice, { promptPrice = it }, Modifier.weight(1f))
                PriceField("Completion / 1M", completionPrice, { completionPrice = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PriceField("Image", imagePrice, { imagePrice = it }, Modifier.weight(1f))
                PriceField("Request", requestPrice, { requestPrice = it }, Modifier.weight(1f))
            }
        }
        item {
            Button(
                onClick = {
                    save(value.copy(
                        providerOrder = csv(order),
                        providerOnly = csv(only),
                        providerIgnore = csv(ignore),
                        quantizations = csv(quantizations),
                        fallbackModels = csv(fallbacks),
                        maxPromptUsdPerMillion = promptPrice.toDoubleOrNull(),
                        maxCompletionUsdPerMillion = completionPrice.toDoubleOrNull(),
                        maxImageUsd = imagePrice.toDoubleOrNull(),
                        maxRequestUsd = requestPrice.toDoubleOrNull()
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить маршрутизацию") }
        }
        item {
            Text("Эти параметры применяются к обычным запросам OpenRouter непосредственно перед отправкой. Для других подключений Umnik их не использует.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolsPage(tools: ServerToolSettings, rag: RagSettings, controller: OpenRouterHubController) {
    var advisor by remember(tools.advisorModel) { mutableStateOf(tools.advisorModel.orEmpty()) }
    var subagent by remember(tools.subagentModel) { mutableStateOf(tools.subagentModel.orEmpty()) }
    var embedding by remember(rag.embeddingModel) { mutableStateOf(rag.embeddingModel) }
    var rerank by remember(rag.rerankModel) { mutableStateOf(rag.rerankModel) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Server tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item {
            Text("Web Search", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchMode.entries) { mode ->
                    FilterChip(selected = tools.webSearch == mode, onClick = { controller.updateTools(tools.copy(webSearch = mode)) }, label = { Text(webModeLabel(mode)) })
                }
            }
        }
        item {
            Text("Поисковый движок", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchEngine.entries) { engine ->
                    FilterChip(selected = tools.webSearchEngine == engine, onClick = { controller.updateTools(tools.copy(webSearchEngine = engine)) }, label = { Text(engine.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
        }
        item { ToggleRow("Web Fetch", tools.webFetch) { controller.updateTools(tools.copy(webFetch = it)) } }
        item { ToggleRow("DateTime", tools.datetime) { controller.updateTools(tools.copy(datetime = it)) } }
        item { ToggleRow("Image Generation tool", tools.imageGeneration) { controller.updateTools(tools.copy(imageGeneration = it)) } }
        item { ToggleRow("Fusion", tools.fusion) { controller.updateTools(tools.copy(fusion = it)) } }
        item { ToggleRow("Shell в обычном чате", tools.shell) { controller.updateTools(tools.copy(shell = it)) } }
        item {
            OutlinedTextField(advisor, { advisor = it }, Modifier.fillMaxWidth(), label = { Text("Advisor model ID") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(subagent, { subagent = it }, Modifier.fillMaxWidth(), label = { Text("Subagent model ID") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = { controller.updateTools(tools.copy(advisorModel = advisor.trim().ifBlank { null }, subagentModel = subagent.trim().ifBlank { null })) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить Advisor / Subagent") }
        }
        item { HorizontalDivider(); Text("RAG проектов и файлов чата", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { ToggleRow("Включить RAG", rag.enabled) { controller.updateRag(rag.copy(enabled = it)) } }
        item {
            OutlinedTextField(embedding, { embedding = it }, Modifier.fillMaxWidth(), label = { Text("Embedding model") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(rerank, { rerank = it }, Modifier.fillMaxWidth(), label = { Text("Rerank model, необязательно") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Text("Фрагментов в ответ: ${rag.topK}")
            Slider(value = rag.topK.toFloat(), onValueChange = { controller.updateRag(rag.copy(topK = it.toInt().coerceIn(1, 20))) }, valueRange = 1f..20f, steps = 18)
            Button(onClick = { controller.updateRag(rag.copy(embeddingModel = embedding.trim(), rerankModel = rerank.trim())) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить RAG") }
            Text("RAG выключен по умолчанию. Сейчас он индексирует текстовые вложения перед запросом; PDF и другие форматы продолжают передаваться штатным способом OpenRouter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Пакетная обработка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Модель: ${state.media.batchModel.ifBlank { "не выбрана" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    label = { Text("Задания") },
                    placeholder = { Text("Разделяйте независимые задания строкой ---") },
                    minLines = 4,
                    maxLines = 10
                )
                Spacer(Modifier.height(7.dp))
                Button(onClick = { controller.submitBatch(input); input = "" }, enabled = state.media.batchModel.endsWith(":batch", true) && input.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Отправить Batch") }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Batch-задания", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = controller::refreshJobs) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Обновить") }
                }
            }
            if (state.batches.isEmpty()) item { Text("Пока нет Batch-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.batches, key = { it.id }) { job ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(job.title, fontWeight = FontWeight.SemiBold)
                        Text("${batchLabel(job.status)} · ${job.completedItems}/${job.totalItems}", style = MaterialTheme.typography.bodySmall)
                        Text(job.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { HorizontalDivider(); Text("Видео-задания", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
            if (state.videos.isEmpty()) item { Text("Пока нет фоновых видео", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.videos, key = { it.id }) { job ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(job.modelId, fontWeight = FontWeight.SemiBold)
                        Text(videoLabel(job.status), style = MaterialTheme.typography.bodySmall)
                        Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        job.costUsd?.let { Text("Стоимость: ${formatUsdSmall(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    var videoPrompt by remember { mutableStateOf("") }
    val videoRefs = remember { mutableStateListOf<Uri>() }
    var speechText by remember { mutableStateOf("") }
    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        videoRefs.clear(); videoRefs.addAll(uris.take(4))
    }
    val sttPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::transcribe) }
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Генерация видео", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Модель: ${state.media.videoModel.ifBlank { "выберите в каталоге → Видео" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(videoPrompt, { videoPrompt = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Описание видео") }, minLines = 3, maxLines = 7)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilledTonalButton(onClick = { videoPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }, modifier = Modifier.weight(1f)) { Text(if (videoRefs.isEmpty()) "Референсы" else "Референсы: ${videoRefs.size}") }
                Button(onClick = { controller.submitVideo(videoPrompt, videoRefs.toList()); videoPrompt = ""; videoRefs.clear() }, enabled = state.media.videoModel.isNotBlank() && videoPrompt.isNotBlank() && !state.loading, modifier = Modifier.weight(1f)) { Text("Создать") }
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Распознавание речи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Модель: ${state.media.transcriptionModel.ifBlank { "выберите в каталоге → STT" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { sttPicker.launch(arrayOf("audio/*")) }, enabled = state.media.transcriptionModel.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Выбрать аудиофайл") }
            if (state.transcription.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.transcription)
                        TextButton(onClick = { copyToClipboard(context, state.transcription) }) { Text("Копировать") }
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Нейросетевая озвучка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Модель: ${state.media.speechModel.ifBlank { "выберите в каталоге → Speech" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Voice, если модель поддерживает") }, singleLine = true)
            OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
            Button(onClick = { controller.updateMedia(state.media.copy(voice = voice.trim())); controller.synthesize(speechText) }, enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("Создать аудио") }
            state.speechFile?.let { file ->
                Text("Готово: ${file.name} · файл сохранён в «Хранилище Umnik»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ShellPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    var prompt by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files.clear(); files.addAll(uris.take(10))
    }
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("OpenRouter Shell", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Shell использует Responses API. Загруженные файлы передаются во временный контейнер; созданные контейнером файлы Umnik скачивает в своё хранилище.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Задача") }, minLines = 4, maxLines = 10)
            FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (files.isEmpty()) "Добавить файлы" else "Файлы: ${files.size}") }
            Button(onClick = { controller.runShell(prompt, files.toList()); prompt = ""; files.clear() }, enabled = prompt.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Выполнить через Shell") }
        }
        if (state.shellResult.isNotBlank()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.shellResult)
                        TextButton(onClick = { copyToClipboard(context, state.shellResult) }) { Text("Копировать результат") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CsvField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun PriceField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValue, modifier, label = { Text(label) }, singleLine = true)
}

private fun csv(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotBlank).distinct()

private fun categoryLabel(value: ModelCategory): String = when (value) {
    ModelCategory.TEXT -> "Текст"
    ModelCategory.IMAGE -> "Изображения"
    ModelCategory.VIDEO -> "Видео"
    ModelCategory.SPEECH -> "Speech"
    ModelCategory.TRANSCRIPTION -> "STT"
    ModelCategory.EMBEDDINGS -> "Embeddings"
    ModelCategory.RERANK -> "Rerank"
    ModelCategory.AUDIO -> "Audio"
}

private fun variantLabel(value: ModelVariant): String = when (value) {
    ModelVariant.STANDARD -> "Обычная"
    ModelVariant.BATCH -> "Batch"
    ModelVariant.FREE -> "Free"
    ModelVariant.THINKING -> "Thinking"
    ModelVariant.EXTENDED -> "Extended"
    ModelVariant.ONLINE -> "Online"
    ModelVariant.NITRO -> "Nitro"
    ModelVariant.FLOOR -> "Floor"
}

private fun routeLabel(value: ProviderRouteStrategy): String = when (value) {
    ProviderRouteStrategy.AUTO -> "Авто"
    ProviderRouteStrategy.CHEAPEST -> "Дешевле"
    ProviderRouteStrategy.HIGHEST_THROUGHPUT -> "Быстрее"
    ProviderRouteStrategy.LOWEST_LATENCY -> "Ниже задержка"
}

private fun webModeLabel(value: WebSearchMode): String = when (value) {
    WebSearchMode.OFF -> "Выкл"
    WebSearchMode.AUTO -> "Авто"
    WebSearchMode.ALWAYS -> "Всегда"
}

private fun batchLabel(value: BatchJobStatus): String = when (value) {
    BatchJobStatus.VALIDATING -> "Проверка"
    BatchJobStatus.QUEUED -> "В очереди"
    BatchJobStatus.IN_PROGRESS -> "Выполняется"
    BatchJobStatus.FINALIZING -> "Завершается"
    BatchJobStatus.COMPLETED -> "Готово"
    BatchJobStatus.FAILED -> "Ошибка"
    BatchJobStatus.CANCELLED -> "Отменено"
    BatchJobStatus.EXPIRED -> "Истекло"
    BatchJobStatus.UNKNOWN -> "Неизвестно"
}

private fun videoLabel(value: VideoJobStatus): String = when (value) {
    VideoJobStatus.PENDING -> "Подготовка"
    VideoJobStatus.QUEUED -> "В очереди"
    VideoJobStatus.IN_PROGRESS -> "Генерация"
    VideoJobStatus.COMPLETED -> "Готово"
    VideoJobStatus.FAILED -> "Ошибка"
    VideoJobStatus.CANCELLED -> "Отменено"
    VideoJobStatus.EXPIRED -> "Истекло"
    VideoJobStatus.UNKNOWN -> "Неизвестно"
}

private fun formatUsdSmall(value: Double): String = "$" + "%.4f".format(Locale.US, value.coerceAtLeast(0.0))

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Umnik", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}
