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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
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
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelParameterCapability
import com.ayuemin.ymnik.model.ModelUniversality
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, REPLY_SPEECH, SHELL }
private enum class MediaSection { ALL, VIDEO, TRANSCRIPTION, SPEECH }
private enum class SimpleModelKind {
    ALL,
    TEXT,
    IMAGE,
    VIDEO,
    BATCH,
    SPEECH,
    TRANSCRIPTION,
    EMBEDDINGS,
    RERANK,
    AUDIO_INPUT,
    MULTIMODAL,
    REASONING,
    TOOLS
}

private enum class SimplePriceFilter {
    ALL,
    FREE,
    UP_TO_0_02,
    UP_TO_0_05,
    UP_TO_0_1,
    UP_TO_1,
    UP_TO_5,
    OVER_5
}

@Composable
fun UmnikV16Root(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val controller = remember(viewModel) { OpenRouterHubController(context.applicationContext, viewModel) }
    var open by remember { mutableStateOf(false) }
    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }
    var requestedMediaSection by remember { mutableStateOf(MediaSection.ALL) }
    var requestedReturnLabel by remember { mutableStateOf<String?>(null) }
    val asyncSequence by AsyncJobEvents.sequence.collectAsState()
    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()
    val hubReturnLabel by AsyncJobEvents.hubReturnLabel.collectAsState()
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
        if (hubRequest != null) requestedReturnLabel = hubReturnLabel
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
            OpenRouterHubDialog(
                controller = controller,
                viewModel = viewModel,
                initialPage = requestedPage,
                initialMediaSection = requestedMediaSection,
                returnLabel = requestedReturnLabel,
                onDismiss = { open = false; requestedReturnLabel = null }
            )
        }
    }
}

@Composable
private fun OpenRouterHubDialog(
    controller: OpenRouterHubController,
    viewModel: ChatViewModel,
    initialPage: HubPage,
    initialMediaSection: MediaSection,
    returnLabel: String?,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsState()
    val appState by viewModel.state.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    val settingsMode = initialPage == HubPage.MODELS || initialPage == HubPage.ROUTING || initialPage == HubPage.TOOLS
    val activeReturnLabel = returnLabel?.takeIf { page == HubPage.MODELS && it.isNotBlank() }
    val returnFromModels: () -> Unit = onDismiss

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
                                    if (page == HubPage.REPLY_SPEECH) "Отдельная модель и голос для кнопки OR" else if (settingsMode) "Каталог, маршрутизация и инструменты" else "Результат возвращается в текущий чат",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (page == HubPage.MODELS && !activeReturnLabel.isNullOrBlank()) {
                                TextButton(onClick = returnFromModels) {
                                    Icon(Icons.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Вернуться")
                                }
                            } else {
                                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть") }
                            }
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
                    if (page == HubPage.MODELS && !activeReturnLabel.isNullOrBlank()) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Каталог открыт из: $activeReturnLabel",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = returnFromModels) { Text("Вернуться") }
                            }
                        }
                    }
                    state.status?.let { status ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)) {
                            Text(status, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when (page) {
                        HubPage.MODELS -> ModelsPage(state, controller, appState)
                        HubPage.ROUTING -> RoutingPage(state.routing, controller::updateRouting)
                        HubPage.TOOLS -> ToolsPage(state.tools, controller)
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
        item { HubPageChip("Инструменты", HubPage.TOOLS, page, onPage) }
    }
}

@Composable
private fun HubPageChip(label: String, value: HubPage, selected: HubPage, onPage: (HubPage) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onPage(value) }, label = { Text(label) })
}

