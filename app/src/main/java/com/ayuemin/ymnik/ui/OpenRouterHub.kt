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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.ayuemin.ymnik.model.ModelPriceFilter
import com.ayuemin.ymnik.model.ModelVariant
import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.RagSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.VideoJobStatus
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
import kotlinx.coroutines.delay
import java.util.Locale

private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, REPLY_SPEECH, SHELL }
private enum class MediaSection { ALL, VIDEO, TRANSCRIPTION, SPEECH }

@Composable
fun UmnikV16Root(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val controller = remember(viewModel) { OpenRouterHubController(context.applicationContext, viewModel) }
    var open by remember { mutableStateOf(false) }
    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }
    var requestedMediaSection by remember { mutableStateOf(MediaSection.ALL) }
    val asyncSequence by AsyncJobEvents.sequence.collectAsState()
    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()
    val speechRequest by AsyncJobEvents.speechRequest.collectAsState()
    val appState by viewModel.state.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    LaunchedEffect(Unit) {
        controller.refreshCatalog()
    }

    LaunchedEffect(asyncSequence) {
        if (asyncSequence <= 0L) return@LaunchedEffect
        viewModel.refreshAsyncResults()
        controller.refreshJobs()
    }

    LaunchedEffect(speechRequest) {
        val request = speechRequest ?: return@LaunchedEffect
        AsyncJobEvents.consumeSpeechRequest()
        controller.synthesizeAnswer(request.chatId, request.text)
    }

    LaunchedEffect(hubRequest) {
        when (hubRequest) {
            "jobs", "batch" -> {
                requestedPage = HubPage.JOBS
                requestedMediaSection = MediaSection.ALL
                controller.refreshJobs()
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "models", "models-settings" -> {
                requestedPage = HubPage.MODELS
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "routing" -> {
                requestedPage = HubPage.ROUTING
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "tools", "rag" -> {
                requestedPage = HubPage.TOOLS
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "media" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "video" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.VIDEO
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "stt", "transcription" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.TRANSCRIPTION
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "speech", "tts" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.SPEECH
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "reply-speech" -> {
                requestedPage = HubPage.REPLY_SPEECH
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "shell" -> {
                requestedPage = HubPage.SHELL
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
        }
    }

    UmnikTheme(appState.themeChoice, appState.customThemeColor) {
        YmnikApp(viewModel)
        if (open) {
            OpenRouterHubDialog(controller = controller, viewModel = viewModel, initialPage = requestedPage, initialMediaSection = requestedMediaSection, onDismiss = { open = false })
        }
    }
}

@Composable
private fun OpenRouterHubDialog(
    controller: OpenRouterHubController,
    viewModel: ChatViewModel,
    initialPage: HubPage,
    initialMediaSection: MediaSection,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsState()
    val appState by viewModel.state.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    val settingsMode = initialPage == HubPage.MODELS || initialPage == HubPage.ROUTING || initialPage == HubPage.TOOLS

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                topBar = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (page == HubPage.REPLY_SPEECH) {
                                        "Озвучивание ответов"
                                    } else if (settingsMode) {
                                        "OpenRouter: модели и настройки"
                                    } else {
                                        when (page) {
                                            HubPage.JOBS -> "Пакетные и фоновые задачи"
                                            HubPage.MEDIA -> when (initialMediaSection) {
                                                MediaSection.VIDEO -> "Создание видео"
                                                MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                                MediaSection.SPEECH -> "Озвучивание текста и документов"
                                                MediaSection.ALL -> "Медиа"
                                            }
                                            HubPage.SHELL -> "OpenRouter Shell"
                                            else -> "OpenRouter"
                                        }
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (page == HubPage.REPLY_SPEECH) "Отдельная модель и голос для кнопки OR" else if (settingsMode) "Каталог, маршрутизация и работа с документами" else "Результат возвращается в текущий чат",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                        }
                        if (settingsMode) HubPageBar(page = page, onPage = { page = it })
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
                        HubPage.MODELS -> ModelsPage(state, controller, appState)
                        HubPage.ROUTING -> RoutingPage(state.routing, controller::updateRouting)
                        HubPage.TOOLS -> ToolsPage(state.tools, state.rag, controller)
                        HubPage.JOBS -> JobsPage(state, controller)
                        HubPage.MEDIA -> MediaPage(state, controller, initialMediaSection)
                        HubPage.REPLY_SPEECH -> ReplySpeechPage(state, appState, controller)
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
        item { HubPageChip("Инструменты и документы", HubPage.TOOLS, page, onPage) }
    }
}

@Composable
private fun HubPageChip(label: String, value: HubPage, selected: HubPage, onPage: (HubPage) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onPage(value) }, label = { Text(label) })
}

@Composable
private fun ModelsPage(state: OpenRouterHubState, controller: OpenRouterHubController, appState: UiState) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ModelCategory?>(null) }
    var variant by remember { mutableStateOf<ModelVariant?>(null) }
    var price by remember { mutableStateOf(ModelPriceFilter.ALL) }
    var capabilities by remember { mutableStateOf(ModelCapabilityFilter()) }
    var filtersExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val availableCategories = remember(state.catalog) {
        ModelCategory.entries.filter { candidate -> state.catalog.any { candidate in it.categories } }
    }
    val variantOrder = remember {
        listOf(ModelVariant.BATCH, ModelVariant.FREE, ModelVariant.THINKING, ModelVariant.EXTENDED, ModelVariant.ONLINE, ModelVariant.NITRO, ModelVariant.FLOOR)
    }
    val availableVariants = remember(state.catalog) {
        variantOrder.filter { candidate -> state.catalog.any { candidate in it.variants } }
    }
    val selectedIds = remember(
        appState.textModel,
        appState.currentChatTextModel,
        appState.quickTextModels,
        appState.imageModel,
        state.media,
        state.rag
    ) {
        buildSet {
            add(appState.textModel)
            appState.currentChatTextModel?.let(::add)
            appState.quickTextModels.forEach { add(it.substringAfter('\u001F')) }
            add(appState.imageModel)
            add(state.media.batchModel)
            add(state.media.videoModel)
            add(state.media.speechModel)
            add(state.media.transcriptionModel)
            add(state.rag.embeddingModel)
            add(state.rag.rerankModel)
        }.filter(String::isNotBlank).toSet()
    }
    val filtered = remember(state.catalog, query, category, variant, price, capabilities, selectedIds) {
        ModelCatalogFilter.apply(state.catalog, query, category, variant, price, capabilities, limit = 700)
            .sortedWith(compareByDescending<ModelInfo> { it.id in selectedIds }.thenBy { it.id })
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (filtersExpanded && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48)) {
            filtersExpanded = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск по OpenRouter") }
            )
            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Text(if (filtersExpanded) "Свернуть" else "Фильтры")
            }
            IconButton(onClick = { controller.refreshCatalog(forceMessage = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить каталог")
            }
        }
        if (filtersExpanded) {
            Text("Категории", modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Все") }) }
                items(availableCategories) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item)) })
                }
            }
            Text("Варианты", modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = variant == null, onClick = { variant = null }, label = { Text("Все") }) }
                items(availableVariants) { item ->
                    FilterChip(selected = variant == item, onClick = { variant = item }, label = { Text(variantLabel(item)) })
                }
            }
            val priceOptions = when (category) {
                ModelCategory.TEXT, ModelCategory.IMAGE -> ModelPriceFilter.entries.toList()
                else -> listOf(ModelPriceFilter.ALL, ModelPriceFilter.FREE)
            }
            Text(priceSectionLabel(category), modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(priceOptions) { item ->
                    FilterChip(selected = price == item, onClick = { price = item }, label = { Text(priceFilterLabel(item, category)) })
                }
            }
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { CapabilityChip("Vision", capabilities.imageInput) { capabilities = capabilities.copy(imageInput = !capabilities.imageInput) } }
                item { CapabilityChip("Audio", capabilities.audioInput) { capabilities = capabilities.copy(audioInput = !capabilities.audioInput) } }
                item { CapabilityChip("Video", capabilities.videoInput) { capabilities = capabilities.copy(videoInput = !capabilities.videoInput) } }
                item { CapabilityChip("Reasoning", capabilities.reasoning) { capabilities = capabilities.copy(reasoning = !capabilities.reasoning) } }
                item { CapabilityChip("Tools", capabilities.tools) { capabilities = capabilities.copy(tools = !capabilities.tools) } }
            }
        }
        Text(
            "Показано ${filtered.size} из ${state.catalog.size} · выбранные модели сверху",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item { Text(if (state.loading) "Каталог загружается…" else "По фильтрам моделей нет", modifier = Modifier.padding(16.dp)) }
            }
            items(filtered, key = { it.id }) { model -> ModelCatalogCard(model, controller, appState, state) }
        }
    }
}

