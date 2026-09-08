package com.ayuemin.ymnik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ThemeChoice
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.tts.TtsController
import kotlinx.coroutines.delay

@Composable
fun YmnikApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val tts = remember { TtsController(context) }
    var tab by remember { mutableIntStateOf(0) }

    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }

    LaunchedEffect(state.status) {
        state.status?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissStatus()
        }
    }

    UmnikTheme(state.themeChoice) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                        label = { Text("Чат") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                        label = { Text("Навыки") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text("Настройки") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> ChatScreen(state, viewModel, tts)
                    1 -> SkillsScreen(state, viewModel)
                    else -> SettingsScreen(state, viewModel)
                }

                if (state.isLoading) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(state.busyLabel ?: "Выполняю…", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(state: UiState, vm: ChatViewModel, tts: TtsController) {
    var text by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var pickerMode by remember { mutableStateOf<ChatMode?>(null) }
    val listState = rememberLazyListState()

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addAttachment)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        fileToSave = null
    }

    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) {
            delay(120)
            listState.animateScrollToItem(state.messages.lastIndex, 10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatHeader(
            state = state,
            onSelectMode = { mode ->
                vm.setMode(mode)
                pickerMode = mode
            },
            onClear = vm::clearChat
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyChatCard(state.mode) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(
                    message = message,
                    tts = tts,
                    onSaveGenerated = { file ->
                        fileToSave = file
                        save.launch(file.name)
                    },
                    onExportText = {
                        val file = vm.exportMessage(message)
                        fileToSave = file
                        save.launch(file.name)
                    }
                )
            }
        }

        if (state.pendingAttachments.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.pendingAttachments, key = { it.uri }) { attachment ->
                        AssistChip(
                            onClick = { vm.removeAttachment(attachment.uri) },
                            label = {
                                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, contentDescription = "Убрать", modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        val types = if (state.mode == ChatMode.IMAGE) arrayOf("image/*") else arrayOf("*/*")
                        attach.launch(types)
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Прикрепить файл")
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (state.mode == ChatMode.IMAGE) "Опишите изображение…" else "Сообщение…")
                    },
                    shape = RoundedCornerShape(25.dp),
                    maxLines = 6
                )

                IconButton(
                    onClick = {
                        vm.send(text)
                        text = ""
                    },
                    enabled = !state.isLoading && (text.isNotBlank() || state.pendingAttachments.isNotEmpty()),
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = "Отправить")
                }
            }
        }
    }

    pickerMode?.let { mode ->
        ModelPickerDialog(
            mode = mode,
            state = state,
            vm = vm,
            onDismiss = { pickerMode = null }
        )
    }
}

@Composable
private fun ChatHeader(
    state: UiState,
    onSelectMode: (ChatMode) -> Unit,
    onClear: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Umnik",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClear, enabled = state.messages.isNotEmpty() && !state.isLoading) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Очистить чат")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == ChatMode.TEXT,
                    onClick = { onSelectMode(ChatMode.TEXT) },
                    label = { Text("Текст") },
                    leadingIcon = {
                        Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                FilterChip(
                    selected = state.mode == ChatMode.IMAGE,
                    onClick = { onSelectMode(ChatMode.IMAGE) },
                    label = { Text("Изображение") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }

            if (state.mode == ChatMode.TEXT && state.activeSkillIds.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.skills.filter { it.id in state.activeSkillIds }, key = { it.id }) { skill ->
                        AssistChip(
                            onClick = { },
                            label = { Text(skill.name) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    mode: ChatMode,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var query by remember(mode) { mutableStateOf("") }
    val models = if (mode == ChatMode.TEXT) state.availableTextModels else state.availableImageModels
    val current = if (mode == ChatMode.TEXT) state.textModel else state.imageModel

    LaunchedEffect(mode) {
        if (models.isEmpty()) vm.refreshModels(mode)
    }

    val filtered = remember(models, query) {
        models.filter { it.contains(query.trim(), ignoreCase = true) }.take(250)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == ChatMode.TEXT) "Текстовая модель" else "Модель изображений") },
        text = {
            Column {
                Text(
                    "Сейчас: $current",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Поиск модели") }
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        if (state.isLoading) "Загрузка списка…" else "Модели не найдены",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 430.dp)) {
                        items(filtered) { id ->
                            TextButton(
                                onClick = {
                                    vm.selectModel(mode, id)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(id, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.refreshModels(mode) }) { Text("Обновить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun EmptyChatCard(mode: ChatMode) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (mode == ChatMode.TEXT) "Готов к работе" else "Режим изображений",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == ChatMode.TEXT)
                    "Напишите сообщение, приложите файл или подключите навык."
                else
                    "Опишите изображение. При необходимости приложите изображение-референс.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    tts: TtsController,
    onSaveGenerated: (GeneratedFile) -> Unit,
    onExportText: () -> Unit
) {
    val context = LocalContext.current
    val user = message.role == "user"
    val container = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (user) 22.dp else 6.dp,
                bottomEnd = if (user) 6.dp else 22.dp
            ),
            colors = CardDefaults.elevatedCardColors(containerColor = container)
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    if (user) "Вы" else "Umnik",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = content
                )
                Spacer(Modifier.height(6.dp))
                MessageBody(message.text, content)

                message.attachmentNames.forEach { name ->
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(17.dp), tint = content)
                        Spacer(Modifier.width(5.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, color = content)
                    }
                }

                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(10.dp))
                    GeneratedFileCard(file, onSaveGenerated)
                }
            }
        }

        if (!user && (message.text.isNotBlank() || message.generatedFiles.isNotEmpty())) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { copyText(context, message.text) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать ответ")
                }
                IconButton(onClick = { shareText(context, message.text) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Поделиться")
                }
                if (message.text.isNotBlank()) {
                    IconButton(onClick = { tts.toggle(message.id, message.text) }) {
                        Icon(
                            if (tts.speakingMessageId == message.id) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                            contentDescription = if (tts.speakingMessageId == message.id) "Остановить озвучку" else "Озвучить"
                        )
                    }
                    IconButton(onClick = onExportText) {
                        Icon(Icons.Outlined.Download, contentDescription = "Сохранить ответ файлом")
                    }
                }
            }
        }
    }
}

