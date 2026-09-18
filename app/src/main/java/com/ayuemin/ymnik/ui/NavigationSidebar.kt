package com.ayuemin.ymnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavigationSidebar(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onOpenProjects: () -> Unit,
    onCreateProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    var settingsTarget by remember { mutableStateOf<ChatSession?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val projects = state.projects.sortedWith(
        compareByDescending<Project> { it.isFavorite }.thenByDescending { it.updatedAt }
    )
    val normalized = query.trim()
    val filteredChats = state.chats
        .filter { it.projectId == null }
        .sortedByDescending { it.updatedAt }
        .filter { chat ->
            normalized.isBlank() || chat.title.contains(normalized, ignoreCase = true)
        }
    val favoriteChats = filteredChats.filter { it.isFavorite }
    val chats = filteredChats.filterNot { it.isFavorite }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(onClick = onDismiss)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.84f),
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Umnik",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    UmnikCircleAction(
                        icon = Icons.Outlined.Search,
                        contentDescription = "Поиск по чатам",
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        }
                    )
                }

                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по чатам") },
                        shape = UmnikFieldShape
                    )
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    item {
                        FilledTonalButton(
                            onClick = onCreateProject,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            shape = UmnikFieldShape
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Создать проект")
                        }
                    }
                    item { SidebarSectionTitle("Проекты") }
                    if (projects.isEmpty()) {
                        item {
                            Text(
                                "Проектов пока нет",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(projects, key = { "project-${it.id}" }) { project ->
                            SidebarProjectRow(project, state, vm) { onOpenProject(project.id) }
                        }
                    }

                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        FilledTonalButton(
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                onNewChat()
                            },
                            enabled = !state.isLoading || state.requestActive,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            shape = UmnikFieldShape
                        ) {
                            Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Новый чат")
                        }
                    }

                    if (favoriteChats.isNotEmpty()) {
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            SidebarSectionTitle("Избранные")
                        }
                        items(favoriteChats, key = { "favorite-${it.id}" }) { chat ->
                            SidebarChatRow(
                                chat = chat,
                                state = state,
                                vm = vm,
                                onOpen = {
                                    vm.switchChat(chat.id)
                                    onDismiss()
                                },
                                onDelete = { deleteTarget = chat },
                                onSettings = { settingsTarget = chat },
                                onRename = {
                                    renameTarget = chat
                                    renameValue = chat.title
                                }
                            )
                        }
                    }

                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SidebarSectionTitle("История чатов")
                    }

                    if (chats.isEmpty()) {
                        if (favoriteChats.isEmpty()) {
                            item {
                                Text(
                                    if (normalized.isBlank()) "Чатов пока нет" else "Ничего не найдено",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(chats, key = { "chat-${it.id}" }) { chat ->
                            SidebarChatRow(
                                chat = chat,
                                state = state,
                                vm = vm,
                                onOpen = {
                                    vm.switchChat(chat.id)
                                    onDismiss()
                                },
                                onDelete = { deleteTarget = chat },
                                onSettings = { settingsTarget = chat },
                                onRename = {
                                    renameTarget = chat
                                    renameValue = chat.title
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = umnikDividerColor())
                UmnikPanel(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onOpenSettings()
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Настройки", maxLines = 1, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${chat.title}» будет удалён. Сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
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
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateChatProfile(
                            chat.id,
                            renameValue,
                            chat.assignedRole.orEmpty(),
                            chat.masterPrompt.orEmpty()
                        )
                        renameTarget = null
                    },
                    enabled = renameValue.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }

    settingsTarget?.let { chat ->
        RegularChatSettingsDialog(
            chat = chat,
            state = state,
            vm = vm,
            onDismiss = { settingsTarget = null }
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Очистить текущий чат?") },
            text = { Text("Переписка и файлы контекста текущего чата будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    onClearChat()
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SidebarSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SidebarProjectRow(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit
) {
    val count = state.chats.count { it.projectId == project.id }
    val activeProjectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = UmnikItemShape,
        color = if (activeProjectId == project.id)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else Color.Transparent
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (activeProjectId == project.id) FontWeight.SemiBold else FontWeight.Medium
                    )
                    Text(
                        "$count чатов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = { vm.setProjectFavorite(project.id, !project.isFavorite) },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    if (project.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (project.isFavorite) "Открепить проект" else "Закрепить проект",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarChatRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    onRename: () -> Unit
) {
    var actionsOpen by remember(chat.id) { mutableStateOf(false) }
    val projectName = chat.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { actionsOpen = true }
            ),
        shape = UmnikItemShape,
        color = if (chat.id == state.currentChatId)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    chat.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    buildString {
                        if (vm.isChatRequestActive(chat.id)) append("Отвечает · ")
                        if (projectName != null) append("$projectName · ")
                        append(sidebarDate(chat.updatedAt))
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(
                    onClick = { actionsOpen = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с чатом", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (chat.isFavorite) "Убрать из избранного" else "В избранное") },
                        leadingIcon = { Icon(if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null) },
                        onClick = {
                            actionsOpen = false
                            vm.setChatFavorite(chat.id, !chat.isFavorite)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        enabled = !state.isLoading,
                        onClick = {
                            actionsOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки чата") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        enabled = !state.isLoading,
                        onClick = {
                            actionsOpen = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        enabled = !state.isLoading,
                        onClick = {
                            actionsOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RegularChatSettingsDialog(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var title by remember(chat.id, chat.title) { mutableStateOf(chat.title) }
    var role by remember(chat.id, chat.assignedRole) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var prompt by remember(chat.id, chat.masterPrompt) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id, chat.isFavorite) { mutableStateOf(chat.isFavorite) }

    FullScreenPanel(title = "Настройки чата", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                UmnikPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(100) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true,
                            shape = UmnikFieldShape
                        )
                        OutlinedTextField(
                            value = role,
                            onValueChange = { role = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Роль чата") },
                            placeholder = { Text("Например: главный редактор") },
                            shape = UmnikFieldShape
                        )
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Инструкция чата") },
                            placeholder = { Text("Постоянная инструкция только для этого чата") },
                            minLines = 5,
                            maxLines = 14,
                            shape = UmnikFieldShape
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Избранное", fontWeight = FontWeight.Medium)
                                Text(
                                    "Показывать чат выше остальных",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = favorite, onCheckedChange = { favorite = it })
                        }
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, title, role, prompt)
                        vm.setChatFavorite(chat.id, favorite)
                    },
                    enabled = title.isNotBlank() && !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = UmnikFieldShape
                ) {
                    Text("Сохранить основные настройки")
                }
            }
            item {
                Text(
                    "Модель, размышление, веб-поиск, навыки и разовые вложения остаются в меню + текущего чата. Здесь хранятся постоянные настройки самого чата.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    }
}

private fun sidebarDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))