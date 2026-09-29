package com.ayuemin.ymnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.model.BatchJobStatus
import com.ayuemin.ymnik.model.VideoJobStatus

internal enum class ChatUtilityTool {
    TRANSCRIPTION,
    SPEECH,
    VIDEO,
    BATCH
}

@Composable
internal fun ChatUtilityToolPanelHost(
    tool: ChatUtilityTool?,
    controller: OpenRouterHubController,
    onDismiss: () -> Unit
) {
    if (tool == null) return

    val state by controller.state.collectAsState()
    LaunchedEffect(tool) {
        controller.refreshJobs()
    }

    when (tool) {
        ChatUtilityTool.TRANSCRIPTION -> TranscriptionFloatingPanel(state, controller, onDismiss)
        ChatUtilityTool.SPEECH -> SpeechFloatingPanel(state, controller, onDismiss)
        ChatUtilityTool.VIDEO -> VideoFloatingPanel(state, controller, onDismiss)
        ChatUtilityTool.BATCH -> BatchFloatingPanel(state, controller, onDismiss)
    }
}

@Composable
private fun TranscriptionFloatingPanel(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    onDismiss: () -> Unit
) {
    var modelId by remember(state.media.transcriptionModel) { mutableStateOf(state.media.transcriptionModel) }
    var settingsOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::transcribe)
    }

    FloatingChatToolPanel(
        title = "В текст",
        subtitle = "Распознавание речи · результат вернётся в текущий чат",
        onDismiss = onDismiss,
        initialHeight = 390.dp,
        minHeight = 260.dp,
        maxHeightFraction = 0.68f,
        headerAction = {
            IconButton(onClick = { settingsOpen = !settingsOpen }) {
                Icon(Icons.Outlined.Info, contentDescription = "Модель распознавания")
            }
        }
    ) {
        if (state.loading) {
            ToolRunningStatus(state.operation ?: "Распознаю аудио…")
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                "Выберите аудиофайл. Расшифровка автоматически добавится в текущий чат.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(top = 5.dp))
            FilledTonalButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                enabled = state.media.transcriptionModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Выбрать аудиофайл")
            }

            if (settingsOpen) {
                Spacer(Modifier.padding(top = 5.dp))
                UmnikPanel {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("ID модели распознавания") },
                            singleLine = true,
                            shape = UmnikFieldShape
                        )
                        FilledTonalButton(
                            onClick = { controller.setTranscriptionModelId(modelId) },
                            enabled = modelId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сохранить модель") }
                    }
                }
            }

            if (state.transcription.isNotBlank()) {
                Spacer(Modifier.padding(top = 5.dp))
                UmnikPanel(modifier = Modifier.weight(1f)) {
                    Text(
                        state.transcription,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        state.status?.takeIf { it.isNotBlank() }?.let { ToolStatusText(it) }
    }
}

@Composable
private fun SpeechFloatingPanel(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var modelId by remember(state.media.speechModel) { mutableStateOf(state.media.speechModel) }
    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }
    var format by remember(state.media.responseFormat) { mutableStateOf(state.media.responseFormat.orEmpty()) }
    var settingsOpen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.loadTextFileForInput(it) { loaded -> text = loaded } }
    }

    FloatingChatToolPanel(
        title = "Озвучить",
        subtitle = "Текст или документ → аудио в текущем чате",
        onDismiss = onDismiss,
        initialHeight = 520.dp,
        minHeight = 330.dp,
        maxHeightFraction = 0.80f,
        headerAction = {
            IconButton(onClick = { settingsOpen = !settingsOpen }) {
                Icon(Icons.Outlined.Info, contentDescription = "Настройки озвучивания")
            }
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("Текст для озвучивания") },
            minLines = 3,
            maxLines = 10,
            shape = UmnikFieldShape
        )
        FilledTonalButton(
            onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Загрузить текстовый файл")
        }

        if (settingsOpen) {
            UmnikPanel(modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ID модели озвучивания") },
                        singleLine = true,
                        shape = UmnikFieldShape
                    )
                    OutlinedTextField(
                        value = voice,
                        onValueChange = { voice = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Voice / ID голоса, необязательно") },
                        singleLine = true,
                        shape = UmnikFieldShape
                    )
                    OutlinedTextField(
                        value = format,
                        onValueChange = { format = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Формат: авто / mp3 / pcm") },
                        singleLine = true,
                        shape = UmnikFieldShape
                    )
                    FilledTonalButton(
                        onClick = {
                            controller.setMediaSpeechModelId(modelId)
                            controller.updateMedia(
                                state.media.copy(
                                    speechModel = modelId.trim(),
                                    voice = voice.trim(),
                                    responseFormat = format.trim().ifBlank { null }
                                )
                            )
                        },
                        enabled = modelId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить параметры") }
                }
            }
        }

        Button(
            onClick = {
                controller.setMediaSpeechModelId(modelId)
                controller.updateMedia(
                    state.media.copy(
                        speechModel = modelId.trim(),
                        voice = voice.trim(),
                        responseFormat = format.trim().ifBlank { null }
                    )
                )
                controller.synthesize(text)
            },
            enabled = modelId.isNotBlank() && text.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text(if (state.loading) "Создаю…" else "Создать аудио") }

        state.speechFile?.let { file ->
            Text(
                "Готово: ${file.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        state.status?.takeIf { it.isNotBlank() }?.let { ToolStatusText(it) }
    }
}

@Composable
private fun VideoFloatingPanel(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var modelId by remember(state.media.videoModel) { mutableStateOf(state.media.videoModel) }
    var settingsOpen by remember { mutableStateOf(false) }
    val refs = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        refs.clear()
        refs.addAll(uris.take(4))
    }

    FloatingChatToolPanel(
        title = "Видео",
        subtitle = "Создание видео · задача продолжится в фоне",
        onDismiss = onDismiss,
        initialHeight = 550.dp,
        minHeight = 330.dp,
        maxHeightFraction = 0.82f,
        headerAction = {
            IconButton(onClick = { settingsOpen = !settingsOpen }) {
                Icon(Icons.Outlined.Info, contentDescription = "Модель видео")
            }
        }
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Описание видео") },
            minLines = 3,
            maxLines = 8,
            shape = UmnikFieldShape
        )
        FilledTonalButton(
            onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Text(if (refs.isEmpty()) "Добавить референсы" else "Референсы: ${refs.size}")
        }

        if (settingsOpen) {
            UmnikPanel(modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ID модели видео") },
                        singleLine = true,
                        shape = UmnikFieldShape
                    )
                    FilledTonalButton(
                        onClick = { controller.setVideoModelId(modelId) },
                        enabled = modelId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить модель") }
                }
            }
        }

        Button(
            onClick = {
                controller.setVideoModelId(modelId)
                controller.submitVideo(prompt, refs.toList())
                prompt = ""
                refs.clear()
            },
            enabled = modelId.isNotBlank() && prompt.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Создать видео") }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Последние задачи", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = controller::refreshJobs) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.videos.isEmpty()) {
                item { Text("Пока нет видео-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.videos.take(12), key = { it.id }) { job ->
                    UmnikPanel {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(job.modelId.substringAfterLast('/'), fontWeight = FontWeight.Medium)
                            Text(videoStatus(job.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        state.status?.takeIf { it.isNotBlank() }?.let { ToolStatusText(it) }
    }
}

private data class FloatingBatchTask(
    val text: String = "",
    val files: List<Uri> = emptyList()
)

@Composable
private fun BatchFloatingPanel(
    state: OpenRouterHubState,
    controller: OpenRouterHubController,
    onDismiss: () -> Unit
) {
    var modelId by remember(state.media.batchModel) { mutableStateOf(state.media.batchModel) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fileTarget by remember { mutableStateOf<Int?>(null) }
    val tasks = remember { mutableStateListOf(FloatingBatchTask()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val index = fileTarget
        if (index != null && index in tasks.indices) {
            tasks[index] = tasks[index].copy(files = uris.take(6))
        }
        fileTarget = null
    }
    val ready = tasks.count { it.text.isNotBlank() }

    FloatingChatToolPanel(
        title = "Пакет задач",
        subtitle = "Независимые задания Batch · результаты вернутся в чат",
        onDismiss = onDismiss,
        initialHeight = 610.dp,
        minHeight = 360.dp,
        maxHeightFraction = 0.86f,
        headerAction = {
            IconButton(onClick = { settingsOpen = !settingsOpen }) {
                Icon(Icons.Outlined.Info, contentDescription = "Модель Batch")
            }
        }
    ) {
        if (settingsOpen) {
            UmnikPanel {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ID Batch-модели") },
                        singleLine = true,
                        shape = UmnikFieldShape
                    )
                    FilledTonalButton(
                        onClick = { controller.setBatchModelId(modelId) },
                        enabled = modelId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить модель") }
                }
            }
            Spacer(Modifier.padding(top = 3.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 6.dp)
        ) {
            items(tasks.size, key = { it }) { index ->
                val task = tasks[index]
                UmnikPanel {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        OutlinedTextField(
                            value = task.text,
                            onValueChange = { tasks[index] = task.copy(text = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Задача ${index + 1}") },
                            minLines = 2,
                            maxLines = 6,
                            shape = UmnikFieldShape
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                fileTarget = index
                                picker.launch(arrayOf("*/*"))
                            }) {
                                Text(if (task.files.isEmpty()) "+ Файлы" else "Файлы: ${task.files.size}")
                            }
                            Spacer(Modifier.weight(1f))
                            if (tasks.size > 1) {
                                TextButton(onClick = { tasks.removeAt(index) }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = { tasks.add(FloatingBatchTask()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Добавить задачу") }
            }
        }

        Button(
            onClick = {
                controller.setBatchModelId(modelId)
                val readyTasks = tasks.filter { it.text.isNotBlank() }
                controller.submitBatch(
                    readyTasks.joinToString("\n---\n") { it.text.trim() },
                    readyTasks.map { it.files }
                )
            },
            enabled = modelId.endsWith(":batch", ignoreCase = true) && ready > 0 && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Запустить пакет · $ready") }

        state.batches.filterNot { it.status.terminal }.maxByOrNull { it.updatedAt }?.let { active ->
            ToolRunningStatus(
                if (active.completedItems > 0) {
                    "${batchStatus(active.status)} · ${active.completedItems}/${active.totalItems}"
                } else {
                    "${batchStatus(active.status)} · ${active.totalItems} заданий"
                }
            )
        }
        state.status?.takeIf { it.isNotBlank() }?.let { ToolStatusText(it) }
    }
}

@Composable
private fun ToolRunningStatus(text: String) {
    UmnikPanel(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(9.dp))
            Text(text, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ToolStatusText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private fun batchStatus(status: BatchJobStatus): String = when (status) {
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

private fun videoStatus(status: VideoJobStatus): String = when (status) {
    VideoJobStatus.PENDING -> "Подготовка"
    VideoJobStatus.QUEUED -> "В очереди"
    VideoJobStatus.IN_PROGRESS -> "Генерация"
    VideoJobStatus.COMPLETED -> "Готово"
    VideoJobStatus.FAILED -> "Ошибка"
    VideoJobStatus.CANCELLED -> "Отменено"
    VideoJobStatus.EXPIRED -> "Истекло"
    VideoJobStatus.UNKNOWN -> "Неизвестно"
}
