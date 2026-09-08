package com.ayuemin.ymnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
        state.status?.let { snackbar.showSnackbar(it); viewModel.dismissStatus() }
    }

    MaterialTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("Чат") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🧩") }, label = { Text("Навыки") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("Настройки") })
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
                    CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(28.dp))
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

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Умник", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(state.model, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::clearChat, enabled = state.messages.isNotEmpty() && !state.isLoading) { Text("Очистить") }
        }

        if (state.activeSkillIds.isNotEmpty()) {
            Text("Навыки: " + state.skills.filter { it.id in state.activeSkillIds }.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageCard(message, tts) { file ->
                    fileToSave = file
                    save.launch(file.name)
                }
            }
        }

        if (state.pendingAttachments.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().height(82.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.pendingAttachments, key = { it.uri }) { a ->
                    AssistChip(onClick = { vm.removeAttachment(a.uri) }, label = { Text("${a.name}  ×") })
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedButton(onClick = { attach.launch(arrayOf("*/*")) }, enabled = !state.isLoading) { Text("＋") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение...") },
                maxLines = 6
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.send(text); text = "" },
                enabled = !state.isLoading && (text.isNotBlank() || state.pendingAttachments.isNotEmpty())
            ) { Text("→") }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage, tts: TtsController, onSave: (GeneratedFile) -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (user) "Вы" else "Умник", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(message.text)
                message.attachmentNames.forEach { Text("📎 $it", style = MaterialTheme.typography.bodySmall) }

                if (!user && message.text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { tts.toggle(message.id, message.text) }) {
                        Text(if (tts.speakingMessageId == message.id) "■ Стоп" else "🔊 Озвучить")
                    }
                }

                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onSave(file) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Сохранить ${file.name} (${humanSize(file.size)})")
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsScreen(state: UiState, vm: ChatViewModel) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSkillFile) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importSkillTree) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Навыки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Импортируйте SKILL.md или целую папку. Текст подключённых навыков добавляется к системной инструкции модели.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) { Text("Файл навыка") }
            OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Папка навыка") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.skills, key = { it.id }) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(skill.name, fontWeight = FontWeight.Bold)
                        Text("Файлов: ${skill.files.size}", style = MaterialTheme.typography.bodySmall)
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.apiKeyConfigured) "OpenRouter API key (уже сохранён)" else "OpenRouter API key") },
            placeholder = { Text("sk-or-v1-…") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ID модели") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(key.takeIf { it.isNotBlank() }, model); key = "" }) { Text("Сохранить") }
            OutlinedButton(onClick = { vm.refreshModels(); showModels = true }) { Text("Выбрать модель") }
        }
        Spacer(Modifier.height(16.dp))
        Text("API-ключ шифруется ключом из Android Keystore и не хранится в исходном коде приложения.", style = MaterialTheme.typography.bodySmall)
        Text("Модель по умолчанию: openrouter/auto. Для навыков с инструментами лучше выбирать модель с поддержкой tool calling.", style = MaterialTheme.typography.bodySmall)
    }

    if (showModels) {
        val filtered = state.availableModels.filter { it.contains(query, ignoreCase = true) }.take(100)
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Модель OpenRouter") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск") })
                    Spacer(Modifier.height(8.dp))
                    if (state.availableModels.isEmpty()) Text("Список загружается…")
                    else LazyColumn(Modifier.height(420.dp)) {
                        items(filtered) { id -> TextButton(onClick = { model = id; showModels = false }, modifier = Modifier.fillMaxWidth()) { Text(id, modifier = Modifier.fillMaxWidth()) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Закрыть") } }
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / 1024.0 / 1024.0)
}
