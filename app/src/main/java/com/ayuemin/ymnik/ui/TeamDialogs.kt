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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ayuemin.ymnik.model.Team
import com.ayuemin.ymnik.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var editorId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    val chats = state.chats.filter { it.teamId == null }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = chats.filter { it.isFavorite }
    val others = chats.filterNot { it.isFavorite }

    FullScreenPanel(title = "История чатов", onBack = onDismiss) {
        FilledTonalButton(
            onClick = {
                vm.createChat()
                onDismiss()
            },
            enabled = !state.isLoading || state.requestActive,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый чат")
        }

        OutlinedButton(
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
    val teamName = chat.teamId?.let { id -> state.teams.firstOrNull { it.id == id }?.name }
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
                        if (teamName != null) append("$teamName · ")
                        append("${chat.messages.size} сообщ. · ${teamDate(chat.updatedAt)}")
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
fun TeamsDialog(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    initialTeamId: String? = null,
    startCreate: Boolean = false,
    onSpecialistConversationOpened: ((teamId: String, chatId: String) -> Unit)? = null
) {
    var openTeamId by remember(initialTeamId) { mutableStateOf(initialTeamId) }
    var createOpen by remember(startCreate) { mutableStateOf(startCreate) }
    val teams = state.teams.sortedWith(compareByDescending<Team> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = teams.filter { it.isFavorite }
    val others = teams.filterNot { it.isFavorite }

    FullScreenPanel(title = "Команды", onBack = onDismiss) {
        FilledTonalButton(
            onClick = { createOpen = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый команда")
        }

        if (teams.isEmpty()) {
            Text(
                "Команда — это кабинет: Оркестратор управляет независимыми специалистами, а каждый специалист хранит свои настройки и знания.",
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
                    items(favorites, key = { it.id }) { team ->
                        TeamRow(team, state, vm) { openTeamId = team.id }
                    }
                }
                if (others.isNotEmpty()) {
                    item { SectionTitle(if (favorites.isEmpty()) "Все команды" else "Остальные") }
                    items(others, key = { it.id }) { team ->
                        TeamRow(team, state, vm) { openTeamId = team.id }
                    }
                }
            }
        }
    }

    if (createOpen) {
        SimpleTeamCreateDialog(
            onDismiss = { createOpen = false },
            onCreate = { name, favorite ->
                openTeamId = vm.createTeam(name = name, favorite = favorite)
                createOpen = false
            }
        )
    }

    state.teams.firstOrNull { it.id == openTeamId }?.let { team ->
        TeamDetailDialog(
            team = team,
            state = state,
            vm = vm,
            onDismiss = { openTeamId = null },
            onConversationOpened = { chatId ->
                openTeamId = null
                if (onSpecialistConversationOpened != null) {
                    onSpecialistConversationOpened(team.id, chatId)
                } else {
                    vm.switchChat(chatId)
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun TeamRow(team: Team, state: UiState, vm: ChatViewModel, onOpen: () -> Unit) {
    val specialistCount = state.specialists.count {
        it.teamId == team.id && it.kind == com.ayuemin.ymnik.model.SpecialistKind.SPECIALIST
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(team.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$specialistCount специалистов · Оркестратор",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { vm.setTeamFavorite(team.id, !team.isFavorite) }) {
            Icon(
                if (team.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (team.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
    }
    HorizontalDivider()
}
