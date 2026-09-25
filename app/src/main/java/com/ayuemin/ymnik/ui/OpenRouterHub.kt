package com.ayuemin.ymnik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.LocalShellActivity
import com.ayuemin.ymnik.ShellActivity
import com.ayuemin.ymnik.model.BatchJobStatus
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelParameterCapability
import com.ayuemin.ymnik.model.ModelUniversality
import com.ayuemin.ymnik.model.ModelVariant
import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
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

private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, REPLY_SPEECH, SHELL, LOCAL_SHELL }
private enum class MediaSection { ALL, VIDEO, TRANSCRIPTION, SPEECH }
internal enum class SimpleModelKind {
    ALL,
    TEXT,
    IMAGE,
    VIDEO,
    BATCH,
    SPEECH,
    TRANSCRIPTION,
    EMBEDDINGS,
    AUDIO_INPUT,
    MULTIMODAL,
    REASONING,
    TOOLS
}

internal enum class CatalogSort {
    ALPHABETICAL,
    CHEAPEST,
    EXPENSIVE,
    CAPABILITIES
}

@Composable
fun UmnikV16Root(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val controller = remember(viewModel) { OpenRouterHubController(context.applicationContext, viewModel) }
    var open by remember { mutableStateOf(false) }
    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }
    var requestedMediaSection by remember { mutableStateOf(MediaSection.ALL) }
    var requestedReturnLabel by remember { mutableStateOf<String?>(null) }
    var localShellOpen by remember { mutableStateOf(false) }
    val asyncSequence by AsyncJobEvents.sequence.collectAsState()
    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()
    val hubReturnLabel by AsyncJobEvents.hubReturnLabel.collectAsState()
    val speechRequest by AsyncJobEvents.speechRequest.collectAsState()
    val localShellActivity by AsyncJobEvents.localShellActivity.collectAsState()
    val hubState by controller.state.collectAsState()
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
            "tools" -> {
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
            "local-shell" -> {
                localShellOpen = true
                AsyncJobEvents.consumeHubRequest()
            }
        }
    }

    LaunchedEffect(localShellActivity?.chatId) {
        if (localShellActivity != null) localShellOpen = false
    }

    UmnikTheme(appState.themeChoice, appState.customThemeColor) {
        Box(Modifier.fillMaxSize()) {
            YmnikApp(viewModel)
            if (localShellActivity != null && !localShellOpen) {
                LocalShellProcessPill(
                    activity = localShellActivity!!,
                    currentChatId = appState.currentChatId,
                    onClick = { localShellOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            if (localShellOpen) {
                LocalShellFloatingCard(
                    state = hubState,
                    activity = localShellActivity,
                    controller = controller,
                    currentChatId = appState.currentChatId,
                    onDismiss = { localShellOpen = false },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
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
    var returnPage by remember(initialPage) { mutableStateOf<HubPage?>(null) }
    val settingsMode = initialPage == HubPage.MODELS || initialPage == HubPage.ROUTING || initialPage == HubPage.TOOLS
    val showBack = settingsMode || returnPage != null || !returnLabel.isNullOrBlank()
    val handleBack: () -> Unit = {
        val target = returnPage
        if (target != null) {
            page = target
            returnPage = null
        } else {
            onDismiss()
        }
    }
    val openCatalog: () -> Unit = {
        if (page != HubPage.MODELS) returnPage = page
        page = HubPage.MODELS
    }

    LaunchedEffect(page) {
        controller.clearStatus()
    }

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
                            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showBack) {
                                UmnikCircleAction(
                                    icon = Icons.Outlined.ArrowBack,
                                    contentDescription = "Назад",
                                    onClick = handleBack
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (page) {
                                        HubPage.MODELS -> "Каталог и модели OpenRouter"
                                        HubPage.ROUTING -> "Маршрутизация OpenRouter"
                                        HubPage.TOOLS -> "Инструменты OpenRouter"
                                        HubPage.JOBS -> "Пакетные задачи"
                                        HubPage.MEDIA -> when (initialMediaSection) {
                                            MediaSection.VIDEO -> "Создание видео"
                                            MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                            MediaSection.SPEECH -> "Озвучивание текста и документов"
                                            MediaSection.ALL -> "Медиа"
                                        }
                                        HubPage.REPLY_SPEECH -> "Озвучивание ответов"
                                        HubPage.SHELL -> "OpenRouter Shell"
                                        HubPage.LOCAL_SHELL -> "Локальный Shell"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                val subtitle = when (page) {
                                    HubPage.MODELS -> "Поиск, фильтры, цены и назначение моделей"
                                    HubPage.ROUTING -> "Правила выбора провайдера"
                                    HubPage.TOOLS -> "Дополнительные возможности OpenRouter"
                                    HubPage.REPLY_SPEECH -> "Отдельная модель и голос для кнопки OR"
                                    HubPage.SHELL -> "Работа с файлами, ZIP-архивами и кодом"
                                    HubPage.LOCAL_SHELL -> "Файлы и код обрабатываются на этом устройстве"
                                    else -> null
                                }
                                if (subtitle != null) {
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            when (page) {
                                HubPage.MODELS -> UmnikInfoHint(
                                    title = "О каталоге",
                                    text = "Не нашли нужной информации? Посмотрите модель на сайте OpenRouter и вставьте её ID в Umnik вручную."
                                )
                                HubPage.JOBS -> UmnikInfoHint(
                                    title = "Пакетные задачи",
                                    text = "Добавьте несколько независимых заданий, при необходимости прикрепите файлы к каждому и запустите пакет. Batch удобен, когда задания не зависят друг от друга; результаты вернутся в исходный чат."
                                )
                                HubPage.MEDIA -> UmnikInfoHint(
                                    title = when (initialMediaSection) {
                                        MediaSection.VIDEO -> "Создание видео"
                                        MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                        MediaSection.SPEECH -> "Озвучивание текста и документов"
                                        MediaSection.ALL -> "Медиа"
                                    },
                                    text = when (initialMediaSection) {
                                        MediaSection.VIDEO -> "Опишите видео, при необходимости добавьте референсы и нажмите «Создать». Видео продолжит создаваться в фоне, а готовый файл появится в исходном чате."
                                        MediaSection.TRANSCRIPTION -> "Выберите аудиофайл. После распознавания текст появится здесь и будет добавлен в текущий чат."
                                        MediaSection.SPEECH -> "Введите текст или загрузите текстовый файл и нажмите «Создать аудио». Модель, голос и формат доступны в сворачиваемом блоке ниже."
                                        MediaSection.ALL -> "Здесь собраны видео, распознавание речи и озвучивание. Технические настройки моделей находятся во вторичном уровне."
                                    }
                                )
                                HubPage.SHELL -> UmnikInfoHint(
                                    title = "Что умеет Shell",
                                    text = "Shell даёт модели рабочую среду для выполнения кода и обработки файлов. Целую папку или проект удобно передать ZIP-архивом: Shell может распаковать его, сохранить структуру папок, проверить содержимое, исправить нужные файлы и вернуть новый ZIP со всем обновлённым проектом. Неизменённые файлы при этом тоже должны остаться на месте. Например, можно упаковать Android-проект в ZIP, попросить найти и исправить проблемы и получить обратно готовую папку проекта в новом архиве. Для архивов рекомендуем ZIP. RAR и 7z зависят от доступных утилит окружения и не считаются гарантированными. Добавьте файл или архив, простыми словами опишите, что нужно сделать, и запустите задачу. Ответ и готовые файлы появятся в текущем чате."
                                )
                                HubPage.LOCAL_SHELL -> UmnikInfoHint(
                                    title = "О локальном Shell",
                                    text = "Исходные вложения не загружаются в OpenRouter Shell. Umnik копирует их в отдельную рабочую папку на телефоне и выполняет локальные операции с файлами, архивами, Git и Python. Модель получает только задание и результаты тех локальных операций, которые сама запросила, включая прочитанные фрагменты файлов. Сетевой шлюз в тестовой версии разрешает только получение данных и публичный Git clone; загрузка локальных файлов и Git push отключены."
                                )
                                else -> Unit
                            }
                            if (page == HubPage.MODELS || page == HubPage.JOBS || page == HubPage.MEDIA || page == HubPage.SHELL || page == HubPage.LOCAL_SHELL) {
                                Spacer(Modifier.width(2.dp))
                            }
                            if (!showBack) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                                }
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
                    state.status?.let { status ->
                        Text(
                            status,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val chatReturnPage = page == HubPage.JOBS ||
                        page == HubPage.MEDIA ||
                        page == HubPage.REPLY_SPEECH ||
                        page == HubPage.SHELL ||
                        page == HubPage.LOCAL_SHELL
                    if (chatReturnPage && !returnLabel.isNullOrBlank()) {
                        Surface(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    returnLabel,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    val activeBatch = if (page == HubPage.JOBS) {
                        state.batches.filterNot { it.status.terminal }.maxByOrNull { it.updatedAt }
                    } else {
                        null
                    }
                    if (activeBatch != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (activeBatch.completedItems > 0) {
                                            "${batchLabel(activeBatch.status)} · ${activeBatch.completedItems}/${activeBatch.totalItems}"
                                        } else {
                                            "${batchLabel(activeBatch.status)} · ${activeBatch.totalItems} заданий"
                                        },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Результат появится в исходном чате",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    when (page) {
                        HubPage.MODELS -> ModelsPage(state, controller, appState)
                        HubPage.ROUTING -> RoutingPage(state.routing, controller::updateRouting)
                        HubPage.TOOLS -> ToolsPage(state.tools, controller, viewModel)
                        HubPage.JOBS -> JobsPage(
                            state = state,
                            controller = controller,
                            onOpenCatalog = openCatalog
                        )
                        HubPage.MEDIA -> MediaPage(state, controller, initialMediaSection, openCatalog)
                        HubPage.REPLY_SPEECH -> ReplySpeechPage(state, appState, controller, openCatalog)
                        HubPage.SHELL -> ShellPage(
                            state = state,
                            controller = controller,
                            currentChatId = appState.currentChatId,
                            onReturnToChat = onDismiss
                        )
                        HubPage.LOCAL_SHELL -> LocalShellPage(
                            state = state,
                            controller = controller,
                            currentChatId = appState.currentChatId,
                            onReturnToChat = onDismiss
                        )
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

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")

private fun normalizeModelSearch(value: String): String = modelSearchSeparators
    .replace(value.lowercase(Locale.ROOT), " ")
    .trim()

internal fun modelSearchRank(model: ModelInfo, rawQuery: String): Int {
    val query = normalizeModelSearch(rawQuery)
    if (query.isBlank()) return 0
    val tokens = query.split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return 0

    fun fieldRank(values: List<String?>, exact: Int, starts: Int, contains: Int, tokensRank: Int): Int {
        val normalized = values.mapNotNull { it?.takeIf(String::isNotBlank) }.map(::normalizeModelSearch)
        if (normalized.any { it == query }) return exact
        if (normalized.any { it.startsWith(query) }) return starts
        if (normalized.any { query in it }) return contains
        if (normalized.any { value -> tokens.all { token -> token in value } }) return tokensRank
        return Int.MAX_VALUE
    }

    val primary = fieldRank(
        listOf(model.name, model.id),
        exact = 0,
        starts = 1,
        contains = 2,
        tokensRank = 3
    )
    if (primary != Int.MAX_VALUE) return primary

    val identity = fieldRank(
        listOf(model.providerId, model.canonicalSlug, model.huggingFaceId),
        exact = 1,
        starts = 2,
        contains = 3,
        tokensRank = 4
    )
    if (identity != Int.MAX_VALUE) return identity

    return fieldRank(
        listOf(model.description),
        exact = 4,
        starts = 5,
        contains = 6,
        tokensRank = 7
    )
}

internal fun modelMatchesSearch(model: ModelInfo, rawQuery: String): Boolean =
    rawQuery.isBlank() || modelSearchRank(model, rawQuery) != Int.MAX_VALUE

@Composable
private fun ModelsPage(state: OpenRouterHubState, controller: OpenRouterHubController, appState: UiState) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SimpleModelKind.ALL) }
    var sort by remember { mutableStateOf(CatalogSort.ALPHABETICAL) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var moreKindsOpen by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(
        listState.isScrollInProgress,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    ) {
        if (
            listState.isScrollInProgress &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24)
        ) {
            filtersExpanded = false
        }
    }

    LaunchedEffect(query, kind, sort) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

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
            SimpleModelKind.AUDIO_INPUT,
            SimpleModelKind.MULTIMODAL,
            SimpleModelKind.REASONING,
            SimpleModelKind.TOOLS
        )
    }

    val effectiveSort = if (!catalogPriceSortSupported(kind) && sort in setOf(CatalogSort.CHEAPEST, CatalogSort.EXPENSIVE)) {
        CatalogSort.ALPHABETICAL
    } else {
        sort
    }

    val filtered = remember(state.catalog, query, kind, effectiveSort) {
        val needle = query.trim()
        val nameComparator = compareBy<ModelInfo> { (it.name ?: it.id).lowercase(Locale.ROOT) }
            .thenBy { it.id }
        val sortComparator = when (effectiveSort) {
            CatalogSort.ALPHABETICAL -> nameComparator
            CatalogSort.CAPABILITIES ->
                compareByDescending<ModelInfo> { ModelUniversality.score(it).total }
                    .then(nameComparator)
            CatalogSort.CHEAPEST ->
                compareBy<ModelInfo> { modelCatalogComparablePrice(it, kind) == null }
                    .thenBy { modelCatalogComparablePrice(it, kind) ?: Double.MAX_VALUE }
                    .then(nameComparator)
            CatalogSort.EXPENSIVE ->
                compareBy<ModelInfo> { modelCatalogComparablePrice(it, kind) == null }
                    .thenByDescending { modelCatalogComparablePrice(it, kind) ?: Double.NEGATIVE_INFINITY }
                    .then(nameComparator)
        }
        val comparator = if (needle.isBlank()) {
            sortComparator
        } else {
            compareBy<ModelInfo> { modelSearchRank(it, needle) }.then(sortComparator)
        }
        state.catalog.asSequence()
            .filter { model -> modelMatchesSearch(model, needle) }
            .filter { model -> modelMatchesSimpleKind(model, kind) }
            .sortedWith(comparator)
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
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                placeholder = { Text("Название модели или ID") },
                shape = UmnikFieldShape
            )
            IconButton(onClick = { controller.refreshCatalog(forceMessage = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить каталог")
            }
            AnimatedVisibility(
                visible = !filtersExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FilledTonalButton(onClick = { filtersExpanded = true }) {
                    Text("Фильтры")
                }
            }
        }

        AnimatedVisibility(
            visible = filtersExpanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            Column {
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
                    "Сортировка",
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        FilterChip(
                            selected = sort != CatalogSort.ALPHABETICAL,
                            onClick = { sortMenuOpen = true },
                            label = { Text("Сортировка: ${catalogSortLabel(sort)}") }
                        )
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            CatalogSort.entries.forEach { item ->
                                val priceSort = item == CatalogSort.CHEAPEST || item == CatalogSort.EXPENSIVE
                                DropdownMenuItem(
                                    text = { Text(catalogSortLabel(item)) },
                                    enabled = !priceSort || catalogPriceSortSupported(kind),
                                    onClick = {
                                        sort = item
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (kind != SimpleModelKind.ALL || sort != CatalogSort.ALPHABETICAL) {
                        TextButton(
                            onClick = {
                                kind = SimpleModelKind.ALL
                                sort = CatalogSort.ALPHABETICAL
                            }
                        ) {
                            Text("Сбросить")
                        }
                    }
                }
                when (kind) {
                    SimpleModelKind.ALL -> Text(
                        "Для сортировки по цене сначала выберите тип модели.",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SimpleModelKind.SPEECH -> Text(
                        "У моделей озвучивания разные единицы тарификации, поэтому ценовая сортировка отключена.",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }

                Text(
                    buildString {
                        append("Показано ${filtered.size} из ${state.catalog.size}")
                        if (query.isNotBlank()) append(" · точные совпадения выше")
                        append(" · ")
                        append(
                            when (effectiveSort) {
                                CatalogSort.ALPHABETICAL -> "по алфавиту"
                                CatalogSort.CHEAPEST -> "сначала бесплатные и дешёвые"
                                CatalogSort.EXPENSIVE -> "сначала дорогие"
                                CatalogSort.CAPABILITIES -> "больше возможностей выше"
                            }
                        )
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(
            visible = !filtersExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                "Показано ${filtered.size} из ${state.catalog.size} · фильтры свёрнуты",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        when {
                            state.loading -> "Каталог загружается…"
                            query.isNotBlank() -> "По запросу «${query.trim()}» ничего не найдено"
                            else -> "По выбранным условиям моделей нет"
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(filtered, key = { it.id }) { model ->
                ModelCatalogCard(model, controller, appState, state, kind)
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
private fun ModelCatalogCard(
    model: ModelInfo,
    controller: OpenRouterHubController,
    appState: UiState,
    hubState: OpenRouterHubState,
    selectedKind: SimpleModelKind
) {
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
                                text = { Text("Использовать как системную модель") },
                                onClick = { menuOpen = false; controller.useAsSystemModel(model) }
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
                        if (model.isBatch && ModelCategory.TEXT in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать для пакетных задач") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TEXT) }
                            )
                        }
                        if (ModelCategory.EMBEDDINGS in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать как Embeddings-модель") },
                                onClick = {
                                    menuOpen = false
                                    controller.assignModel(model, ModelCategory.EMBEDDINGS)
                                }
                            )
                        }
                        if (ModelCategory.IMAGE in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать для генерации изображений") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.IMAGE) }
                            )
                        }
                        if (ModelCategory.VIDEO in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать для создания видео") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.VIDEO) }
                            )
                        }
                        if (ModelCategory.TRANSCRIPTION in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать для распознавания речи") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TRANSCRIPTION) }
                            )
                        }
                        if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Использовать для озвучивания текста") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.SPEECH) }
                            )
                            DropdownMenuItem(
                                text = { Text("Использовать для озвучивания ответов") },
                                onClick = { menuOpen = false; controller.assignReplySpeechModel(model) }
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

            catalogPriceText(model, selectedKind)?.let { priceText ->
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
                                if (ModelCategory.IMAGE in model.categories) {
                                    model.estimatedImageOutputUsd1K?.takeIf { it > 0.0 }?.let { estimate ->
                                        ModelDetailLine(
                                            "Генерация изображения 1K, ориентир",
                                            formatCatalogPrice(estimate)
                                        )
                                    }
                                }
                                model.pricingSkusUsd.toSortedMap().forEach { (key, value) ->
                                    ModelDetailLine(pricingFieldLabel(key), formatSpecializedPricing(key, value))
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
    SimpleModelKind.AUDIO_INPUT -> "Аудио на вход"
    SimpleModelKind.MULTIMODAL -> "Мультимодальный чат"
    SimpleModelKind.REASONING -> "Reasoning"
    SimpleModelKind.TOOLS -> "Tools"
}

private fun catalogSortLabel(value: CatalogSort): String = when (value) {
    CatalogSort.ALPHABETICAL -> "По алфавиту"
    CatalogSort.CHEAPEST -> "Сначала дешёвые"
    CatalogSort.EXPENSIVE -> "Сначала дорогие"
    CatalogSort.CAPABILITIES -> "По возможностям"
}

private fun catalogPriceSortSupported(kind: SimpleModelKind): Boolean =
    kind != SimpleModelKind.ALL && kind != SimpleModelKind.SPEECH

internal fun modelMatchesSimpleKind(model: ModelInfo, kind: SimpleModelKind): Boolean = when (kind) {
    SimpleModelKind.ALL -> true
    SimpleModelKind.TEXT ->
        model.outputs("text") &&
            !model.isBatch &&
            !model.outputs("image") &&
            !model.outputs("video") &&
            !model.outputs("speech") &&
            !model.outputs("transcription") &&
            !model.outputs("audio")
    SimpleModelKind.IMAGE -> model.outputs("image")
    SimpleModelKind.VIDEO -> model.outputs("video")
    SimpleModelKind.BATCH -> model.isBatch
    SimpleModelKind.SPEECH -> model.outputs("speech")
    SimpleModelKind.TRANSCRIPTION -> model.outputs("transcription")
    SimpleModelKind.EMBEDDINGS -> model.outputs("embeddings") || model.outputs("embedding")
    SimpleModelKind.AUDIO_INPUT -> model.accepts("audio")
    SimpleModelKind.MULTIMODAL -> model.isMultimodalChat
    SimpleModelKind.REASONING -> model.supportsReasoning
    SimpleModelKind.TOOLS -> model.supportsTools
}

internal fun modelCatalogComparablePrice(model: ModelInfo, kind: SimpleModelKind): Double? =
    catalogPriceQuote(model, kind).sortValue

private data class CatalogPriceQuote(
    val sortValue: Double?,
    val text: String?
)

private fun catalogPriceQuote(model: ModelInfo, requestedKind: SimpleModelKind): CatalogPriceQuote {
    val kind = if (requestedKind == SimpleModelKind.ALL) primaryPriceKind(model) else requestedKind
    if (ModelVariant.FREE in model.variants) {
        return CatalogPriceQuote(0.0, "Цена: бесплатно (:free)")
    }

    return when (kind) {
        SimpleModelKind.TEXT,
        SimpleModelKind.BATCH,
        SimpleModelKind.EMBEDDINGS,
        SimpleModelKind.MULTIMODAL,
        SimpleModelKind.REASONING,
        SimpleModelKind.TOOLS,
        SimpleModelKind.AUDIO_INPUT -> tokenPriceQuote(model)

        SimpleModelKind.IMAGE -> imagePriceQuote(model)
        SimpleModelKind.VIDEO -> videoPriceQuote(model)
        SimpleModelKind.SPEECH -> speechPriceQuote(model)
        SimpleModelKind.TRANSCRIPTION -> transcriptionPriceQuote(model)
        SimpleModelKind.ALL -> CatalogPriceQuote(null, null)
    }
}

private fun primaryPriceKind(model: ModelInfo): SimpleModelKind = when {
    model.outputs("video") -> SimpleModelKind.VIDEO
    model.outputs("image") -> SimpleModelKind.IMAGE
    model.outputs("speech") -> SimpleModelKind.SPEECH
    model.outputs("transcription") -> SimpleModelKind.TRANSCRIPTION
    model.outputs("embeddings") || model.outputs("embedding") -> SimpleModelKind.EMBEDDINGS
    else -> SimpleModelKind.TEXT
}

private fun tokenPriceQuote(model: ModelInfo): CatalogPriceQuote {
    val input = model.promptPriceUsdPerMillion
    val output = model.completionPriceUsdPerMillion
    val known = listOfNotNull(input, output)
    if (known.isEmpty()) return CatalogPriceQuote(null, null)
    val sortValue = known.average()
    val text = if (known.all { it <= 0.0 }) {
        "Цена: бесплатно"
    } else {
        "Текст / 1M: вход ${formatCatalogPrice(input)} · выход ${formatCatalogPrice(output)}"
    }
    return CatalogPriceQuote(sortValue, text)
}

private fun imagePriceQuote(model: ModelInfo): CatalogPriceQuote {
    // OpenRouter's generic `pricing.image` is the INPUT-image charge for models
    // that accept references. It is not the generation price. For output pricing
    // the general catalog exposes image_output/image_token; 4096 image tokens is
    // the 1K baseline used by OpenRouter's image catalog.
    val estimated1K = model.estimatedImageOutputUsd1K?.takeIf { it > 0.0 }
    if (estimated1K != null) {
        return CatalogPriceQuote(
            estimated1K,
            "Изображение: от ${formatCatalogPrice(estimated1K)} / изображение · 1K"
        )
    }

    val endpointOutput = model.pricingSkusUsd
        .filter { (key, value) ->
            value > 0.0 && key.lowercase().let { k ->
                k.contains("output_image") || k.contains("per-image") || k.contains("megapixel")
            }
        }
        .values
        .minOrNull()
    if (endpointOutput != null) {
        return CatalogPriceQuote(
            endpointOutput,
            "Изображение: от ${formatCatalogPrice(endpointOutput)}"
        )
    }

    return CatalogPriceQuote(null, "Изображение: цена генерации не указана")
}

private fun videoPriceQuote(model: ModelInfo): CatalogPriceQuote {
    val rates = videoPerSecondPrices(model)
    val positive = rates.filter { it > 0.0 }
    if (positive.isNotEmpty()) {
        val value = positive.minOrNull()!!
        return CatalogPriceQuote(value, "Видео: от ${formatCatalogPrice(value)} / сек")
    }
    return if (rates.isNotEmpty() && rates.all { it <= 0.0 }) {
        CatalogPriceQuote(0.0, "Видео: бесплатно")
    } else {
        CatalogPriceQuote(null, "Видео: цена не указана")
    }
}

private fun videoPerSecondPrices(model: ModelInfo): List<Double> =
    (model.pricingSkusUsd + model.pricingUsd)
        .filter { (key, _) -> isVideoSecondPriceKey(key) }
        .values
        .toList()

private fun isVideoSecondPriceKey(key: String): Boolean {
    val k = key.lowercase()
    return k.contains("duration_seconds") ||
        k.contains("per-video-second") ||
        (k.contains("video") && k.contains("second"))
}

private fun speechPriceQuote(model: ModelInfo): CatalogPriceQuote {
    val specialized = (model.pricingSkusUsd + model.pricingUsd)
        .filter { (key, _) ->
            val k = key.lowercase()
            k.contains("character") || k.contains("byte")
        }
    val specializedPositive = specialized.filterValues { it > 0.0 }
    if (specializedPositive.isNotEmpty()) {
        val entry = specializedPositive.minBy { it.value }
        val perMillion = entry.value * 1_000_000.0
        return CatalogPriceQuote(
            perMillion,
            "Озвучка: ${formatSpecializedPricing(entry.key, entry.value)}"
        )
    }

    val input = model.promptPriceUsdPerMillion
    val output = model.completionPriceUsdPerMillion
    val known = listOfNotNull(input, output)
    if (known.isEmpty()) return CatalogPriceQuote(null, "Озвучка: цена не указана")
    if (known.all { it <= 0.0 }) return CatalogPriceQuote(0.0, "Озвучка: бесплатно")

    val sortValue = known.average()
    val text = when {
        output != null && output > 0.0 ->
            "Озвучка / 1M: текст-токены ${formatCatalogPrice(input)} · аудио-токены ${formatCatalogPrice(output)}"
        input != null && input > 0.0 && model.providerId == "fish-audio" ->
            "Озвучка: ${formatCatalogPrice(input)} / 1M UTF-8 байт"
        input != null && input > 0.0 ->
            "Озвучка: ${formatCatalogPrice(input)} / 1M символов"
        else -> "Озвучка: цена не указана"
    }
    return CatalogPriceQuote(sortValue, text)
}

private fun transcriptionPriceQuote(model: ModelInfo): CatalogPriceQuote {
    val explicitPerSecond = (model.pricingSkusUsd + model.pricingUsd)
        .filter { (key, value) ->
            value >= 0.0 && key.lowercase().let { k ->
                k.contains("per-second") || k.contains("per_second") || k.contains("second")
            }
        }
        .values
        .minOrNull()
    val explicitPerMinute = (model.pricingSkusUsd + model.pricingUsd)
        .filter { (key, value) ->
            value >= 0.0 && key.lowercase().contains("minute")
        }
        .values
        .minOrNull()

    val perSecond = when {
        explicitPerSecond != null -> explicitPerSecond
        explicitPerMinute != null -> explicitPerMinute / 60.0
        else -> model.pricingUsd["prompt"]
    }

    if (perSecond == null) return CatalogPriceQuote(null, "Распознавание: цена не указана")
    if (perSecond <= 0.0) return CatalogPriceQuote(0.0, "Распознавание: бесплатно")

    return CatalogPriceQuote(
        perSecond,
        "Распознавание: ${formatCatalogPrice(perSecond)} / сек"
    )
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

private fun pricingFieldLabel(value: String): String {
    val key = value.lowercase()
    return when {
        key == "prompt" -> "Входные токены"
        key == "completion" -> "Выходные токены"
        key == "request" -> "Запрос"
        key == "image" -> "Входное изображение"
        key == "image_token" -> "Image token"
        key == "image_output" -> "Выходной image token"
        key == "web_search" -> "Веб-поиск"
        key == "internal_reasoning" -> "Reasoning tokens"
        key == "audio" -> "Аудио"
        key.startsWith("duration_seconds_") -> "Видео ${key.removePrefix("duration_seconds_")}"
        key == "duration_seconds" -> "Видео"
        key.contains("character") -> "Символы"
        key.contains("byte") -> "UTF-8 байты"
        key.contains("megapixel") -> "Мегапиксели"
        else -> value
    }
}

private fun formatRawPricing(key: String, value: Double): String = when (key.lowercase()) {
    "prompt", "completion" -> "${formatCatalogPrice(value * 1_000_000.0)} / 1M токенов"
    else -> formatSpecializedPricing(key, value)
}

private fun formatSpecializedPricing(key: String, value: Double): String {
    val normalized = key.lowercase()
    return when {
        normalized.startsWith("duration_seconds") || normalized.contains("per-video-second") || normalized.endsWith("_second") || normalized.endsWith("_seconds") ->
            "${formatCatalogPrice(value)} / сек"
        normalized.contains("minute") ->
            "${formatCatalogPrice(value)} / мин"
        normalized.contains("character") ->
            "${formatCatalogPrice(value * 1_000_000.0)} / 1M символов"
        normalized.contains("byte") ->
            "${formatCatalogPrice(value * 1_000_000.0)} / 1M байт"
        normalized.contains("megapixel") ->
            "${formatCatalogPrice(value)} / МП"
        normalized == "image" || normalized.endsWith("_image") ->
            "${formatCatalogPrice(value)} / изображение"
        normalized == "request" || normalized.endsWith("_request") ->
            "${formatCatalogPrice(value)} / запрос"
        else -> formatCatalogPrice(value)
    }
}

private fun catalogPriceText(model: ModelInfo, kind: SimpleModelKind): String? =
    catalogPriceQuote(model, kind).text


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
            UmnikInlineExpander(
                title = "Расширенная маршрутизация",
                expanded = advanced,
                onToggle = { advanced = !advanced }
            )
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
    controller: OpenRouterHubController,
    viewModel: ChatViewModel
) {
    var advanced by remember { mutableStateOf(false) }
    var defaultSearchEnabled by remember { mutableStateOf(viewModel.defaultWebSearchEnabled()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Поиск для новых чатов",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                UmnikInfoHint(
                    title = "Зачем это",
                    text = "Здесь задаются стартовые настройки поиска для новых обычных чатов. В уже созданном чате поиск включается и настраивается через «+»."
                )
            }
        }
        item {
            ToggleRow(
                "Поиск в новых чатах",
                defaultSearchEnabled,
                "Это только стартовое значение. После создания каждый чат хранит своё состояние поиска независимо от остальных."
            ) {
                defaultSearchEnabled = it
                viewModel.setDefaultWebSearchEnabled(it)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Режим поиска по умолчанию", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                UmnikInfoHint(
                    title = "Поиск для новых чатов",
                    text = "Этот режим получит новый чат. После создания его можно изменить в самом чате через «+»."
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
            UmnikInlineExpander(
                title = "Тонкая настройка поиска и инструменты",
                expanded = advanced,
                onToggle = { advanced = !advanced }
            )
        }
        if (advanced) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сервис интернет-поиска", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    UmnikInfoHint(
                        title = "Сервис поиска",
                        text = "Общий движок веб-поиска для всех чатов, где поиск включён. Auto подходит большинству пользователей; ручной выбор нужен только для тонкой настройки."
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
private fun JobsPage(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    onOpenCatalog: () -> Unit
) {
    val tasks = remember { mutableStateListOf(BatchDraftTask()) }
    var bulkInput by remember { mutableStateOf("") }
    var fileTargetIndex by remember { mutableStateOf<Int?>(null) }
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
            Text("Новый пакет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            SettingTitleWithInfo(
                title = "Быстро добавить списком",
                info = "Если у вас уже есть список коротких задач, вставьте по одной задаче на строку."
            )
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
            CategoryModelPicker(
                title = "Модель Batch",
                current = state.media.batchModel,
                onOpenCatalog = onOpenCatalog,
                onApply = controller::setBatchModelId
            )
        }
    }
}

@Composable
private fun MediaPage(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    section: MediaSection,
    onOpenCatalog: () -> Unit
) {
    var videoPrompt by remember { mutableStateOf("") }
    val videoRefs = remember { mutableStateListOf<Uri>() }
    var speechText by remember { mutableStateOf("") }
    var speechModelId by remember(state.media.speechModel) { mutableStateOf(state.media.speechModel) }
    var voice by remember(state.media.speechModel, state.media.voice) { mutableStateOf(state.media.voice) }
    var speechResponseFormat by remember(state.media.speechModel, state.media.responseFormat) { mutableStateOf(state.media.responseFormat.orEmpty()) }
    var videoSettingsExpanded by remember { mutableStateOf(false) }
    var transcriptionSettingsExpanded by remember { mutableStateOf(false) }
    var speechSettingsExpanded by remember { mutableStateOf(false) }
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
                if (section == MediaSection.ALL) {
                    SettingTitleWithInfo(
                        title = "Создание видео",
                        info = "Опишите видео, при необходимости добавьте референсы и нажмите «Создать». Готовый файл появится в исходном чате."
                    )
                }
                OutlinedTextField(videoPrompt, { videoPrompt = it }, Modifier.fillMaxWidth(), label = { Text("Описание видео") }, minLines = 3, maxLines = 7)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilledTonalButton(onClick = { videoPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }, modifier = Modifier.weight(1f)) { Text(if (videoRefs.isEmpty()) "Референсы" else "Референсы: " + videoRefs.size) }
                    Button(onClick = { controller.submitVideo(videoPrompt, videoRefs.toList()); videoPrompt = ""; videoRefs.clear() }, enabled = state.media.videoModel.isNotBlank() && videoPrompt.isNotBlank() && !state.loading, modifier = Modifier.weight(1f)) { Text("Создать") }
                }
            }
            item {
                UmnikInlineExpander(
                    title = "Модель и параметры",
                    subtitle = state.media.videoModel.substringAfterLast('/').ifBlank { "Модель не выбрана" },
                    expanded = videoSettingsExpanded,
                    onToggle = { videoSettingsExpanded = !videoSettingsExpanded }
                )
            }
            if (videoSettingsExpanded) {
                item {
                    CategoryModelPicker(
                        title = "ID модели видео",
                        current = state.media.videoModel,
                        onOpenCatalog = onOpenCatalog,
                        onApply = controller::setVideoModelId
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("История видео", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = controller::clearFinishedVideoHistory,
                        enabled = state.videos.any { it.status.terminal }
                    ) { Text("Очистить") }
                    TextButton(onClick = controller::refreshJobs) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Обновить")
                    }
                }
            }
            if (state.videos.isEmpty()) {
                item { Text("Пока нет видео-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.videos, key = { it.id }) { job ->
                    UmnikPanel {
                        Column(Modifier.padding(12.dp)) {
                            Text(job.modelId, fontWeight = FontWeight.SemiBold)
                            Text(videoLabel(job.status), style = MaterialTheme.typography.bodySmall)
                            Text(
                                job.prompt,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            job.costUsd?.let {
                                Text("Стоимость: ${formatUsdSmall(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.TRANSCRIPTION) {
            item {
                if (section == MediaSection.ALL) {
                    SettingTitleWithInfo(
                        title = "Распознавание речи",
                        info = "Выберите аудиофайл. Расшифровка появится здесь и будет добавлена в текущий чат."
                    )
                }
                FilledTonalButton(
                    onClick = { sttPicker.launch(arrayOf("audio/*")) },
                    enabled = state.media.transcriptionModel.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Выбрать аудиофайл") }
                if (state.transcription.isNotBlank()) {
                    UmnikPanel(modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(state.transcription)
                            TextButton(onClick = { copyToClipboard(context, state.transcription) }) { Text("Копировать") }
                        }
                    }
                }
            }
            item {
                UmnikInlineExpander(
                    title = "Модель и параметры",
                    subtitle = state.media.transcriptionModel.substringAfterLast('/').ifBlank { "Модель не выбрана" },
                    expanded = transcriptionSettingsExpanded,
                    onToggle = { transcriptionSettingsExpanded = !transcriptionSettingsExpanded }
                )
            }
            if (transcriptionSettingsExpanded) {
                item {
                    CategoryModelPicker(
                        title = "ID модели распознавания",
                        current = state.media.transcriptionModel,
                        onOpenCatalog = onOpenCatalog,
                        onApply = controller::setTranscriptionModelId
                    )
                }
            }

            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.SPEECH) {
            item {
                if (section == MediaSection.ALL) {
                    SettingTitleWithInfo(
                        title = "Озвучивание текста и документов",
                        info = "Введите текст или загрузите текстовый файл и нажмите «Создать аудио»."
                    )
                }
                OutlinedTextField(
                    speechText,
                    { speechText = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Текст для озвучивания") },
                    minLines = 3,
                    maxLines = 8
                )
                FilledTonalButton(
                    onClick = { speechTextPicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Загрузить текстовый файл") }
                Button(
                    onClick = {
                        controller.updateMedia(
                            state.media.copy(
                                voice = voice.trim(),
                                responseFormat = speechResponseFormat.ifBlank { null }
                            )
                        )
                        controller.synthesize(speechText)
                    },
                    enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Создать аудио") }
                if (state.media.speechModel.isBlank()) {
                    Text(
                        "Для создания аудио сначала выберите модель в настройках ниже.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                state.speechFile?.let { file ->
                    Text(
                        "Готово и добавлено в чат: ${file.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            item {
                UmnikInlineExpander(
                    title = "Модель, голос и параметры",
                    subtitle = state.media.speechModel.substringAfterLast('/').ifBlank { "Модель не выбрана" },
                    expanded = speechSettingsExpanded,
                    onToggle = { speechSettingsExpanded = !speechSettingsExpanded }
                )
            }
            if (speechSettingsExpanded) {
                item {
                    val selectedSpeechModel = state.catalog.firstOrNull { it.id == state.media.speechModel }
                    val documentVoiceOptions = selectedSpeechModel?.parameterValues("voice").orEmpty()
                    UmnikModelIdField(
                        label = "ID модели озвучивания",
                        value = speechModelId,
                        onValueChange = { speechModelId = it },
                        onPick = onOpenCatalog,
                        onApply = { controller.setMediaSpeechModelId(speechModelId) },
                        info = "Можно вставить ID модели OpenRouter вручную или открыть каталог значком поиска."
                    )
                    if (state.media.speechModel.isNotBlank()) {
                        SettingTitleWithInfo(
                            title = "Голос и формат (необязательно)",
                            info = "Некоторым моделям нужен конкретный голос или формат. Если модель работает без них, оставьте голос пустым, а формат — «Авто»."
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
                            value = voice,
                            onValueChange = { voice = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            label = { Text("Voice / ID голоса") },
                            trailingIcon = {
                                UmnikInfoHint(
                                    title = "ID голоса",
                                    text = "ID или название голоса смотрите на странице выбранной модели на сайте OpenRouter. Если Umnik получил список voice из каталога модели, готовые варианты показаны выше."
                                )
                            },
                            singleLine = true
                        )
                        SettingTitleWithInfo(
                            title = "Формат ответа",
                            info = "Оставьте «Авто», если модель не требует конкретный формат. Umnik автоматически оборачивает PCM в WAV для воспроизведения на Android.",
                            modifier = Modifier.padding(top = 8.dp)
                        )
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
                            onClick = {
                                controller.updateMedia(
                                    state.media.copy(
                                        voice = voice.trim(),
                                        responseFormat = speechResponseFormat.ifBlank { null }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                        ) { Text("Сохранить параметры") }
                    }
                }
            }
        }
    }
}


@Composable
private fun ReplySpeechPage(
    state: OpenRouterHubState,
    appState: UiState,
    controller: OpenRouterHubController,
    onOpenCatalog: () -> Unit
) {
    val selected = state.catalog.firstOrNull { it.id == appState.openRouterSpeechModel }
    val voiceOptions = selected?.parameterValues("voice").orEmpty()
    var replySpeechModelId by remember(appState.openRouterSpeechModel) {
        mutableStateOf(appState.openRouterSpeechModel)
    }
    var manualVoice by remember(appState.openRouterSpeechModel, appState.openRouterSpeechVoice) {
        mutableStateOf(appState.openRouterSpeechVoice)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingTitleWithInfo(
                title = "Кнопка OR под ответами",
                info = "Эти настройки относятся только к кнопке OR под ответами и не влияют на озвучивание текста и документов. Голос и формат задавайте только если они нужны выбранной модели."
            )
        }
        item {
            UmnikModelIdField(
                label = "ID модели озвучивания ответов",
                value = replySpeechModelId,
                onValueChange = { replySpeechModelId = it },
                onPick = onOpenCatalog,
                onApply = { controller.setReplySpeechModelId(replySpeechModelId) },
                info = "Можно вставить ID модели OpenRouter вручную или открыть каталог значком поиска."
            )
        }
        if (appState.openRouterSpeechModel.isNotBlank()) {
            item {
                SettingTitleWithInfo(
                    title = "Голос (необязательно)",
                    info = "Если у модели есть голос по умолчанию, оставьте «Не задавать». Если OpenRouter требует voice, выберите готовый вариант или введите ID вручную."
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
                    trailingIcon = {
                        UmnikInfoHint(
                            title = "ID голоса",
                            text = "ID или название голоса смотрите на странице выбранной модели на сайте OpenRouter. Если OpenRouter сообщает готовые варианты voice, Umnik показывает их выше."
                        )
                    },
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = { controller.updateReplySpeechVoice(manualVoice.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text(if (manualVoice.isBlank()) "Сохранить без голоса" else "Сохранить голос") }
            }
            item {
                SettingTitleWithInfo(
                    title = "Формат ответа",
                    info = "Авто выбирает подходящий формат для известных TTS-моделей и не навязывает его неизвестным. При однозначной ошибке формата Umnik один раз повторит запрос с требуемым MP3/PCM."
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

            }
        }
    }
}

@Composable
private fun LocalShellPage(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    currentChatId: String,
    onReturnToChat: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var networkEnabled by remember { mutableStateOf(true) }
    var showResultHere by remember(state.localShellResult) { mutableStateOf(false) }
    val files = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files.clear()
        files.addAll(uris.take(10))
    }
    val context = LocalContext.current
    val belongsToCurrentChat = state.localShellChatId == null || state.localShellChatId == currentChatId

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Задача") },
                minLines = 4,
                maxLines = 10
            )
        }

        item {
            FilledTonalButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !state.localShellRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (files.isEmpty()) "Добавить файлы" else "Выбрано файлов: " + files.size)
            }
            if (files.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    files.toList().forEach { uri ->
                        val info = remember(uri) { shellAttachmentInfo(context, uri) }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(info.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    info.sizeBytes?.let { size ->
                                        Text(
                                            shellFileSizeLabel(size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { files.remove(uri) },
                                    enabled = !state.localShellRunning
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Убрать файл")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            UmnikPanel {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Сетевой шлюз", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (networkEnabled) "GET и публичный Git clone разрешены" else "Локальная работа без сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = networkEnabled,
                        onCheckedChange = { networkEnabled = it },
                        enabled = !state.localShellRunning
                    )
                }
            }
        }

        item {
            if (state.localShellRunning && belongsToCurrentChat) {
                UmnikPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Локальный Shell работает", fontWeight = FontWeight.SemiBold)
                        }
                        state.localShellStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            buildString {
                                append("Модель: ")
                                append(state.localShellModel?.substringAfterLast('/') ?: "—")
                                append(" · шагов модели: ")
                                append(state.localShellTurns)
                                append(" · локальных действий: ")
                                append(state.localShellToolCalls)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = controller::cancelLocalShell,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Остановить")
                        }
                    }
                }
            } else {
                Button(
                    onClick = { controller.runLocalShell(prompt, files.toList(), networkEnabled) },
                    enabled = prompt.isNotBlank() && !state.localShellRunning && !state.shellRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Запустить локально")
                }
            }
        }

        if (belongsToCurrentChat && state.localShellError != null) {
            item {
                Text(
                    state.localShellError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (belongsToCurrentChat && state.localShellResult.isNotBlank() && !state.localShellRunning) {
            item {
                UmnikPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            "Готово · шагов модели: " + state.localShellTurns +
                                " · локальных действий: " + state.localShellToolCalls +
                                if (state.localShellFileCount > 0) " · файлов: " + state.localShellFileCount else "",
                            fontWeight = FontWeight.SemiBold
                        )
                        state.localShellCostUsd?.let { cost ->
                            Text(
                                "Стоимость модели: $" + String.format(Locale.US, "%.6f", cost),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showResultHere = !showResultHere }) {
                            Text(if (showResultHere) "Скрыть ответ" else "Показать ответ")
                        }
                        if (showResultHere) {
                            Text(state.localShellResult, style = MaterialTheme.typography.bodyMedium)
                        }
                        FilledTonalButton(
                            onClick = onReturnToChat,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Вернуться в чат")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "MVP: Toybox, ZIP/TAR, Git, Python и сетевой шлюз работают на устройстве. " +
                    "Исходные вложения не передаются в OpenRouter как файлы; модель получает только результаты запрошенных локальных действий.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShellPage(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    currentChatId: String,
    onReturnToChat: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var showResultHere by remember(state.shellResult) { mutableStateOf(false) }
    val files = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files.clear(); files.addAll(uris.take(10))
    }
    val context = LocalContext.current
    val shellActivity by AsyncJobEvents.shellActivity.collectAsState()
    val activeShell = shellActivity?.takeIf { it.chatId == currentChatId }
    val belongsToCurrentChat = state.shellChatId == null || state.shellChatId == currentChatId

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                prompt,
                { prompt = it },
                Modifier.fillMaxWidth(),
                label = { Text("Задача") },
                minLines = 4,
                maxLines = 10
            )
            FilledTonalButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !state.shellRunning && !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text(if (files.isEmpty()) "Добавить файлы" else "Выбрано файлов: ${files.size}")
            }
            if (files.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    files.toList().forEach { uri ->
                        val info = remember(uri) { shellAttachmentInfo(context, uri) }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        info.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    info.sizeBytes?.let { size ->
                                        Text(
                                            shellFileSizeLabel(size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { files.remove(uri) },
                                    enabled = !state.shellRunning && !state.loading
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Убрать файл")
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    controller.runShell(prompt, files.toList())
                },
                enabled = prompt.isNotBlank() && activeShell == null && !state.shellRunning && !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("Выполнить через Shell") }

            if (activeShell != null) {
                Text(
                    "Можно вернуться в чат: задача продолжит выполняться, а результат появится там.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        activeShell?.let { activity ->
            item {
                ShellProgressPanel(
                    activity = activity,
                    onStop = controller::cancelShell
                )
            }
        }

        if (state.shellResult.isNotBlank() && activeShell == null && !state.shellRunning && belongsToCurrentChat) {
            item {
                UmnikPanel {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (state.shellFileCount > 0) {
                                "Готово · создано файлов: ${state.shellFileCount}"
                            } else {
                                "Готово"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        FilledTonalButton(
                            onClick = onReturnToChat,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Открыть результат в чате") }
                        TextButton(
                            onClick = { showResultHere = !showResultHere },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (showResultHere) "Скрыть ответ здесь" else "Показать ответ здесь")
                        }
                        if (showResultHere) {
                            Text(
                                state.shellResult,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            TextButton(
                                onClick = { copyToClipboard(context, state.shellResult) }
                            ) { Text("Копировать результат") }
                        }
                    }
                }
            }
        }

        state.shellError?.takeIf { it.isNotBlank() && belongsToCurrentChat }?.let { error ->
            item {
                UmnikPanel {
                    Column(Modifier.padding(12.dp)) {
                        Text("Shell не выполнил задачу", fontWeight = FontWeight.SemiBold)
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "Ошибка также добавлена в исходный чат.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        FilledTonalButton(
                            onClick = onReturnToChat,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Вернуться в чат") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellProgressPanel(
    activity: ShellActivity,
    onStop: () -> Unit
) {
    var now by remember(activity.startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = shellDurationLabel(now - activity.startedAt)
    val lastSignal = activity.lastRemoteEventAt?.let { shellAgoLabel(now - it) }

    UmnikPanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Shell работает · $elapsed", fontWeight = FontWeight.SemiBold)
            Text(
                activity.status,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (activity.modelId.isNotBlank()) {
                Text(
                    "Модель: ${activity.modelId.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activity.attachmentCount > 0) {
                Text(
                    "Вложений: ${activity.attachmentCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activity.shellSteps > 0) {
                Text(
                    "Этапов Shell: ${activity.shellSteps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activity.eventCount > 0) {
                Text(
                    buildString {
                        append("Событий OpenRouter: ${activity.eventCount}")
                        if (lastSignal != null) append(" · последний сигнал $lastSignal назад")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Жду первый сигнал от OpenRouter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Автоматический повтор после обрыва не запускается, чтобы не было повторного списания.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Остановить Shell")
            }
        }
    }
}

private fun shellDurationLabel(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "${minutes} мин ${seconds} с" else "${seconds} с"
}

private fun shellAgoLabel(durationMs: Long): String {
    val seconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 5L -> "только что"
        seconds < 60L -> "${seconds} с"
        else -> "${seconds / 60L} мин"
    }
}

@Composable
private fun CategoryModelPicker(
    title: String,
    current: String,
    onOpenCatalog: () -> Unit,
    onApply: (String) -> Unit
) {
    var modelId by remember(current) { mutableStateOf(current) }
    UmnikModelIdField(
        label = title,
        value = modelId,
        onValueChange = { modelId = it },
        onPick = onOpenCatalog,
        onApply = { onApply(modelId) },
        info = "Можно вставить ID модели OpenRouter вручную или открыть каталог значком поиска."
    )
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


private data class ShellAttachmentInfo(val name: String, val sizeBytes: Long?)

private fun shellAttachmentInfo(context: Context, uri: Uri): ShellAttachmentInfo {
    val resolver = context.contentResolver
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Файл"
    return ShellAttachmentInfo(name?.takeIf { it.isNotBlank() } ?: fallbackName, size)
}

private fun shellFileSizeLabel(bytes: Long): String = when {
    bytes < 1024L -> "$bytes Б"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
}