@Composable
private fun CapabilityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController, appState: UiState, hubState: OpenRouterHubState) {
    val context = LocalContext.current
    var menuOpen by remember(model.id) { mutableStateOf(false) }
    UmnikPanel {
        Column(Modifier.padding(12.dp)) {
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
                        if (ModelCategory.TEXT in model.categories && !model.isBatch) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для чата") },
                                onClick = { menuOpen = false; controller.useAsTextModel(model) }
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить / убрать из быстрых") },
                                onClick = { menuOpen = false; controller.toggleQuickTextModel(model) }
                            )
                            if (appState.textModel == model.id) {
                                DropdownMenuItem(
                                    text = { Text("Сбросить чат на OpenRouter Auto") },
                                    onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.TEXT) }
                                )
                            }
                        }
                        if (model.isBatch) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для пакетных задач") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TEXT) }
                            )
                            if (hubState.media.batchModel == model.id) {
                                DropdownMenuItem(text = { Text("Снять с пакетных задач") }, onClick = { menuOpen = false; controller.clearBatchModel() })
                            }
                        }
                        if (ModelCategory.IMAGE in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для создания изображений") },
                                onClick = { menuOpen = false; controller.useAsImageModel(model) }
                            )
                            if (appState.imageModel == model.id) {
                                DropdownMenuItem(text = { Text("Снять с изображений") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.IMAGE) })
                            }
                        }
                        if (ModelCategory.VIDEO in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для видео") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.VIDEO) })
                            if (hubState.media.videoModel == model.id) DropdownMenuItem(text = { Text("Снять с видео") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.VIDEO) })
                        }
                        if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для озвучивания") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.SPEECH) })
                            if (hubState.media.speechModel == model.id) DropdownMenuItem(text = { Text("Снять с озвучивания") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.SPEECH) })
                        }
                        if (ModelCategory.TRANSCRIPTION in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для распознавания") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TRANSCRIPTION) })
                            if (hubState.media.transcriptionModel == model.id) DropdownMenuItem(text = { Text("Снять с распознавания") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.TRANSCRIPTION) })
                        }
                        if (ModelCategory.EMBEDDINGS in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для поиска по документам") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.EMBEDDINGS) })
                            if (hubState.rag.embeddingModel == model.id) DropdownMenuItem(text = { Text("Снять с поиска по документам") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.EMBEDDINGS) })
                        }
                        if (ModelCategory.RERANK in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для точной сортировки") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.RERANK) })
                            if (hubState.rag.rerankModel == model.id) DropdownMenuItem(text = { Text("Снять с точной сортировки") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.RERANK) })
                        }
                        DropdownMenuItem(
                            text = { Text("Копировать ID модели") },
                            onClick = { menuOpen = false; copyToClipboard(context, model.id) }
                        )
                    }
                }
            }
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
            catalogPriceText(model)?.let { priceText ->
                Text(
                    priceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ModelCategory.TEXT in model.categories && !model.isBatch) SmallAssignButton("Использовать в чате") { controller.useAsTextModel(model) }
                if (ModelCategory.IMAGE in model.categories) SmallAssignButton("Для изображений") { controller.useAsImageModel(model) }
                if (model.isBatch) SmallAssignButton("Для пакета задач") { controller.assignModel(model, ModelCategory.TEXT) }
                if (ModelCategory.VIDEO in model.categories) SmallAssignButton("Для видео") { controller.assignModel(model, ModelCategory.VIDEO) }
                if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) SmallAssignButton("Для озвучивания") { controller.assignModel(model, ModelCategory.SPEECH) }
                if (ModelCategory.TRANSCRIPTION in model.categories) SmallAssignButton("Для распознавания") { controller.assignModel(model, ModelCategory.TRANSCRIPTION) }
                if (ModelCategory.EMBEDDINGS in model.categories) SmallAssignButton("Для поиска по документам") { controller.assignModel(model, ModelCategory.EMBEDDINGS) }
                if (ModelCategory.RERANK in model.categories) SmallAssignButton("Для точной сортировки") { controller.assignModel(model, ModelCategory.RERANK) }
            }
        }
    }
}

