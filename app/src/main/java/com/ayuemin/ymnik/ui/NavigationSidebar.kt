package com.ayuemin.ymnik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
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
    onOpenProject: (String) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
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
    val chats = state.chats
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
        .filter { chat ->
            normalized.isBlank() ||
                chat.title.contains(normalized, ignoreCase = true) ||
                chat.projectId?.let { projectId ->
                    state.projects.firstOrNull { it.id == projectId }?.name?.contains(normalized, ignoreCase = true)
                } == true
        }

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
                .fillMaxWidth(0.72f),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp
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
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Поиск по чатам")
                    }
                }

                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по чатам") }
                    )
                }

                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onNewChat()
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Новый чат")
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
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
                        SidebarSectionTitle("История чатов")
                    }

                    if (chats.isEmpty()) {
                        item {
                            Text(
                                if (normalized.isBlank()) "Чатов пока нет" else "Ничего не найдено",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                onDelete = { deleteTarget = chat }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        TextButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = null, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Меню", maxLines = 1)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Проекты") },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenProjects()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Навыки") },
                                leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSkills()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                }
                            )
                            if (state.messages.isNotEmpty()) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Очистить текущий чат") },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        clearConfirm = true
                                    }
                                )
                            }
                        }
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
        color = MaterialTheme.colorScheme.primary
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    project.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
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

@Composable
private fun SidebarChatRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val projectName = chat.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (chat.id == state.currentChatId)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else Color.Transparent
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        chat.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        buildString {
                            if (projectName != null) append("$projectName · ")
                            append(sidebarDate(chat.updatedAt))
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = { vm.setChatFavorite(chat.id, !chat.isFavorite) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (chat.isFavorite) "Открепить чат" else "Закрепить чат",
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = !state.isLoading,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить чат", modifier = Modifier.size(19.dp))
            }
        }
    }
}

private fun sidebarDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))