@Composable
private fun ModelsPage(state: OpenRouterHubState, controller: OpenRouterHubController, appState: UiState) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SimpleModelKind.ALL) }
    var price by remember { mutableStateOf(SimplePriceFilter.ALL) }
    var sortByCapabilities by remember { mutableStateOf(false) }
    var moreKindsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val mainKinds = remember {
        listOf(
            SimpleModelKind.ALL,
            SimpleModelKind.TEXT,
            SimpleModelKind.IMAGE,
            SimpleModelKind.VIDEO,
            SimpleModelKind.BATCH,
            SimpleModelKind.SPEECH
        )
    }
    val extraKinds = remember {
        listOf(
            SimpleModelKind.TRANSCRIPTION,
            SimpleModelKind.EMBEDDINGS,
            SimpleModelKind.RERANK,
            SimpleModelKind.AUDIO_INPUT,
            SimpleModelKind.MULTIMODAL,
            SimpleModelKind.REASONING,
            SimpleModelKind.TOOLS
        )
    }

    val filtered = remember(state.catalog, query, kind, price, sortByCapabilities) {
        val needle = query.trim()
        state.catalog.asSequence()
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
            .filter { model -> modelMatchesSimpleKind(model, kind) }
            .filter { model -> modelMatchesSimplePrice(model, kind, price) }
            .sortedWith(
                if (sortByCapabilities) {
                    compareByDescending<ModelInfo> { ModelUniversality.score(it).total }
                        .thenBy { (it.name ?: it.id).lowercase(Locale.ROOT) }
                        .thenBy { it.id }
                } else {
                    compareBy<ModelInfo> { (it.name ?: it.id).lowercase(Locale.ROOT) }
                        .thenBy { it.id }
                }
            )
            .take(700)
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Название модели или ID") }
            )
            IconButton(onClick = { controller.refreshCatalog(forceMessage = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить каталог")
            }
        }

        Text(
            "Тип",
            modifier = Modifier.padding(start = 14.dp, top = 1.dp),
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(mainKinds) { item ->
                    FilterChip(
                        selected = kind == item,
                        onClick = { kind = item },
                        label = { Text(simpleModelKindLabel(item)) }
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = kind in extraKinds,
                onClick = { moreKindsOpen = true },
                label = {
                    Text(
                        if (kind in extraKinds) simpleModelKindLabel(kind) else "Больше",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        Text(
            "Стоимость",
            modifier = Modifier.padding(start = 14.dp, top = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(SimplePriceFilter.entries) { item ->
                FilterChip(
                    selected = price == item,
                    onClick = { price = item },
                    label = { Text(simplePriceFilterLabel(item)) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = sortByCapabilities,
                onClick = { sortByCapabilities = !sortByCapabilities },
                label = { Text(if (sortByCapabilities) "Возможности ↓" else "По возможностям") }
            )
            Spacer(Modifier.weight(1f))
            if (kind != SimpleModelKind.ALL || price != SimplePriceFilter.ALL || sortByCapabilities) {
                TextButton(
                    onClick = {
                        kind = SimpleModelKind.ALL
                        price = SimplePriceFilter.ALL
                        sortByCapabilities = false
                    }
                ) {
                    Text("Сбросить")
                }
            }
        }

        Text(
            buildString {
                append("Показано ${filtered.size} из ${state.catalog.size}")
                if (!sortByCapabilities) append(" · по алфавиту")
                else append(" · больше возможностей выше")
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
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
                item {
                    Text(
                        if (state.loading) "Каталог загружается…" else "По выбранным условиям моделей нет",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(filtered, key = { it.id }) { model ->
                ModelCatalogCard(model, controller, appState, state)
            }
        }
    }

    if (moreKindsOpen) {
        AlertDialog(
            onDismissRequest = { moreKindsOpen = false },
            title = { Text("Другие типы и возможности") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(extraKinds) { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = {
                                kind = item
                                moreKindsOpen = false
                            },
                            label = {
                                Text(
                                    simpleModelKindLabel(item),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moreKindsOpen = false }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController, appState: UiState, hubState: OpenRouterHubState) {
    val context = LocalContext.current
    val universality = remember(model) { ModelUniversality.score(model) }
    var menuOpen by remember(model.id) { mutableStateOf(false) }
    var infoOpen by remember(model.id) { mutableStateOf(false) }

    UmnikPanel {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name?.takeIf { it.isNotBlank() } ?: model.id,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!model.name.isNullOrBlank() && model.name != model.id) {
                        Text(
                            model.id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(
                    onClick = { copyToClipboard(context, model.id) },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Копировать", style = MaterialTheme.typography.labelSmall)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Информация о модели") },
                            onClick = {
                                menuOpen = false
                                infoOpen = true
                            }
                        )
                        if (ModelCategory.TEXT in model.categories && !model.isBatch) {
                            DropdownMenuItem(
                                text = { Text("Основная модель чатов по умолчанию") },
                                onClick = { menuOpen = false; controller.useAsTextModel(model) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (appState.quickTextModels.any { it.substringAfter('\u001F') == model.id })
                                            "Удалить из дополнительных моделей чатов"
                                        else
                                            "Добавить в дополнительные модели чатов"
                                    )
                                },
                                onClick = { menuOpen = false; controller.toggleQuickTextModel(model) }
                            )
                        }
                    }
                }
            }

            val contextTokens = maxOf(model.contextLength ?: 0, model.topProviderContextLength ?: 0)
            Text(
                buildString {
                    append(model.categories.joinToString(" · ") { categoryLabel(it) })
                    if (contextTokens > 0) append(" · контекст ${compactTokenCount(contextTokens)}")
                    val variants = model.variants.filterNot { it == ModelVariant.STANDARD }
                    if (variants.isNotEmpty()) append(" · " + variants.joinToString(" · ") { variantLabel(it) })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val inputText = model.inputModalities.map(::modalityLabel).distinct().joinToString(" · ")
            val outputText = model.outputModalities.map(::modalityLabel).distinct().joinToString(" · ")
            Text(
                "Вход: ${inputText.ifBlank { "—" }}  •  Выход: ${outputText.ifBlank { "—" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            )

            val keyCapabilities = buildList {
                if (model.isMultimodalChat) add("мультимодальный чат")
                else {
                    if (model.accepts("image")) add("понимает изображения")
                    if (model.outputs("image")) add("создаёт изображения")
                }
                if (model.supportsReasoning) add("reasoning")
                if (model.supportsTools) add("tools")
                if (model.supportsStreaming == true) add("streaming")
            }
            if (keyCapabilities.isNotEmpty()) {
                Text(
                    keyCapabilities.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(top = 3.dp)
            ) {
                Text(
                    "Возможности ${universality.total}/100",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            catalogPriceText(model)?.let { priceText ->
                Text(priceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        }
    }

    if (infoOpen) {
        ModelInfoDialog(model = model, onDismiss = { infoOpen = false })
    }
}

@Composable
private fun ModelInfoDialog(model: ModelInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val universality = remember(model) { ModelUniversality.score(model) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                    }
                    Text(
                        "Информация о модели",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { copyToClipboard(context, model.id) }) {
                        Text("ID")
                    }
                }
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(model.name?.takeIf { it.isNotBlank() } ?: model.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (!model.name.isNullOrBlank() && model.name != model.id) {
                            Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        model.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    item {
                        ModelInfoSection("Основное") {
                            ModelDetailLine("Провайдер", model.providerId)
                            ModelDetailLine("Категории", model.categories.joinToString(", ") { categoryLabel(it) })
                            if (model.variants.isNotEmpty()) ModelDetailLine("Варианты", model.variants.joinToString(", ") { variantLabel(it) })
                            model.createdAtEpochSeconds?.let { ModelDetailLine("Добавлена / создана", formatModelDate(it)) }
                            model.canonicalSlug?.let { ModelDetailLine("Canonical slug", it) }
                            model.huggingFaceId?.let { ModelDetailLine("Hugging Face", it) }
                        }
                    }

                    item {
                        ModelInfoSection("Возможности ${universality.total}/100") {
                            Text(
                                "Это показатель широты функций, которые OpenRouter заявляет для модели. 100/100 означает максимально широкий набор поддерживаемых возможностей, а не качество, интеллект, скорость или цену.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            ModelDetailLine("Входные модальности", "${universality.inputBreadth}/20")
                            ModelDetailLine("Выходные модальности", "${universality.outputBreadth}/25")
                            ModelDetailLine("Общие функции API", "${universality.generalCapabilities}/30")
                            ModelDetailLine("Контекст и размер ответа", "${universality.capacity}/15")
                            ModelDetailLine("Специализированные возможности", "${universality.specializedCapabilities}/10")
                        }
                    }

                    item {
                        ModelInfoSection("Вход и выход") {
                            ModelDetailLine("Принимает", model.inputModalities.sortedBy(::modalitySortKey).joinToString(", ") { modalityLabel(it) })
                            ModelDetailLine("Выдаёт", model.outputModalities.sortedBy(::modalitySortKey).joinToString(", ") { modalityLabel(it) })
                            ModelDetailLine("Мультимодальный чат", if (model.isMultimodalChat) "да" else "нет")
                        }
                    }

                    item {
                        ModelInfoSection("Архитектура и лимиты") {
                            model.architectureModality?.let { ModelDetailLine("Модальность архитектуры", it) }
                            model.tokenizer?.let { ModelDetailLine("Токенизатор", it) }
                            model.instructType?.let { ModelDetailLine("Формат инструкций", it) }
                            model.contextLength?.let { ModelDetailLine("Контекст модели", compactTokenCount(it)) }
                            model.topProviderContextLength?.let { ModelDetailLine("Контекст top provider", compactTokenCount(it)) }
                            model.maxCompletionTokens?.let { ModelDetailLine("Максимальный ответ", compactTokenCount(it)) }
                            model.supportsStreaming?.let { ModelDetailLine("Streaming", if (it) "да" else "нет") }
                            model.topProviderModerated?.let { ModelDetailLine("Модерация top provider", if (it) "да" else "нет") }
                        }
                    }

                    if (model.supportsReasoning || model.reasoningEfforts.isNotEmpty()) {
                        item {
                            ModelInfoSection("Reasoning") {
                                ModelDetailLine("Поддерживается", if (model.supportsReasoning) "да" else "нет")
                                ModelDetailLine("Обязательное", if (model.reasoningMandatory) "да" else "нет")
                                ModelDetailLine("По умолчанию", if (model.reasoningDefaultEnabled) "включено" else "выключено")
                                if (model.reasoningEfforts.isNotEmpty()) {
                                    ModelDetailLine("Уровни", model.reasoningEfforts.sorted().joinToString(", "))
                                }
                            }
                        }
                    }

                    if (model.supportedParameters.isNotEmpty()) {
                        item {
                            ModelInfoSection("Параметры API") {
                                model.supportedParameters.sortedBy(::parameterSortKey).forEach { parameter ->
                                    val descriptor = model.parameterCapabilities[parameter]
                                    Text(
                                        "• ${parameterCapabilityText(parameter, descriptor)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (model.capabilityValues.isNotEmpty() || model.capabilityFlags.isNotEmpty()) {
                        item {
                            ModelInfoSection("Специализированные возможности") {
                                model.capabilityValues.toSortedMap().forEach { (key, values) ->
                                    ModelDetailLine(capabilityFieldLabel(key), values.joinToString(", "))
                                }
                                model.capabilityFlags.toSortedMap().forEach { (key, value) ->
                                    ModelDetailLine(capabilityFieldLabel(key), if (value) "да" else "нет")
                                }
                            }
                        }
                    }

                    if (model.allowedPassthroughParameters.isNotEmpty()) {
                        item {
                            ModelInfoSection("Passthrough-параметры") {
                                Text(
                                    model.allowedPassthroughParameters.sorted().joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (model.pricingUsd.isNotEmpty() || model.pricingSkusUsd.isNotEmpty()) {
                        item {
                            ModelInfoSection("Стоимость OpenRouter") {
                                model.pricingUsd.toSortedMap().forEach { (key, value) ->
                                    ModelDetailLine(pricingFieldLabel(key), formatRawPricing(key, value))
                                }
                                model.pricingSkusUsd.toSortedMap().forEach { (key, value) ->
                                    ModelDetailLine(key, formatCatalogPrice(value))
                                }
                            }
                        }
                    }

                    if (model.rawOpenRouterMetadata.isNotEmpty()) {
                        item {
                            ModelInfoSection("Все данные OpenRouter") {
                                Text(
                                    "Ниже сохранены исходные метаданные каталога без потери неизвестных Umnik полей.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                model.rawOpenRouterMetadata.forEachIndexed { index, raw ->
                                    if (model.rawOpenRouterMetadata.size > 1) {
                                        Text("Источник ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                                    }
                                    Text(
                                        raw,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { copyToClipboard(context, raw) }) {
                                        Text("Копировать исходные данные")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoSection(title: String, content: @Composable () -> Unit) {
    UmnikPanel {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun ModelDetailLine(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun modalityLabel(value: String): String = when (value.trim().lowercase()) {
    "text" -> "текст"
    "image" -> "изображение"
    "video" -> "видео"
    "audio" -> "аудио"
    "speech" -> "речь"
    "transcription" -> "распознавание"
    "file" -> "файлы"
    "pdf" -> "PDF"
    "embeddings", "embedding" -> "эмбеддинги"
    "rerank", "ranking" -> "ранжирование"
    else -> value
}

private fun modalitySortKey(value: String): String = when (value.lowercase()) {
    "text" -> "00"
    "image" -> "01"
    "video" -> "02"
    "audio" -> "03"
    "file", "pdf" -> "04"
    "speech" -> "05"
    "transcription" -> "06"
    "embeddings", "embedding" -> "07"
    "rerank", "ranking" -> "08"
    else -> "99_$value"
}

private fun parameterSortKey(value: String): String = when (value.lowercase()) {
    "reasoning", "reasoning_effort" -> "00_$value"
    "tools", "tool_choice" -> "01_$value"
    "response_format", "structured_outputs" -> "02_$value"
    "web_search" -> "03_$value"
    "temperature", "top_p", "top_k", "min_p" -> "04_$value"
    else -> "99_$value"
}

private fun parameterLabel(value: String): String = when (value.lowercase()) {
    "reasoning" -> "Reasoning"
    "reasoning_effort" -> "Уровень reasoning"
    "tools" -> "Tools"
    "tool_choice" -> "Выбор tools"
    "response_format" -> "Формат ответа"
    "structured_outputs" -> "Структурированный ответ"
    "web_search" -> "Веб-поиск"
    "temperature" -> "Temperature"
    "top_p" -> "Top P"
    "top_k" -> "Top K"
    "min_p" -> "Min P"
    "max_tokens" -> "Max tokens"
    "seed" -> "Seed"
    else -> value
}

private fun simpleModelKindLabel(value: SimpleModelKind): String = when (value) {
    SimpleModelKind.ALL -> "Все"
    SimpleModelKind.TEXT -> "Текст"
    SimpleModelKind.IMAGE -> "Изображения"
    SimpleModelKind.VIDEO -> "Видео"
    SimpleModelKind.BATCH -> "Batch"
    SimpleModelKind.SPEECH -> "Озвучка"
    SimpleModelKind.TRANSCRIPTION -> "Распознавание речи"
    SimpleModelKind.EMBEDDINGS -> "Поиск по документам"
    SimpleModelKind.RERANK -> "Rerank"
    SimpleModelKind.AUDIO_INPUT -> "Аудио на вход"
    SimpleModelKind.MULTIMODAL -> "Мультимодальный чат"
    SimpleModelKind.REASONING -> "Reasoning"
    SimpleModelKind.TOOLS -> "Tools"
}

private fun simplePriceFilterLabel(value: SimplePriceFilter): String = when (value) {
    SimplePriceFilter.ALL -> "Все"
    SimplePriceFilter.FREE -> "Бесплатно"
    SimplePriceFilter.UP_TO_0_02 -> "до \$0,02"
    SimplePriceFilter.UP_TO_0_05 -> "до \$0,05"
    SimplePriceFilter.UP_TO_0_1 -> "до \$0,1"
    SimplePriceFilter.UP_TO_1 -> "до \$1"
    SimplePriceFilter.UP_TO_5 -> "до \$5"
    SimplePriceFilter.OVER_5 -> "более \$5"
}

private fun modelMatchesSimpleKind(model: ModelInfo, kind: SimpleModelKind): Boolean = when (kind) {
    SimpleModelKind.ALL -> true
    SimpleModelKind.TEXT -> ModelCategory.TEXT in model.categories && !model.isBatch
    SimpleModelKind.IMAGE -> ModelCategory.IMAGE in model.categories
    SimpleModelKind.VIDEO -> ModelCategory.VIDEO in model.categories
    SimpleModelKind.BATCH -> model.isBatch
    SimpleModelKind.SPEECH -> ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories
    SimpleModelKind.TRANSCRIPTION -> ModelCategory.TRANSCRIPTION in model.categories
    SimpleModelKind.EMBEDDINGS -> ModelCategory.EMBEDDINGS in model.categories
    SimpleModelKind.RERANK -> ModelCategory.RERANK in model.categories
    SimpleModelKind.AUDIO_INPUT -> model.accepts("audio")
    SimpleModelKind.MULTIMODAL -> model.isMultimodalChat
    SimpleModelKind.REASONING -> model.supportsReasoning
    SimpleModelKind.TOOLS -> model.supportsTools
}

private fun modelMatchesSimplePrice(
    model: ModelInfo,
    kind: SimpleModelKind,
    filter: SimplePriceFilter
): Boolean {
    if (filter == SimplePriceFilter.ALL) return true
    if (filter == SimplePriceFilter.FREE) return isSimpleCatalogFree(model, kind)

    val value = simpleCatalogPrice(model, kind) ?: return false
    return when (filter) {
        SimplePriceFilter.ALL, SimplePriceFilter.FREE -> true
        SimplePriceFilter.UP_TO_0_02 -> value <= 0.02
        SimplePriceFilter.UP_TO_0_05 -> value <= 0.05
        SimplePriceFilter.UP_TO_0_1 -> value <= 0.10
        SimplePriceFilter.UP_TO_1 -> value <= 1.0
        SimplePriceFilter.UP_TO_5 -> value <= 5.0
        SimplePriceFilter.OVER_5 -> value > 5.0
    }
}

private fun isSimpleCatalogFree(model: ModelInfo, kind: SimpleModelKind): Boolean {
    if (ModelVariant.FREE in model.variants) return true
    val category = simplePriceCategory(kind, model)
    if (category == ModelCategory.TEXT || category == ModelCategory.IMAGE) {
        return model.isFreeFor(category)
    }
    val prices = simpleRawUnitPrices(model)
    return prices.isNotEmpty() && prices.all { it <= 0.0 }
}

private fun simpleCatalogPrice(model: ModelInfo, kind: SimpleModelKind): Double? {
    return when (simplePriceCategory(kind, model)) {
        ModelCategory.TEXT -> model.maxTextPriceUsdPerMillion
        ModelCategory.IMAGE -> model.estimatedImageOutputUsd1K ?: model.imagePriceUsd
        ModelCategory.EMBEDDINGS, ModelCategory.RERANK -> model.maxTextPriceUsdPerMillion
        else -> simpleRawUnitPrices(model).filter { it > 0.0 }.minOrNull()
            ?: simpleRawUnitPrices(model).firstOrNull()
            ?: model.maxTextPriceUsdPerMillion
            ?: model.estimatedImageOutputUsd1K
    }
}

private fun simplePriceCategory(kind: SimpleModelKind, model: ModelInfo): ModelCategory? = when (kind) {
    SimpleModelKind.TEXT, SimpleModelKind.BATCH -> ModelCategory.TEXT
    SimpleModelKind.IMAGE -> ModelCategory.IMAGE
    SimpleModelKind.VIDEO -> ModelCategory.VIDEO
    SimpleModelKind.SPEECH -> ModelCategory.SPEECH
    SimpleModelKind.TRANSCRIPTION -> ModelCategory.TRANSCRIPTION
    SimpleModelKind.EMBEDDINGS -> ModelCategory.EMBEDDINGS
    SimpleModelKind.RERANK -> ModelCategory.RERANK
    SimpleModelKind.ALL, SimpleModelKind.AUDIO_INPUT, SimpleModelKind.MULTIMODAL,
    SimpleModelKind.REASONING, SimpleModelKind.TOOLS -> when {
        model.outputModalities == setOf("image") -> ModelCategory.IMAGE
        ModelCategory.TEXT in model.categories -> ModelCategory.TEXT
        ModelCategory.VIDEO in model.categories -> ModelCategory.VIDEO
        ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories -> ModelCategory.SPEECH
        ModelCategory.TRANSCRIPTION in model.categories -> ModelCategory.TRANSCRIPTION
        ModelCategory.EMBEDDINGS in model.categories -> ModelCategory.EMBEDDINGS
        ModelCategory.RERANK in model.categories -> ModelCategory.RERANK
        else -> null
    }
}

private fun simpleRawUnitPrices(model: ModelInfo): List<Double> {
    val tokenKeys = setOf("prompt", "completion", "internal_reasoning", "image_token", "image_output")
    return model.pricingUsd
        .filterKeys { it.lowercase() !in tokenKeys }
        .values
        .toList()
}


private fun compactTokenCount(value: Int): String = when {
    value >= 1_000_000 -> {
        val millions = value / 1_000_000.0
        if (millions % 1.0 == 0.0) "${millions.toInt()}M" else "${"%.1f".format(Locale.US, millions)}M"
    }
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

private fun formatModelDate(epochSeconds: Long): String = runCatching {
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}.getOrDefault("")

private fun parameterCapabilityText(name: String, descriptor: ModelParameterCapability?): String {
    val details = buildList {
        descriptor?.type?.takeIf { it.isNotBlank() }?.let(::add)
        descriptor?.values?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
        if (descriptor?.min != null || descriptor?.max != null) {
            add("${descriptor.min?.let(::formatNumberCompact) ?: "…"} … ${descriptor.max?.let(::formatNumberCompact) ?: "…"}")
        }
    }
    return if (details.isEmpty()) "${parameterLabel(name)} ($name)"
    else "${parameterLabel(name)} ($name): ${details.joinToString(" · ")}"
}

private fun formatNumberCompact(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')

private fun capabilityFieldLabel(value: String): String = when (value) {
    "resolutions" -> "Разрешения"
    "aspect_ratios" -> "Соотношения сторон"
    "sizes" -> "Размеры"
    "durations" -> "Длительности"
    "frame_images" -> "Опорные кадры"
    "generate_audio" -> "Генерация аудио"
    "seed" -> "Seed"
    else -> value
}

private fun pricingFieldLabel(value: String): String = when (value) {
    "prompt" -> "Входные токены"
    "completion" -> "Выходные токены"
    "request" -> "Запрос"
    "image" -> "Изображение"
    "image_token" -> "Image token"
    "image_output" -> "Image output"
    "web_search" -> "Веб-поиск"
    "internal_reasoning" -> "Reasoning tokens"
    "audio" -> "Аудио"
    else -> value
}

private fun formatRawPricing(key: String, value: Double): String = when (key) {
    "prompt", "completion" -> "${formatCatalogPrice(value * 1_000_000.0)} / 1M токенов"
    else -> formatCatalogPrice(value)
}

private fun catalogPriceText(model: ModelInfo): String? {
    if (ModelVariant.FREE in model.variants) return "Цена: бесплатно (:free)"

    val parts = mutableListOf<String>()
    if (model.promptPriceUsdPerMillion != null || model.completionPriceUsdPerMillion != null) {
        parts += "Текст / 1M: вход ${formatCatalogPrice(model.promptPriceUsdPerMillion)} · выход ${formatCatalogPrice(model.completionPriceUsdPerMillion)}"
    }
    if (ModelCategory.IMAGE in model.categories) {
        model.estimatedImageOutputUsd1K?.let { estimate ->
            if (estimate > 0.0) {
                parts += "изображение ≈ ${formatCatalogPrice(estimate)} за 1K"
            }
        }
    }
    if (parts.isEmpty() && ModelCategory.IMAGE in model.categories) {
        parts += "изображение: цена зависит от image-тарифа OpenRouter"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  •  ")
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
    var advanced by remember { mutableStateOf(false) }
    var order by remember(value.providerOrder) { mutableStateOf(value.providerOrder.joinToString(", ")) }
    var only by remember(value.providerOnly) { mutableStateOf(value.providerOnly.joinToString(", ")) }
    var ignore by remember(value.providerIgnore) { mutableStateOf(value.providerIgnore.joinToString(", ")) }
    var quantizations by remember(value.quantizations) { mutableStateOf(value.quantizations.joinToString(", ")) }
    var fallbacks by remember(value.fallbackModels) { mutableStateOf(value.fallbackModels.joinToString(", ")) }
    var promptPrice by remember(value.maxPromptUsdPerMillion) { mutableStateOf(value.maxPromptUsdPerMillion?.toString().orEmpty()) }
    var completionPrice by remember(value.maxCompletionUsdPerMillion) { mutableStateOf(value.maxCompletionUsdPerMillion?.toString().orEmpty()) }
    var imagePrice by remember(value.maxImageUsd) { mutableStateOf(value.maxImageUsd?.toString().orEmpty()) }
    var requestPrice by remember(value.maxRequestUsd) { mutableStateOf(value.maxRequestUsd?.toString().orEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Маршрутизация OpenRouter",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                UmnikInfoHint(
                    title = "Что это",
                    text = "Одна и та же модель OpenRouter может работать через нескольких провайдеров. Обычно достаточно режима «Авто»: OpenRouter сам выберет подходящий маршрут. Эти настройки не выбирают платную модель за вас."
                )
            }
        }
        item {
            Text("Стратегия", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ProviderRouteStrategy.entries) { strategy ->
                    FilterChip(
                        selected = value.strategy == strategy,
                        onClick = { save(value.copy(strategy = strategy)) },
                        label = { Text(routeLabel(strategy)) }
                    )
                }
            }
            Text(
                when (value.strategy) {
                    ProviderRouteStrategy.AUTO -> "Рекомендуется большинству пользователей."
                    ProviderRouteStrategy.CHEAPEST -> "OpenRouter предпочитает более дешёвый маршрут выбранной модели."
                    ProviderRouteStrategy.HIGHEST_THROUGHPUT -> "OpenRouter предпочитает более высокую пропускную способность."
                    ProviderRouteStrategy.LOWEST_LATENCY -> "OpenRouter предпочитает меньшую задержку."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Скрыть расширенную маршрутизацию" else "Расширенная маршрутизация")
            }
        }
        if (advanced) {
            item {
                ToggleRow(
                    "Fallback провайдера",
                    value.allowProviderFallbacks,
                    "Если один провайдер выбранной модели недоступен, OpenRouter может попробовать другой. Модель при этом остаётся той же."
                ) { save(value.copy(allowProviderFallbacks = it)) }
            }
            item {
                ToggleRow(
                    "Требовать поддержку параметров",
                    value.requireParameters,
                    "Отсекает провайдеров, которые не поддерживают параметры запроса, например tools или reasoning."
                ) { save(value.copy(requireParameters = it)) }
            }
            item {
                ToggleRow(
                    "Zero Data Retention",
                    value.zeroDataRetention,
                    "Ограничивает маршруты вариантами, совместимыми с Zero Data Retention. Может уменьшить число доступных провайдеров."
                ) { save(value.copy(zeroDataRetention = it)) }
            }
            item {
                ToggleRow(
                    "Запретить сбор данных",
                    value.denyDataCollection,
                    "Передаёт OpenRouter ограничение data_collection=deny."
                ) { save(value.copy(denyDataCollection = it)) }
            }
            item {
                CsvField("Приоритет провайдеров", order, "ID провайдеров через запятую в желаемом порядке.") { order = it }
            }
            item {
                CsvField("Разрешить только", only, "Белый список провайдеров. Если не знаете ID провайдера, оставьте пустым.") { only = it }
            }
            item {
                CsvField("Исключить", ignore, "Чёрный список провайдеров.") { ignore = it }
            }
            item {
                CsvField("Квантизации", quantizations, "Продвинутое ограничение backend-вариантов модели. Обычно оставляется пустым.") { quantizations = it }
            }
            item {
                CsvField(
                    "Fallback-модели",
                    fallbacks,
                    "Запасные модели через запятую. В отличие от fallback провайдера это уже разрешает сменить саму модель."
                ) { fallbacks = it }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Максимальная цена маршрута", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    UmnikInfoHint(
                        title = "Ограничение цены",
                        text = "Не выбирает модель автоматически, а только отсекает слишком дорогие маршруты уже выбранной модели. Слишком низкое значение может привести к ошибке запроса."
                    )
                }
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
                        save(
                            value.copy(
                                providerOrder = csv(order),
                                providerOnly = csv(only),
                                providerIgnore = csv(ignore),
                                quantizations = csv(quantizations),
                                fallbackModels = csv(fallbacks),
                                maxPromptUsdPerMillion = promptPrice.toDoubleOrNull(),
                                maxCompletionUsdPerMillion = completionPrice.toDoubleOrNull(),
                                maxImageUsd = imagePrice.toDoubleOrNull(),
                                maxRequestUsd = requestPrice.toDoubleOrNull()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить расширенные параметры") }
            }
        }
    }
}

@Composable
private fun ToolsPage(
    tools: ServerToolSettings,
    controller: OpenRouterHubController
) {
    var advanced by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Настройки новых чатов",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                UmnikInfoHint(
                    title = "Зачем это",
                    text = "Здесь задаются значения по умолчанию для новых обычных чатов. Уже созданные чаты хранят свои настройки отдельно. Модель пользователь всегда выбирает сам."
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Режим веб-поиска по умолчанию", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                UmnikInfoHint(
                    title = "Поиск для новых чатов",
                    text = "Сам поиск включается или выключается в конкретном чате. Здесь задаётся только режим, который получит новый чат."
                )
            }
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
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Скрыть дополнительные инструменты" else "Дополнительные инструменты")
            }
        }
        if (advanced) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сервис интернет-поиска", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    UmnikInfoHint(
                        title = "Сервис поиска",
                        text = "Auto подходит большинству пользователей. Ручной выбор нужен только если вы понимаете, какой поисковый backend хотите использовать."
                    )
                }
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
            item {
                ToggleRow(
                    "Открывать найденные веб-страницы",
                    tools.webFetch,
                    "Разрешает модели дополнительно открыть найденную страницу и прочитать её содержимое."
                ) { controller.updateTools(tools.copy(webFetch = it)) }
            }
            item {
                ToggleRow(
                    "Текущие дата и время",
                    tools.datetime,
                    "Разрешает модели запросить актуальные дату и время как серверный инструмент."
                ) { controller.updateTools(tools.copy(datetime = it)) }
            }
            item {
                ToggleRow(
                    "Создание изображений как инструмент",
                    tools.imageGeneration,
                    "Позволяет совместимой модели вызвать генерацию изображения прямо в ходе разговора."
                ) { controller.updateTools(tools.copy(imageGeneration = it)) }
            }
            item {
                ToggleRow(
                    "Fusion",
                    tools.fusion,
                    "Продвинутый режим OpenRouter для объединения работы нескольких инструментов. Обычно не требуется."
                ) { controller.updateTools(tools.copy(fusion = it)) }
            }
            item {
                ToggleRow(
                    "Shell",
                    tools.shell,
                    "Продвинутая возможность для задач с командами и рабочими файлами. Включайте только когда действительно нужна."
                ) { controller.updateTools(tools.copy(shell = it)) }
            }
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
private fun ToggleRow(
    title: String,
    checked: Boolean,
    info: String? = null,
    onChecked: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        if (!info.isNullOrBlank()) {
            UmnikInfoHint(title = title, text = info)
            Spacer(Modifier.width(6.dp))
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CsvField(label: String, value: String, info: String? = null, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        trailingIcon = if (info.isNullOrBlank()) null else {
            { UmnikInfoHint(title = label, text = info) }
        },
        singleLine = true
    )
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
