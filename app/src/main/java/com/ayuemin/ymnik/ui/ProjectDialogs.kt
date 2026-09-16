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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectFile
import com.ayuemin.ymnik.model.ProjectStage
import com.ayuemin.ymnik.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var editorId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    val chats = state.chats.filter { it.projectId == null }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
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

        TextButton(
            onClick = { clearAllConfirm = true },
            enabled = !state.isLoading && chats.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Очистить все чаты")
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
        ChatProfileDialog(chat, state, vm) { editorId = null }
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

    if (clearAllConfirm) {
        AlertDialog(
            onDismissRequest = { clearAllConfirm = false },
            title = { Text("Очистить всю историю чатов?") },
            text = {
                Text(
                    "Будут удалены все ${state.chats.size} чатов и их файлы контекста. Сгенерированные изображения и экспорт в хранилище Umnik останутся."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllChats()
                    clearAllConfirm = false
                    onDismiss()
                }) { Text("Очистить все") }
            },
            dismissButton = {
                TextButton(onClick = { clearAllConfirm = false }) { Text("Отмена") }
            }
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
private fun ChatProfileDialog(chat: ChatSession, state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
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
            item { ChatContextSettingsSection(chat, state, vm) }
            item {
                KnowledgeBaseSection(
                    kind = KnowledgeOwnerKind.CHAT,
                    ownerId = chat.id,
                    state = state,
                    vm = vm,
                    title = "База знаний чата"
                )
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
fun ProjectsDialog(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    initialProjectId: String? = null,
    startCreate: Boolean = false
) {
    var openProjectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var createOpen by remember(startCreate) { mutableStateOf(startCreate) }
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
                "Проект объединяет инструкции, постоянные материалы, рабочие чаты и последовательности этапов.",
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
                    "$count чатов · ${project.files.size} постоянных файлов · ${project.stages.orEmpty().size} этапов",
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
    var settingsOpen by remember(project.id) { mutableStateOf(false) }
    var deleteChatTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var chatSettingsId by remember { mutableStateOf<String?>(null) }
    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { vm.isOrchestratorChat(it.id) }.thenByDescending { it.isFavorite }.thenByDescending { it.updatedAt })

    FullScreenPanel(title = project.name, onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCreateChat, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Новый чат")
                    }
                    FilledTonalButton(onClick = { settingsOpen = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Настройки")
                    }
                }
            }

            item { SectionTitle("Чаты проекта") }
            if (projectChats.isEmpty()) {
                item {
                    Text(
                        "Пока нет чатов. Создайте первый чат проекта кнопкой выше.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(projectChats, key = { it.id }) { chat ->
                    ProjectChatRow(
                        chat = chat,
                        state = state,
                        vm = vm,
                        onOpen = { onOpenChat(chat.id) },
                        onRename = {
                            renameTarget = chat
                            renameValue = chat.title
                        },
                        onSettings = { chatSettingsId = chat.id },
                        onDelete = { deleteChatTarget = chat }
                    )
                }
            }
        }
    }

    renameTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, renameValue, chat.assignedRole.orEmpty(), chat.masterPrompt.orEmpty())
                        renameTarget = null
                    },
                    enabled = renameValue.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }

    deleteChatTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteChatTarget = null },
            title = { Text("Удалить чат из проекта?") },
            text = { Text("«${chat.title}» будет удалён. Сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteChatTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteChatTarget = null }) { Text("Отмена") } }
        )
    }

    state.chats.firstOrNull { it.id == chatSettingsId }?.let { chat ->
        ProjectChatAutomationDialog(
            chat = chat,
            project = project,
            state = state,
            vm = vm,
            onDismiss = { chatSettingsId = null }
        )
    }

    if (settingsOpen) {
        ProjectSettingsDialog(
            project = project,
            state = state,
            vm = vm,
            onDismiss = { settingsOpen = false },
            onProjectDeleted = {
                settingsOpen = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun ProjectChatRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit
) {
    val orchestrator = vm.isOrchestratorChat(chat.id)
    var menuOpen by remember(chat.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(if (orchestrator) "◆ ${chat.title}" else chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (orchestrator) FontWeight.Bold else FontWeight.Medium)
                Text(
                    if (orchestrator) "Управление процессом · ${vm.orchestratorSteps(chat.id).size} шагов" else buildString {
                        append("${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}")
                        if (chat.stages.orEmpty().isNotEmpty()) append(" · ${chat.stages.orEmpty().size} этапов")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с чатом") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!orchestrator) DropdownMenuItem(
                    text = { Text(if (chat.isFavorite) "Убрать из избранного" else "В избранное") },
                    leadingIcon = { Icon(if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null) },
                    onClick = { menuOpen = false; vm.setChatFavorite(chat.id, !chat.isFavorite) }
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Настройки чата") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = { menuOpen = false; onSettings() }
                )
                if (!orchestrator) DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    enabled = !state.isLoading,
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ProjectSettingsDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onProjectDeleted: () -> Unit
) {
    var name by remember(project.id, project.name) { mutableStateOf(project.name) }
    var role by remember(project.id, project.role) { mutableStateOf(project.role) }
    var prompt by remember(project.id, project.masterPrompt) { mutableStateOf(project.masterPrompt) }
    var favorite by remember(project.id, project.isFavorite) { mutableStateOf(project.isFavorite) }
    var stagesExpanded by remember(project.id) { mutableStateOf(true) }
    var skillsExpanded by remember(project.id) { mutableStateOf(false) }
    var filesExpanded by remember(project.id) { mutableStateOf(false) }
    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addProjectFile(project.id, it) }
    }

    FullScreenPanel(title = "Настройки проекта", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
            item {
                FilledTonalButton(
                    onClick = { vm.updateProject(project.id, name, role, prompt, favorite) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить основные настройки") }
            }

            item {
                SettingsExpander(
                    title = "Этапы работы",
                    subtitle = "${project.stages.orEmpty().size} этапов · выполняются строго по порядку",
                    expanded = stagesExpanded,
                    onToggle = { stagesExpanded = !stagesExpanded }
                )
            }
            if (stagesExpanded) {
                item {
                    Text(
                        "У каждого этапа может быть своя модель, свои файлы и выбранные чаты проекта как дополнительный источник контекста.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (project.stages.orEmpty().isEmpty()) {
                    item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(project.stages.orEmpty(), key = { _, stage -> stage.id }) { index, stage ->
                        StageCard(
                            stage = stage,
                            index = index,
                            total = project.stages.orEmpty().size,
                            onMoveUp = { vm.moveProjectStage(project.id, stage.id, -1) },
                            onMoveDown = { vm.moveProjectStage(project.id, stage.id, 1) },
                            onEdit = { editingStage = stage; stageEditorOpen = true },
                            onDelete = { vm.deleteProjectStage(project.id, stage.id) },
                            enabled = !state.isLoading
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { editingStage = null; stageEditorOpen = true },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить этап")
                    }
                }
            }

            item {
                SettingsExpander(
                    title = "Навыки проекта",
                    subtitle = if (project.skillIds.isEmpty()) "Не выбраны" else "Выбрано: ${project.skillIds.size}",
                    expanded = skillsExpanded,
                    onToggle = { skillsExpanded = !skillsExpanded }
                )
            }
            if (skillsExpanded) {
                item {
                    Text(
                        "Отмеченные навыки доступны как быстрый набор внутри чатов этого проекта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.skills.isEmpty()) {
                    item { Text("Добавьте навыки в Настройки → Навыки", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.skills, key = { "project-skill-${it.id}" }) { skill ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(skill.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Switch(
                                checked = skill.id in project.skillIds,
                                onCheckedChange = { vm.toggleProjectSkill(project.id, skill.id) }
                            )
                        }
                    }
                }
            }

            item {
                SettingsExpander(
                    title = "Постоянные файлы",
                    subtitle = if (project.files.isEmpty()) "Нет файлов" else "${project.files.size} файлов",
                    expanded = filesExpanded,
                    onToggle = { filesExpanded = !filesExpanded }
                )
            }
            if (filesExpanded) {
                if (project.files.isEmpty()) {
                    item { Text("Нет файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(project.files, key = { "project-file-${it.id}" }) { file ->
                        FileRow(file = file, onDelete = { vm.deleteProjectFile(project.id, file.id) })
                    }
                }
                item {
                    FilledTonalButton(onClick = { addFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить постоянные файлы")
                    }
                }
            }

            item {
                KnowledgeBaseSection(
                    kind = KnowledgeOwnerKind.PROJECT,
                    ownerId = project.id,
                    state = state,
                    vm = vm,
                    title = "База знаний проекта"
                )
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

    if (stageEditorOpen) {
        StageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            vm = vm,
            ownerChatId = null,
            onDismiss = {
                stageEditorOpen = false
                editingStage = null
            },
            onSave = { title, instruction, modelId, files, sourceChatIds ->
                vm.upsertProjectStage(project.id, editingStage?.id, title, instruction, modelId, files, sourceChatIds)
                stageEditorOpen = false
                editingStage = null
            }
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить проект?") },
            text = { Text("Материалы проекта будут удалены. Его чаты сохранятся как обычные, а индивидуальные этапы этих чатов будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(project.id)
                    deleteConfirm = false
                    onProjectDeleted()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ProjectChatSettingsDialog(
    chat: ChatSession,
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var title by remember(chat.id, chat.title) { mutableStateOf(chat.title) }
    var role by remember(chat.id, chat.assignedRole) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var prompt by remember(chat.id, chat.masterPrompt) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id, chat.isFavorite) { mutableStateOf(chat.isFavorite) }
    var stagesExpanded by remember(chat.id) { mutableStateOf(true) }
    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }

    FullScreenPanel(title = "Настройки чата", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true) }
            item { OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Роль чата") }) }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Инструкция чата") },
                    minLines = 4,
                    maxLines = 12
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, title, role, prompt)
                        vm.setChatFavorite(chat.id, favorite)
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить настройки чата") }
            }

            item {
                SettingsExpander(
                    title = "Этапы этого чата",
                    subtitle = if (chat.stages.orEmpty().isEmpty()) "Не настроены" else "${chat.stages.orEmpty().size} этапов",
                    expanded = stagesExpanded,
                    onToggle = { stagesExpanded = !stagesExpanded }
                )
            }
            if (stagesExpanded) {
                item {
                    Text(
                        "Эти этапы принадлежат только этому чату. Запустить их можно из + → Проект.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (chat.stages.orEmpty().isEmpty()) {
                    item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(chat.stages.orEmpty(), key = { _, stage -> "chat-stage-${stage.id}" }) { index, stage ->
                        StageCard(
                            stage = stage,
                            index = index,
                            total = chat.stages.orEmpty().size,
                            onMoveUp = { vm.moveChatStage(chat.id, stage.id, -1) },
                            onMoveDown = { vm.moveChatStage(chat.id, stage.id, 1) },
                            onEdit = { editingStage = stage; stageEditorOpen = true },
                            onDelete = { vm.deleteChatStage(chat.id, stage.id) },
                            enabled = !state.isLoading
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { editingStage = null; stageEditorOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить этап чата")
                    }
                }
            }
        }
    }

    if (stageEditorOpen) {
        StageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            vm = vm,
            ownerChatId = chat.id,
            onDismiss = {
                stageEditorOpen = false
                editingStage = null
            },
            onSave = { stageTitle, instruction, modelId, files, sourceChatIds ->
                vm.upsertChatStage(chat.id, editingStage?.id, stageTitle, instruction, modelId, files, sourceChatIds)
                stageEditorOpen = false
                editingStage = null
            }
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
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true) }
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
        ) { Text("Сохранить") }
    }
}

@Composable
fun StageEditorDialog(
    project: Project,
    stage: ProjectStage?,
    state: UiState,
    vm: ChatViewModel,
    ownerChatId: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, List<ProjectFile>, Set<String>) -> Unit
) {
    var title by remember(stage?.id) { mutableStateOf(stage?.title.orEmpty()) }
    var instruction by remember(stage?.id) { mutableStateOf(stage?.instruction.orEmpty()) }
    var modelId by remember(stage?.id) { mutableStateOf(stage?.modelId) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var sourcesExpanded by remember(stage?.id) { mutableStateOf(false) }
    var files by remember(stage?.id) { mutableStateOf(stage?.files.orEmpty()) }
    var sourceChatIds by remember(stage?.id) { mutableStateOf(stage?.sourceChatIds.orEmpty()) }
    val originalFileIds = remember(stage?.id) { stage?.files.orEmpty().map { it.id }.toSet() }
    val quickIds = state.quickTextModels.map { ref -> ref.substringAfter('\u001F') }
    val choices = (listOfNotNull(state.currentChatTextModel, state.textModel) + quickIds)
        .filter { it.isNotBlank() && !it.endsWith(":batch", true) }
        .distinct()
    val projectChats = state.chats
        .filter { it.projectId == project.id && it.id != ownerChatId }
        .sortedByDescending { it.updatedAt }

    val addStageFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            vm.importStageDraftFile(project.id, uri)?.let { imported -> files = files + imported }
        }
    }

    fun cancelEditor() {
        vm.cleanupStageDraftFiles(originalFileIds, files)
        onDismiss()
    }

    FullScreenPanel(title = if (stage == null) "Новый этап" else "Изменить этап", onBack = ::cancelEditor) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название этапа") },
                    placeholder = { Text("Например: Собрать выводы") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Инструкция этапа") },
                    placeholder = { Text("Что именно нужно сделать на этом шаге") },
                    minLines = 6,
                    maxLines = 18
                )
            }
            item {
                Text("Модель", fontWeight = FontWeight.SemiBold)
                Box {
                    FilledTonalButton(onClick = { modelMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(modelId?.substringAfterLast('/') ?: "Как в текущем чате", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Как в текущем чате") },
                            onClick = { modelId = null; modelMenuOpen = false }
                        )
                        choices.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { modelId = id; modelMenuOpen = false }
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Файлы этого этапа") }
            if (files.isEmpty()) {
                item { Text("Нет отдельных файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(files, key = { "stage-file-${it.id}" }) { file ->
                    FileRow(file = file, onDelete = {
                        if (file.id !in originalFileIds) vm.deleteStageDraftFile(file)
                        files = files.filterNot { it.id == file.id }
                    })
                }
            }
            item {
                FilledTonalButton(
                    onClick = { addStageFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Прикрепить файл к этапу")
                }
            }

            item {
                SettingsExpander(
                    title = "Чаты проекта как источник",
                    subtitle = if (sourceChatIds.isEmpty()) "Не выбраны" else "Выбрано: ${sourceChatIds.size}",
                    expanded = sourcesExpanded,
                    onToggle = { sourcesExpanded = !sourcesExpanded }
                )
            }
            if (sourcesExpanded) {
                item {
                    Text(
                        "При запуске этап получит актуальную переписку и доступные файлы выбранных чатов. Чаты не запускаются повторно: они используются как источник результата и контекста.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (projectChats.isEmpty()) {
                    item { Text("Других чатов проекта пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(projectChats, key = { "source-chat-${it.id}" }) { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = source.id in sourceChatIds,
                                onCheckedChange = { checked ->
                                    sourceChatIds = if (checked) sourceChatIds + source.id else sourceChatIds - source.id
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${source.messages.size} сообщ. · ${projectDate(source.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { onSave(title, instruction, modelId, files, sourceChatIds) },
            enabled = instruction.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить этап") }
    }
}

@Composable
fun StageCard(
    stage: ProjectStage,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${stage.title}", fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(stage.modelId?.substringAfterLast('/') ?: "Модель чата")
                            if (stage.files.orEmpty().isNotEmpty()) append(" · ${stage.files.orEmpty().size} файлов")
                            if (stage.sourceChatIds.orEmpty().isNotEmpty()) append(" · ${stage.sourceChatIds.orEmpty().size} чатов")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Поднять этап")
                }
                IconButton(onClick = onMoveDown, enabled = enabled && index < total - 1) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Опустить этап")
                }
            }
            Text(
                stage.instruction,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Изменить")
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить")
                }
            }
        }
    }
}

@Composable
fun SettingsExpander(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    ElevatedCard(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }
    }
}

@Composable
private fun FileRow(file: ProjectFile, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(projectSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
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