private fun priceSectionLabel(category: ModelCategory?): String = when (category) {
    ModelCategory.IMAGE -> "Цена изображения (≈ для 1K; точная зависит от параметров)"
    ModelCategory.TEXT -> "Цена текста (макс. вход/выход за 1M токенов)"
    else -> "Цена"
}

private fun priceFilterLabel(value: ModelPriceFilter, category: ModelCategory?): String = when (value) {
    ModelPriceFilter.ALL -> "Все"
    ModelPriceFilter.FREE -> "Бесплатно"
    ModelPriceFilter.UP_TO_0_5 -> if (category == ModelCategory.IMAGE) "≤ \$0.02" else "≤ \$0.5/M"
    ModelPriceFilter.UP_TO_1 -> if (category == ModelCategory.IMAGE) "≤ \$0.05" else "≤ \$1/M"
    ModelPriceFilter.UP_TO_5 -> if (category == ModelCategory.IMAGE) "≤ \$0.10" else "≤ \$5/M"
    ModelPriceFilter.UP_TO_10 -> if (category == ModelCategory.IMAGE) "≤ \$0.20" else "≤ \$10/M"
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
    value == null -> "—"
    value == 0.0 -> "\$0"
    value < 0.01 -> "$" + "%.4f".format(Locale.US, value)
    else -> "$" + "%.2f".format(Locale.US, value)
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Инструменты обычного чата", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Эти возможности OpenRouter модель может использовать во время обычного разговора. Включайте только то, что действительно нужно задаче.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Text("Режим веб-поиска по умолчанию", fontWeight = FontWeight.SemiBold)
            Text(
                "Включение поиска остаётся в текущем чате. Здесь задаётся режим, который будет предложен по умолчанию.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchPreset.entries) { preset ->
                    FilterChip(
                        selected = tools.webSearchPreset == preset,
                        onClick = { controller.updateTools(tools.copy(webSearchPreset = preset)) },
                        label = { Text(webSearchPresetLabel(preset)) }
                    )
                }
            }
        }
        item {
            Text("Сервис интернет-поиска", fontWeight = FontWeight.SemiBold)
            Text(
                "Auto использует встроенный поиск провайдера, когда он доступен, иначе OpenRouter выбирает совместимый сервис.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchEngine.entries) { engine ->
                    FilterChip(
                        selected = tools.webSearchEngine == engine,
                        onClick = { controller.updateTools(tools.copy(webSearchEngine = engine)) },
                        label = { Text(searchEngineLabel(engine)) }
                    )
                }
            }
        }
        item { ToggleRow("Открывать найденные веб-страницы", tools.webFetch) { controller.updateTools(tools.copy(webFetch = it)) } }
        item { ToggleRow("Использовать текущие дату и время", tools.datetime) { controller.updateTools(tools.copy(datetime = it)) } }
        item { ToggleRow("Разрешить модели создавать изображения как инструмент", tools.imageGeneration) { controller.updateTools(tools.copy(imageGeneration = it)) } }
        item { ToggleRow("Fusion — объединять работу нескольких инструментов", tools.fusion) { controller.updateTools(tools.copy(fusion = it)) } }
        item { ToggleRow("Разрешить Shell прямо в обычном чате", tools.shell) { controller.updateTools(tools.copy(shell = it)) } }

        item {
            HorizontalDivider()
            Text("Поиск по своим документам (RAG)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(
                "RAG сначала находит подходящие фрагменты ваших текстовых файлов, затем передаёт их основной модели. Модели Embeddings и Rerank выбираются во вкладке «Модели» общего каталога.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { ToggleRow("Включить поиск по документам", rag.enabled) { controller.updateRag(rag.copy(enabled = it)) } }
        item {
            Text("Модель смыслового поиска", fontWeight = FontWeight.SemiBold)
            Text(rag.embeddingModel.ifBlank { "Не выбрана — назначьте Embeddings-модель во вкладке «Модели»" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Модель уточнения результатов", fontWeight = FontWeight.SemiBold)
            Text(rag.rerankModel.ifBlank { "Не выбрана — Rerank необязателен" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Сколько подходящих фрагментов передавать модели: ${rag.topK}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = rag.topK.toFloat(),
                onValueChange = { controller.updateRag(rag.copy(topK = it.toInt().coerceIn(1, 20))) },
                valueRange = 1f..20f,
                steps = 18
            )
            Text(
                "Для небольшого PDF сначала попробуйте обычное прикрепление файла. RAG особенно полезен для набора больших текстовых материалов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class BatchDraftTask(
    val text: String = "",
    val files: List<Uri> = emptyList()
)

@Composable
private fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    val tasks = remember { mutableStateListOf(BatchDraftTask()) }
    var bulkInput by remember { mutableStateOf("") }
    var fileTargetIndex by remember { mutableStateOf<Int?>(null) }
    var clearHistoryConfirm by remember { mutableStateOf(false) }
    val taskFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val index = fileTargetIndex
        if (index != null && index in tasks.indices) {
            tasks[index] = tasks[index].copy(files = uris.take(6))
        }
        fileTargetIndex = null
    }
    val readyCount = tasks.count { it.text.isNotBlank() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Пакет из нескольких независимых заданий", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Batch удобен, когда задания не зависят друг от друга. Результаты вернутся в тот чат, из которого вы запустили пакет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            CategoryModelPicker(
                title = "Модель для пакетных задач",
                current = state.media.batchModel,
                models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },
                onSelect = { controller.assignModel(it, ModelCategory.TEXT) }
            )
        }

        item {
            Text("Задания", fontWeight = FontWeight.Bold)
            Text("Каждое поле — отдельный запрос. Файлы можно добавить отдельно к нужной задаче.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEachIndexed { index, task ->
                    UmnikPanel {
                        Column(Modifier.padding(10.dp)) {
                            OutlinedTextField(
                                value = task.text,
                                onValueChange = { tasks[index] = task.copy(text = it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Задача ${index + 1}") },
                                placeholder = { Text(if (index == 0) "Например: сделай краткое резюме текста" else "Введите независимое задание") },
                                minLines = 2,
                                maxLines = 7
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        fileTargetIndex = index
                                        taskFilePicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown", "application/yaml"))
                                    }
                                ) {
                                    Text(if (task.files.isEmpty()) "+ Файлы к задаче" else "Файлы: ${task.files.size}")
                                }
                                Spacer(Modifier.weight(1f))
                                if (task.files.isNotEmpty()) {
                                    TextButton(onClick = { tasks[index] = task.copy(files = emptyList()) }) { Text("Убрать файлы") }
                                }
                                if (tasks.size > 1) {
                                    TextButton(onClick = { tasks.removeAt(index) }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = { tasks.add(BatchDraftTask()) }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить задачу") }
            }
        }

        item {
            Text("Быстро добавить списком", fontWeight = FontWeight.SemiBold)
            Text("Если у вас уже есть список коротких задач, вставьте по одной задаче на строку.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = bulkInput,
                onValueChange = { bulkInput = it },
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                label = { Text("Список задач") },
                placeholder = { Text("Задача 1\nЗадача 2\nЗадача 3") },
                minLines = 3,
                maxLines = 8
            )
            FilledTonalButton(
                onClick = {
                    val imported = bulkInput.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                    if (imported.isNotEmpty()) {
                        if (tasks.size == 1 && tasks.first().text.isBlank() && tasks.first().files.isEmpty()) tasks.clear()
                        tasks.addAll(imported.map { BatchDraftTask(text = it) })
                        bulkInput = ""
                    }
                },
                enabled = bulkInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("Разбить по строкам") }
        }

        item {
            Button(
                onClick = {
                    val readyTasks = tasks.filter { it.text.isNotBlank() }
                    val raw = readyTasks.joinToString("\n---\n") { it.text.trim() }
                    controller.submitBatch(raw, readyTasks.map { it.files })
                },
                enabled = state.media.batchModel.endsWith(":batch", true) && readyCount > 0 && !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Запустить пакет · $readyCount") }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("История Batch", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { clearHistoryConfirm = true },
                    enabled = state.batches.any { it.status.terminal }
                ) { Text("Очистить") }
                TextButton(onClick = controller::refreshJobs) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Обновить")
                }
            }
        }
        if (state.batches.isEmpty()) item { Text("Пока нет Batch-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.batches, key = { it.id }) { job ->
            UmnikPanel {
                Column(Modifier.padding(12.dp)) {
                    Text(job.title, fontWeight = FontWeight.SemiBold)
                    Text("${batchLabel(job.status)} · ${job.completedItems}/${job.totalItems}", style = MaterialTheme.typography.bodySmall)
                    Text(job.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Видео-задания", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = controller::clearFinishedVideoHistory,
                    enabled = state.videos.any { it.status.terminal }
                ) { Text("Очистить") }
            }
        }
        if (state.videos.isEmpty()) item { Text("Пока нет фоновых видео", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.videos, key = { it.id }) { job ->
            UmnikPanel {
                Column(Modifier.padding(12.dp)) {
                    Text(job.modelId, fontWeight = FontWeight.SemiBold)
                    Text(videoLabel(job.status), style = MaterialTheme.typography.bodySmall)
                    Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    job.costUsd?.let { Text("Стоимость: ${formatUsdSmall(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (clearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { clearHistoryConfirm = false },
            title = { Text("Очистить историю Batch?") },
            text = { Text("Готовые, ошибочные и отменённые записи будут удалены. Активные задания останутся и продолжат выполняться.") },
            confirmButton = {
                TextButton(onClick = {
                    clearHistoryConfirm = false
                    controller.clearFinishedBatchHistory()
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun MediaPage(state: OpenRouterHubState, controller: OpenRouterHubController, section: MediaSection) {
    var videoPrompt by remember { mutableStateOf("") }
    val videoRefs = remember { mutableStateListOf<Uri>() }
    var speechText by remember { mutableStateOf("") }
    var voice by remember(state.media.speechModel, state.media.voice) { mutableStateOf(state.media.voice) }
    var speechResponseFormat by remember(state.media.speechModel, state.media.responseFormat) { mutableStateOf(state.media.responseFormat.orEmpty()) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        videoRefs.clear(); videoRefs.addAll(uris.take(4))
    }
    val sttPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::transcribe) }
    val speechTextPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.loadTextFileForInput(it) { loaded -> speechText = loaded } }
    }
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section == MediaSection.ALL || section == MediaSection.VIDEO) {
            item {
                Text("Генерация видео", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель видео",
                    current = state.media.videoModel,
                    models = state.catalog.filter { ModelCategory.VIDEO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.VIDEO) }
                )
                OutlinedTextField(videoPrompt, { videoPrompt = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Описание видео") }, minLines = 3, maxLines = 7)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilledTonalButton(onClick = { videoPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }, modifier = Modifier.weight(1f)) { Text(if (videoRefs.isEmpty()) "Референсы" else "Референсы: ${videoRefs.size}") }
                    Button(onClick = { controller.submitVideo(videoPrompt, videoRefs.toList()); videoPrompt = ""; videoRefs.clear() }, enabled = state.media.videoModel.isNotBlank() && videoPrompt.isNotBlank() && !state.loading, modifier = Modifier.weight(1f)) { Text("Создать") }
                }
                Text("Видео продолжит создаваться в фоне, а готовый файл появится в исходном чате.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.TRANSCRIPTION) {
            item {
                Text("Распознавание речи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель распознавания",
                    current = state.media.transcriptionModel,
                    models = state.catalog.filter { ModelCategory.TRANSCRIPTION in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.TRANSCRIPTION) }
                )
                FilledTonalButton(onClick = { sttPicker.launch(arrayOf("audio/*")) }, enabled = state.media.transcriptionModel.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Выбрать аудиофайл") }
                if (state.transcription.isNotBlank()) {
                    UmnikPanel(modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(state.transcription)
                            TextButton(onClick = { copyToClipboard(context, state.transcription) }) { Text("Копировать") }
                        }
                    }
                }
                Text("Расшифровка также добавляется в текущий чат.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.SPEECH) {
            item {
                Text("Нейросетевая озвучка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val selectedSpeechModel = state.catalog.firstOrNull { it.id == state.media.speechModel }
                val documentVoiceOptions = selectedSpeechModel?.parameterValues("voice").orEmpty()
                CategoryModelPicker(
                    title = "Модель озвучивания",
                    current = state.media.speechModel,
                    models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.SPEECH) }
                )
                if (state.media.speechModel.isNotBlank()) {
                    Text("Дополнительные параметры (необязательно)", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                    Text(
                        "Некоторым моделям нужен голос или конкретный формат, другим достаточно самой модели. «Авто» не передаёт лишний формат и учитывает известные ограничения Gemini/Voxtral.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = voice.isBlank(),
                                onClick = { voice = "" },
                                label = { Text("Без голоса") }
                            )
                        }
                        items(documentVoiceOptions) { option ->
                            FilterChip(
                                selected = voice == option,
                                onClick = { voice = option },
                                label = { Text(option, maxLines = 1) }
                            )
                        }
                    }
                    OutlinedTextField(
                        voice,
                        { voice = it },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text("Voice / ID голоса (необязательно)") },
                        singleLine = true
                    )
                    Text("Формат ответа", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(listOf("" to "Авто", "mp3" to "MP3", "pcm" to "PCM")) { (value, label) ->
                            FilterChip(
                                selected = speechResponseFormat == value,
                                onClick = { speechResponseFormat = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { controller.updateMedia(state.media.copy(voice = voice.trim(), responseFormat = speechResponseFormat.ifBlank { null })) },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                    ) { Text("Сохранить параметры") }
                }
                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                FilledTonalButton(
                    onClick = { speechTextPicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Загрузить текстовый файл") }
                Button(
                    onClick = {
                        controller.updateMedia(state.media.copy(voice = voice.trim(), responseFormat = speechResponseFormat.ifBlank { null }))
                        controller.synthesize(speechText)
                    },
                    enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Создать аудио") }
                state.speechFile?.let { file ->
                    Text("Готово и добавлено в чат: ${file.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}


@Composable
private fun ReplySpeechPage(
    state: OpenRouterHubState,
    appState: UiState,
    controller: OpenRouterHubController
) {
    val selected = state.catalog.firstOrNull { it.id == appState.openRouterSpeechModel }
    val voiceOptions = selected?.parameterValues("voice").orEmpty()
    var manualVoice by remember(appState.openRouterSpeechModel, appState.openRouterSpeechVoice) {
        mutableStateOf(appState.openRouterSpeechVoice)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Кнопка OR под ответами", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Эти настройки не влияют на режим «+ → Озвучить». Достаточно выбрать модель; голос и формат задаются только если они нужны выбранному провайдеру.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            CategoryModelPicker(
                title = "Модель озвучивания ответов",
                current = appState.openRouterSpeechModel,
                models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                onSelect = controller::assignReplySpeechModel
            )
        }
        if (appState.openRouterSpeechModel.isNotBlank()) {
            item {
                Text("Голос (необязательно)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Если у модели есть голос по умолчанию, оставьте «Не задавать». Если OpenRouter требует voice, выберите вариант из списка или введите ID вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = appState.openRouterSpeechVoice.isBlank(),
                            onClick = {
                                manualVoice = ""
                                controller.updateReplySpeechVoice("")
                            },
                            label = { Text("Не задавать") }
                        )
                    }
                    items(voiceOptions) { voice ->
                        FilterChip(
                            selected = appState.openRouterSpeechVoice == voice,
                            onClick = {
                                manualVoice = voice
                                controller.updateReplySpeechVoice(voice)
                            },
                            label = { Text(voice, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = manualVoice,
                    onValueChange = { manualVoice = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    label = { Text("ID голоса") },
                    placeholder = { Text("Оставьте пустым, если голос не нужен") },
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = { controller.updateReplySpeechVoice(manualVoice.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text(if (manualVoice.isBlank()) "Сохранить без голоса" else "Сохранить голос") }
            }
            item {
                Text("Формат ответа", fontWeight = FontWeight.SemiBold)
                Text(
                    "Авто: для Gemini TTS используется PCM, для Voxtral TTS — MP3, а неизвестным моделям Umnik не навязывает формат. Если провайдер вернёт однозначную ошибку формата, Auto один раз повторит запрос с требуемым MP3/PCM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(listOf("" to "Авто", "mp3" to "MP3", "pcm" to "PCM")) { (value, label) ->
                        FilterChip(
                            selected = appState.openRouterSpeechResponseFormat == value,
                            onClick = { controller.updateReplySpeechResponseFormat(value) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "PCM Umnik автоматически оборачивает в WAV для воспроизведения на Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp)
                )
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
            Text("Shell использует Responses API. Загруженные файлы передаются во временный контейнер; созданные контейнером файлы Umnik скачивает в своё хранилище. Результат и созданные файлы добавляются в текущий чат.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Задача") }, minLines = 4, maxLines = 10)
            FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (files.isEmpty()) "Добавить файлы" else "Файлы: ${files.size}") }
            Button(onClick = { controller.runShell(prompt, files.toList()); prompt = ""; files.clear() }, enabled = prompt.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Выполнить через Shell") }
        }
        if (state.shellResult.isNotBlank()) {
            item {
                UmnikPanel {
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
private fun CategoryModelPicker(
    title: String,
    current: String,
    models: List<ModelInfo>,
    onSelect: (ModelInfo) -> Unit
) {
    var open by remember(title, current) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            FilledTonalButton(
                onClick = { open = true },
                enabled = models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    current.ifBlank { if (models.isEmpty()) "Нет подходящих моделей" else "Выбрать модель" },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                models.sortedBy { it.id }.take(160).forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onSelect(model)
                        }
                    )
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
    WebSearchMode.AUTO, WebSearchMode.ALWAYS -> "Включён"
}

private fun webSearchPresetLabel(value: WebSearchPreset): String = when (value) {
    WebSearchPreset.ON_DEMAND -> "По необходимости"
    WebSearchPreset.FAST -> "Быстрый"
    WebSearchPreset.NORMAL -> "Обычный"
    WebSearchPreset.DEEP -> "Глубокий"
}

private fun searchEngineLabel(value: WebSearchEngine): String = when (value) {
    WebSearchEngine.AUTO -> "Auto"
    WebSearchEngine.NATIVE -> "Native"
    WebSearchEngine.EXA -> "Exa"
    WebSearchEngine.PARALLEL -> "Parallel"
    WebSearchEngine.PERPLEXITY -> "Perplexity"
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
