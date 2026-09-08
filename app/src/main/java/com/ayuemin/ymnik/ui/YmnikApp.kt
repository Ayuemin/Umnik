package com.ayuemin.ymnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.tts.TtsController

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

    UmnikTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("💬") },
                        label = { Text("Чат") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("🧩") },
                        label = { Text("Навыки") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Text("⚙") },
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
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
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
                            Text("Модель думает…", style = MaterialTheme.typography.labelLarge)
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

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addAttachment)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        fileToSave = null
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Umnik",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = state.model,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    TextButton(
                        onClick = vm::clearChat,
                        enabled = state.messages.isNotEmpty() && !state.isLoading
                    ) { Text("Очистить") }
                }

                if (state.activeSkillIds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.skills.filter { it.id in state.activeSkillIds }, key = { it.id }) { skill ->
                            AssistChip(
                                onClick = { vm.toggleSkill(skill.id) },
                                label = { Text(skill.name) },
                                leadingIcon = { Text("🧩") }
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyChatCard()
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(message, tts) { file ->
                    fileToSave = file
                    save.launch(file.name)
                }
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
                            label = { Text("📎 ${attachment.name}  ×") }
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { attach.launch(arrayOf("*/*")) },
                    enabled = !state.isLoading,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("＋", style = MaterialTheme.typography.headlineSmall)
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 5
                )

                Button(
                    onClick = {
                        vm.send(text)
                        text = ""
                    },
                    enabled = !state.isLoading && (text.isNotBlank() || state.pendingAttachments.isNotEmpty()),
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("↑", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun EmptyChatCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Привет!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Напишите сообщение, приложите файл или подключите навык. Umnik отправит запрос выбранной модели через OpenRouter.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    tts: TtsController,
    onSave: (GeneratedFile) -> Unit
) {
    val user = message.role == "user"
    val container = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = if (user) 24.dp else 6.dp,
                bottomEnd = if (user) 6.dp else 24.dp
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
                Spacer(Modifier.height(5.dp))
                Text(message.text, color = content)

                message.attachmentNames.forEach {
                    Spacer(Modifier.height(5.dp))
                    Text("📎 $it", style = MaterialTheme.typography.bodySmall, color = content)
                }

                if (!user && message.text.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = { tts.toggle(message.id, message.text) }) {
                        Text(if (tts.speakingMessageId == message.id) "■ Стоп" else "🔊 Озвучить")
                    }
                }

                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { onSave(file) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить ${file.name} · ${humanSize(file.size)}")
                    }
                }
            }
        }
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
            "Подключайте SKILL.md и папки с текстовыми материалами. Активные навыки автоматически добавляются к инструкции модели.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) {
                Text("＋ Файл")
            }
            FilledTonalButton(onClick = { treePicker.launch(null) }) {
                Text("📁 Папка")
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.skills.isEmpty()) {
                item {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(24.dp)
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
                    shape = RoundedCornerShape(22.dp),
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
                                label = { Text(if (skill.id in state.activeSkillIds) "Подключён" else "Подключить") }
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.deleteSkill(skill.id) }) { Text("Удалить") }
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
    var model by remember(state.model) { mutableStateOf(state.model) }
    var showModels by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("OpenRouter и модель", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (state.apiKeyConfigured) "OpenRouter API key сохранён" else "OpenRouter API key") },
                    placeholder = { Text("sk-or-v1-…") },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ID модели") },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.saveSettings(key.takeIf { it.isNotBlank() }, model)
                        key = ""
                    }) { Text("Сохранить") }
                    FilledTonalButton(onClick = {
                        vm.refreshModels()
                        showModels = true
                    }) { Text("Выбрать модель") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(15.dp)) {
                Text("🔐 Ключ хранится локально", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "API-ключ шифруется через Android Keystore и не записывается в APK или репозиторий.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }

    if (showModels) {
        val filtered = state.availableModels.filter { it.contains(query, ignoreCase = true) }.take(100)
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Модель OpenRouter") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск модели") },
                        shape = RoundedCornerShape(18.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.availableModels.isEmpty()) {
                        Text("Список загружается…")
                    } else {
                        LazyColumn(Modifier.height(420.dp)) {
                            items(filtered) { id ->
                                TextButton(
                                    onClick = {
                                        model = id
                                        showModels = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(id, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModels = false }) { Text("Закрыть") }
            }
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / 1024.0 / 1024.0)
}
