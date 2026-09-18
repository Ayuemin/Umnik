package com.ayuemin.ymnik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
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
import com.ayuemin.ymnik.model.AgentKind
import com.ayuemin.ymnik.model.AgentModelRef
import com.ayuemin.ymnik.model.AgentProfile
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.UiState

/**
 * First visible slice of the agent-first project architecture.
 *
 * The project is only a room. All working configuration lives on AgentProfile.
 */
@Composable
fun AgentProjectDetailDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onConversationOpened: (String) -> Unit
) {
    var editingAgentId by remember(project.id) { mutableStateOf<String?>(null) }
    var projectSettingsOpen by remember(project.id) { mutableStateOf(false) }

    val agents = state.agents.filter { it.projectId == project.id }
    val orchestrator = agents.firstOrNull { it.kind == AgentKind.ORCHESTRATOR }
    val specialists = agents.filter { it.kind == AgentKind.SPECIALIST }.sortedBy { it.name.lowercase() }

    FullScreenPanel(title = project.name, onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            editingAgentId = vm.createAgent(project.id)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Новый агент")
                    }
                    OutlinedButton(
                        onClick = { projectSettingsOpen = true }
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки проекта")
                    }
                }
            }

            item {
                Text(
                    "Оркестратор",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                if (orchestrator == null) {
                    Text(
                        "Оркестратор ещё не создан.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    AgentCard(
                        agent = orchestrator,
                        onSettings = { editingAgentId = orchestrator.id },
                        onOpenChat = {
                            vm.openAgentChat(orchestrator.id)?.let(onConversationOpened)
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Агенты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        specialists.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (specialists.isEmpty()) {
                item {
                    Text(
                        "В кабинете пока никого нет. Создайте первого агента и настройте его роль, модель и рабочую среду.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(specialists, key = { it.id }) { agent ->
                    AgentCard(
                        agent = agent,
                        onSettings = { editingAgentId = agent.id },
                        onOpenChat = {
                            vm.openAgentChat(agent.id)?.let(onConversationOpened)
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Агенты изолированы: настройки обычных чатов, чужие навыки, память и база знаний сюда не наследуются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    editingAgentId?.let { id ->
        state.agents.firstOrNull { it.id == id }?.let { agent ->
            AgentSettingsDialog(
                agent = agent,
                state = state,
                vm = vm,
                onDismiss = { editingAgentId = null },
                onOpenChat = {
                    vm.saveAgent(it)
                    vm.openAgentChat(it.id)?.let(onConversationOpened)
                    editingAgentId = null
                }
            )
        }
    }

    if (projectSettingsOpen) {
        SimpleProjectSettingsDialog(
            project = project,
            vm = vm,
            onDismiss = { projectSettingsOpen = false },
            onDeleted = {
                projectSettingsOpen = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun AgentCard(
    agent: AgentProfile,
    onSettings: () -> Unit,
    onOpenChat: () -> Unit
) {
    val primaryModelId = agent.primaryModel?.modelId
    val primaryModelLabel = primaryModelId
        ?.substringAfter('/')
        ?.ifBlank { primaryModelId }
        ?: "Основная модель не выбрана"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (agent.kind == AgentKind.ORCHESTRATOR)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val roleLine = when {
                        agent.role.isNotBlank() -> agent.role
                        agent.kind == AgentKind.ORCHESTRATOR -> "Руководитель проекта"
                        else -> "Роль не задана"
                    }
                    Text(
                        roleLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Настройки агента")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                primaryModelLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (agent.primaryModel == null)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (agent.kind == AgentKind.ORCHESTRATOR) "Открыть Оркестратора" else "Перейти в чат")
            }
        }
    }
}

@Composable
private fun AgentSettingsDialog(
    agent: AgentProfile,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (AgentProfile) -> Unit
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var role by remember(agent.id) { mutableStateOf(agent.role) }
    var instruction by remember(agent.id) { mutableStateOf(agent.instruction) }
    var primaryModel by remember(agent.id) { mutableStateOf(agent.primaryModel?.modelId.orEmpty()) }
    var quickModelsText by remember(agent.id) {
        mutableStateOf(agent.quickModels.joinToString("\n") { it.modelId })
    }
    var contextModel by remember(agent.id) { mutableStateOf(agent.contextModel?.modelId.orEmpty()) }
    var memoryEmbedding by remember(agent.id) { mutableStateOf(agent.memoryEmbeddingModel?.modelId.orEmpty()) }
    var reasoningEnabled by remember(agent.id) { mutableStateOf(agent.reasoningEnabled) }
    var reasoningEffort by remember(agent.id) { mutableStateOf(agent.reasoningEffort) }
    var webSearch by remember(agent.id) { mutableStateOf(agent.webSearchEnabled) }
    var pickerTarget by remember(agent.id) { mutableStateOf<ModelPickerTarget?>(null) }
    var deleteConfirm by remember(agent.id) { mutableStateOf(false) }
    var skillEditorOpen by remember(agent.id) { mutableStateOf(false) }
    var skillName by remember(agent.id) { mutableStateOf("") }
    var skillBody by remember(agent.id) { mutableStateOf("") }
    val ownedSkills = vm.agentSkills(agent.id)
    val ownedFiles = vm.agentFiles(agent.id)
    val agentFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addAgentFiles(agent.id, uris)
    }
    val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAgentSkillFile(agent.id, it) }
    }
    val skillFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importAgentSkillTree(agent.id, it) }
    }

    LaunchedEffect(agent.id) {
        if (state.modelCatalogConnectionId != "openrouter") {
            vm.loadConnectionModels("openrouter")
        }
    }

    fun ref(modelId: String): AgentModelRef? =
        modelId.trim().takeIf { it.isNotBlank() }?.let { AgentModelRef("openrouter", it) }

    fun buildProfile(): AgentProfile = agent.copy(
        name = name.trim().ifBlank {
            if (agent.kind == AgentKind.ORCHESTRATOR) "Оркестратор" else "Агент"
        },
        role = role.trim(),
        instruction = instruction.trim(),
        primaryModel = ref(primaryModel),
        quickModels = quickModelsText
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map { AgentModelRef("openrouter", it) }
            .toList(),
        contextModel = ref(contextModel),
        memoryEmbeddingModel = ref(memoryEmbedding),
        knowledgeBase = agent.knowledgeBase,
        reasoningEnabled = reasoningEnabled,
        reasoningEffort = reasoningEffort,
        webSearchEnabled = webSearch
    )

    FullScreenPanel(
        title = if (agent.kind == AgentKind.ORCHESTRATOR) "Оркестратор" else "Настройки агента",
        onBack = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Роль") },
                    supportingText = {
                        Text("Коротко опишите, чем занимается агент и какие задачи ему поручать. Это видит Оркестратор.")
                    },
                    minLines = 2
                )
            }
            item {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Инструкция / личность") },
                    minLines = 5
                )
            }

            item { AgentSettingsSectionTitle("Модели") }

            item {
                ModelField(
                    label = "Основная модель",
                    value = primaryModel,
                    onValueChange = { primaryModel = it },
                    onPick = { pickerTarget = ModelPickerTarget.PRIMARY }
                )
            }
            item {
                OutlinedTextField(
                    value = quickModelsText,
                    onValueChange = { quickModelsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Быстрые модели") },
                    supportingText = { Text("По одной модели в строке. Эти настройки принадлежат только агенту.") },
                    minLines = 2
                )
            }
            item {
                ModelField(
                    label = "Модель контекста",
                    value = contextModel,
                    onValueChange = { contextModel = it },
                    onPick = { pickerTarget = ModelPickerTarget.CONTEXT }
                )
            }
            item {
                OutlinedTextField(
                    value = memoryEmbedding,
                    onValueChange = { memoryEmbedding = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Embeddings памяти") },
                    singleLine = true
                )
            }

            item { AgentSettingsSectionTitle("Работа модели") }

            item {
                ToggleSettingRow(
                    title = "Размышление",
                    subtitle = "Настройка действует только для этого агента",
                    checked = reasoningEnabled,
                    onCheckedChange = { reasoningEnabled = it }
                )
            }
            if (reasoningEnabled) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReasoningEffort.entries.forEach { effort ->
                            val selected = reasoningEffort == effort
                            OutlinedButton(
                                onClick = { reasoningEffort = effort },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    when (effort) {
                                        ReasoningEffort.MINIMAL -> "Min"
                                        ReasoningEffort.LOW -> "Low"
                                        ReasoningEffort.MEDIUM -> "Med"
                                        ReasoningEffort.HIGH -> "High"
                                        ReasoningEffort.XHIGH -> "XH"
                                    },
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            item {
                ToggleSettingRow(
                    title = "Поиск в сети",
                    subtitle = "Только для этого агента",
                    checked = webSearch,
                    onCheckedChange = { webSearch = it }
                )
            }

            item { AgentSettingsSectionTitle("Навыки") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            skillName = ""
                            skillBody = ""
                            skillEditorOpen = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Создать")
                    }
                    OutlinedButton(
                        onClick = { skillFilePicker.launch(arrayOf("text/*", "application/json")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Файл")
                    }
                    OutlinedButton(
                        onClick = { skillFolderPicker.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Папка")
                    }
                }
            }
            if (ownedSkills.isEmpty()) {
                item {
                    Text(
                        "Навыков у этого агента пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(ownedSkills, key = { "agent-skill-${it.id}" }) { skill ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${skill.files.size} файлов",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = skill.id in agent.skillIds,
                            onCheckedChange = { enabled ->
                                vm.setAgentSkillEnabled(agent.id, skill.id, enabled)
                            }
                        )
                        IconButton(onClick = { vm.deleteAgentSkill(agent.id, skill.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                        }
                    }
                    HorizontalDivider()
                }
            }

            item { AgentSettingsSectionTitle("Файлы агента") }
            item {
                FilledTonalButton(
                    onClick = { agentFilesPicker.launch(arrayOf("*/*")) },
                    enabled = !state.isLoading && !state.requestActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить постоянные файлы")
                }
            }
            if (ownedFiles.isEmpty()) {
                item {
                    Text(
                        "Постоянных файлов у агента пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(ownedFiles, key = { "agent-file-${it.id}" }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${file.size / 1024} КБ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deleteAgentFile(agent.id, file.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл агента")
                        }
                    }
                    HorizontalDivider()
                }
            }

            item { AgentSettingsSectionTitle("База знаний") }
            item {
                KnowledgeBaseSection(
                    kind = com.ayuemin.ymnik.model.KnowledgeOwnerKind.AGENT,
                    ownerId = agent.id,
                    state = state,
                    vm = vm,
                    title = "База знаний агента"
                )
            }

            item { AgentSettingsSectionTitle("Локальная среда") }
            item {
                Text(
                    "Навыки, файлы, база знаний, память и разговоры хранятся в локальной папке этого агента и не наследуются другими агентами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Button(
                    onClick = {
                        val saved = buildProfile()
                        vm.saveAgent(saved)
                        onOpenChat(saved)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && primaryModel.isNotBlank()
                ) {
                    Text("Сохранить и перейти в чат")
                }
            }

            item {
                OutlinedButton(
                    onClick = { vm.saveAgent(buildProfile()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить")
                }
            }

            if (agent.kind == AgentKind.SPECIALIST) {
                item {
                    TextButton(
                        onClick = { deleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Удалить агента")
                    }
                }
            }
        }
    }

    pickerTarget?.let { target ->
        AgentModelPickerDialog(
            state = state,
            title = when (target) {
                ModelPickerTarget.PRIMARY -> "Основная модель"
                ModelPickerTarget.CONTEXT -> "Модель контекста"
            },
            onDismiss = { pickerTarget = null },
            onSelect = { id ->
                when (target) {
                    ModelPickerTarget.PRIMARY -> primaryModel = id
                    ModelPickerTarget.CONTEXT -> contextModel = id
                }
                pickerTarget = null
            }
        )
    }

    if (skillEditorOpen) {
        AlertDialog(
            onDismissRequest = { skillEditorOpen = false },
            title = { Text("Новый навык") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = skillName,
                        onValueChange = { skillName = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = skillBody,
                        onValueChange = { skillBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Инструкция навыка") },
                        minLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (vm.createAgentSkill(agent.id, skillName, skillBody) != null) {
                            skillEditorOpen = false
                        }
                    },
                    enabled = skillBody.isNotBlank()
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { skillEditorOpen = false }) { Text("Отмена") }
            }
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить агента?") },
            text = { Text("Будут удалены его разговоры и локальное рабочее хранилище.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAgent(agent.id)
                    deleteConfirm = false
                    onDismiss()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

private enum class ModelPickerTarget { PRIMARY, CONTEXT }

@Composable
private fun AgentModelPickerDialog(
    state: UiState,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val models = state.modelCatalog
        .filter { ModelCategory.TEXT in it.categories }
        .filter { info -> query.isBlank() || info.id.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск модели") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                if (state.isLoading && state.modelCatalog.isEmpty()) {
                    Text("Загружаю каталог…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(360.dp)
                    ) {
                        items(models, key = { it.id }) { model ->
                            TextButton(
                                onClick = { onSelect(model.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        model.id.substringAfter('/').ifBlank { model.id },
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun ModelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true
        )
        OutlinedButton(onClick = onPick) {
            Text("Выбрать из каталога")
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AgentSettingsSectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        HorizontalDivider()
    }
}

@Composable
fun SimpleProjectCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, favorite: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый проект") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название кабинета") },
                    singleLine = true
                )
                ToggleSettingRow(
                    title = "Закрепить",
                    subtitle = "Показывать проект выше остальных",
                    checked = favorite,
                    onCheckedChange = { favorite = it }
                )
                Text(
                    "Инструкции, навыки и знания настраиваются у каждого агента отдельно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, favorite) },
                enabled = name.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SimpleProjectSettingsDialog(
    project: Project,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    var favorite by remember(project.id) { mutableStateOf(project.isFavorite) }
    var deleteConfirm by remember(project.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кабинет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    singleLine = true
                )
                ToggleSettingRow(
                    title = "Закрепить",
                    subtitle = "Показывать проект выше остальных",
                    checked = favorite,
                    onCheckedChange = { favorite = it }
                )
                Text(
                    "У проекта нет общей инструкции, навыков или базы знаний. Это только кабинет для Оркестратора и агентов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { deleteConfirm = true }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить проект")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.updateProject(project.id, name, "", "", favorite)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить проект?") },
            text = { Text("Будут удалены Оркестратор, все агенты и их локальные рабочие хранилища.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(project.id)
                    deleteConfirm = false
                    onDeleted()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") }
            }
        )
    }
}
