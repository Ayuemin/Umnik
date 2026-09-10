package com.ayuemin.ymnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var editorId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    val chats = state.chats.sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = chats.filter { it.isFavorite }
    val others = chats.filterNot { it.isFavorite }

    FullScreenPanel(title = "История чатов", onBack = onDismiss) {
        FilledTonalButton(
            onClick = {
                vm.createChat()
                onDismiss()
            },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый чат")
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (favorites.isNotEmpty()) {
                item { SectionTitle("Избранные") }
                items(favorites, key = { it.id }) { chat ->
                    ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle(if (favorites.isEmpty()) "Все чаты" else "Остальные") }
                items(others, key = { it.id }) { chat ->
                    ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                }
            }
        }
    }

    state.chats.firstOrNull { it.id == editorId }?.let { chat ->
        ChatProfileDialog(chat, vm) { editorId = null }
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${chat.title}» будет удалён. Его сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ChatHubRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val projectName = chat.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                vm.switchChat(chat.id)
                onDismiss()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    chat.title,
                    fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (projectName != null) append("$projectName · ")
                        append("${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { vm.setChatFavorite(chat.id, !chat.isFavorite) }) {
            Icon(
                if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (chat.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Настроить чат")
        }
        IconButton(onClick = onDelete, enabled = !state.isLoading) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить чат")
        }
    }
    HorizontalDivider()
}

@Composable
private fun ChatProfileDialog(chat: ChatSession, vm: ChatViewModel, onDismiss: () -> Unit) {
    var title by remember(chat.id) { mutableStateOf(chat.title) }
    var role by remember(chat.id) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var prompt by remember(chat.id) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id) { mutableStateOf(chat.isFavorite) }

    FullScreenPanel(title = "Настройки диалога", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
            }
            item {
                OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Роль") }, placeholder = { Text("Например: главный редактор") })
            }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Мастер-промпт") },
                    placeholder = { Text("Постоянная инструкция только для этого чата") },
                    minLines = 6,
                    maxLines = 16
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
        }
        FilledTonalButton(
            onClick = {
                vm.updateChatProfile(chat.id, title, role, prompt)
                vm.setChatFavorite(chat.id, favorite)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("Сохранить")
        }
    }
}

@Composable
fun ProjectsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var openProjectId by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    val projects = state.projects.sortedWith(compareByDescending<Project> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = projects.filter { it.isFavorite }
    val others = projects.filterNot { it.isFavorite }

    FullScreenPanel(title = "Проекты", onBack = onDismiss) {
        FilledTonalButton(
            onClick = { createOpen = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый проект")
        }

        if (projects.isEmpty()) {
            Text(
                "Проект объединяет мастер-промпт, роль, постоянные файлы, навыки и несколько отдельных чатов.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (favorites.isNotEmpty()) {
                    item { SectionTitle("Избранные") }
                    items(favorites, key = { it.id }) { project ->
                        ProjectRow(project, state, vm) { openProjectId = project.id }
                    }
                }
                if (others.isNotEmpty()) {
                    item { SectionTitle(if (favorites.isEmpty()) "Все проекты" else "Остальные") }
                    items(others, key = { it.id }) { project ->
                        ProjectRow(project, state, vm) { openProjectId = project.id }
                    }
                }
            }
        }
    }

    if (createOpen) {
        ProjectEditorDialog(project = null, onDismiss = { createOpen = false }) { name, role, prompt, favorite ->
            openProjectId = vm.createProject(name, role, prompt, favorite)
            createOpen = false
        }
    }

    state.projects.firstOrNull { it.id == openProjectId }?.let { project ->
        ProjectDetailDialog(
            project = project,
            state = state,
            vm = vm,
            onDismiss = { openProjectId = null },
            onOpenChat = { chatId ->
                vm.switchChat(chatId)
                openProjectId = null
                onDismiss()
            },
            onCreateChat = {
                vm.createChat(project.id)
                openProjectId = null
                onDismiss()
            }
        )
    }
}

@Composable
private fun ProjectRow(project: Project, state: UiState, vm: ChatViewModel, onOpen: () -> Unit) {
    val count = state.chats.count { it.projectId == project.id }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$count чатов · ${project.files.size} файлов · ${project.skillIds.size} навыков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { vm.setProjectFavorite(project.id, !project.isFavorite) }) {
            Icon(
                if (project.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (project.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ProjectDetailDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCreateChat: () -> Unit
) {
    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })

    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addProjectFile(project.id, it) }
    }
    val importPrompt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importProjectPromptFile(project.id, it) }
    }

    FullScreenPanel(title = project.name, onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (project.role.isNotBlank()) {
                item { Text("Роль: ${project.role}", style = MaterialTheme.typography.bodyMedium) }
            }
            if (project.masterPrompt.isNotBlank()) {
                item {
                    Text(
                        project.masterPrompt,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCreateChat, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Новый чат")
                    }
                    IconButton(onClick = { editOpen = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Настройки проекта")
                    }
                }
            }

            item { SectionTitle("Чаты проекта") }
            if (projectChats.isEmpty()) {
                item { Text("Пока нет диалогов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(projectChats, key = { it.id }) { chat ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpenChat(chat.id) }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { vm.setChatFavorite(chat.id, !chat.isFavorite) }) {
                            Icon(
                                if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Избранное"
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            item { SectionTitle("Навыки проекта") }
            if (state.skills.isEmpty()) {
                item { Text("Импортируйте навыки через меню «Навыки»", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.skills, key = { it.id }) { skill ->
                            FilterChip(
                                selected = skill.id in project.skillIds,
                                onClick = { vm.toggleProjectSkill(project.id, skill.id) },
                                label = { Text(skill.name, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(17.dp)) }
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Постоянные файлы") }
            if (project.files.isEmpty()) {
                item { Text("Нет файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(project.files, key = { it.id }) { file ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(projectSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deleteProjectFile(project.id, file.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { addFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Добавить постоянные файлы")
                }
            }
            item {
                TextButton(
                    onClick = { importPrompt.launch(arrayOf("text/plain", "text/markdown", "application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Загрузить мастер-промпт из файла")
                }
            }
            item {
                TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Удалить проект")
                }
            }
        }
    }

    if (editOpen) {
        ProjectEditorDialog(project, { editOpen = false }) { name, role, prompt, favorite ->
            vm.updateProject(project.id, name, role, prompt, favorite)
            editOpen = false
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить проект?") },
            text = { Text("Мастер-промпт и постоянные файлы проекта будут удалены. Его чаты сохранятся и станут обычными чатами.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(project.id)
                    deleteConfirm = false
                    onDismiss()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var role by remember(project?.id) { mutableStateOf(project?.role.orEmpty()) }
    var prompt by remember(project?.id) { mutableStateOf(project?.masterPrompt.orEmpty()) }
    var favorite by remember(project?.id) { mutableStateOf(project?.isFavorite ?: false) }

    FullScreenPanel(title = if (project == null) "Новый проект" else "Настройки проекта", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
            }
            item {
                OutlinedTextField(
                    role,
                    { role = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Роль") },
                    placeholder = { Text("Например: главный редактор IT-канала") }
                )
            }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Мастер-промпт") },
                    placeholder = { Text("Эта инструкция автоматически добавляется ко всем чатам проекта") },
                    minLines = 8,
                    maxLines = 18
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
        }
        FilledTonalButton(
            onClick = { onSave(name, role, prompt, favorite) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("Сохранить")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 9.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun projectDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun projectSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