private enum class MessagePartKind { PLAIN, CODE, COPY }

private data class MessagePart(
    val text: String,
    val kind: MessagePartKind,
    val language: String = ""
)

@Composable
private fun MessageBody(text: String, color: androidx.compose.ui.graphics.Color) {
    val parts = remember(text) { splitRichBlocks(text) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part.kind) {
                MessagePartKind.PLAIN -> if (part.text.isNotBlank()) {
                    SelectionContainer { Text(part.text.trim(), color = color) }
                }
                MessagePartKind.CODE -> IsolatedBlock(
                    title = part.language.ifBlank { "Код" },
                    text = part.text,
                    monospace = true,
                    onCopy = { copyText(context, part.text) }
                )
                MessagePartKind.COPY -> IsolatedBlock(
                    title = "Для копирования",
                    text = part.text,
                    monospace = false,
                    onCopy = { copyText(context, part.text) }
                )
            }
        }
    }
}

@Composable
private fun IsolatedBlock(
    title: String,
    text: String,
    monospace: Boolean,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать блок", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            SelectionContainer {
                Text(
                    text.trim(),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun splitRichBlocks(text: String): List<MessagePart> {
    val regex = Regex(":::copy[ \\t]*\\n([\\s\\S]*?)\\n:::[ \\t]*|```([^\\n`]*)\\n?([\\s\\S]*?)```")
    val result = mutableListOf<MessagePart>()
    var cursor = 0

    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            result += MessagePart(text.substring(cursor, match.range.first), MessagePartKind.PLAIN)
        }
        if (match.value.startsWith(":::copy")) {
            result += MessagePart(match.groupValues[1].trim(), MessagePartKind.COPY)
        } else {
            result += MessagePart(
                text = match.groupValues[3].trimEnd(),
                kind = MessagePartKind.CODE,
                language = match.groupValues[2].trim()
            )
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) result += MessagePart(text.substring(cursor), MessagePartKind.PLAIN)
    if (result.isEmpty()) result += MessagePart(text, MessagePartKind.PLAIN)
    return result
}

@Composable
private fun GeneratedFileCard(file: GeneratedFile, onSave: (GeneratedFile) -> Unit) {
    if (file.mimeType.startsWith("image/")) {
        val bitmap = remember(file.localPath) { BitmapFactory.decodeFile(file.localPath) }
        if (bitmap != null) {
            ComposeImage(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat())
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(7.dp))
        }
    }

    FilledTonalButton(
        onClick = { onSave(file) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${file.name} · ${humanSize(file.size)}",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Download, contentDescription = "Сохранить")
    }
}

@Composable
private fun SkillsScreen(state: UiState, vm: ChatViewModel) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importSkillFile)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::importSkillTree)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Навыки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "SKILL.md и папки с текстовыми материалами. Подключённые навыки применяются в текстовом режиме.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Файл")
            }
            FilledTonalButton(onClick = { treePicker.launch(null) }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Папка")
            }
        }

        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.skills.isEmpty()) {
                item {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(
                            "Пока навыков нет. Импортируйте SKILL.md или папку навыка.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(state.skills, key = { it.id }) { skill ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(15.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text("Файлов: ${skill.files.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = skill.id in state.activeSkillIds,
                                onClick = { vm.toggleSkill(skill.id) },
                                label = { Text(if (skill.id in state.activeSkillIds) "Подключён" else "Подключить") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: ChatViewModel) {
    var key by remember { mutableStateOf("") }
    val themes = ThemeChoice.entries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("OpenRouter и локальные параметры", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("API-ключ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (state.apiKeyConfigured) "Новый ключ (старый уже сохранён)" else "OpenRouter API key")
                        },
                        placeholder = { Text("sk-or-v1-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.saveApiKey(key.takeIf { it.isNotBlank() })
                            key = ""
                        }
                    ) { Text("Сохранить") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ключ шифруется через Android Keystore. Модель выбирается нажатием на «Текст» или «Изображение» в чате.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Интерфейс", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Звук готового ответа", fontWeight = FontWeight.Medium)
                            Text(
                                "Короткий тихий сигнал после ответа модели",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.answerSoundEnabled,
                            onCheckedChange = vm::setAnswerSoundEnabled
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Palette, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Цветовая схема", fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(themes) { choice ->
                            FilterChip(
                                selected = state.themeChoice == choice,
                                onClick = { vm.setThemeChoice(choice) },
                                label = { Text(themeLabel(choice)) }
                            )
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Текущие модели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("Текст: ${state.textModel}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Изображения: ${state.imageModel}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun themeLabel(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.DYNAMIC -> "Material You"
    ThemeChoice.GRAPHITE -> "Графит"
    ThemeChoice.OCEAN -> "Синяя"
    ThemeChoice.FOREST -> "Зелёная"
    ThemeChoice.AMBER -> "Янтарная"
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Umnik", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / 1024.0 / 1024.0)
}
