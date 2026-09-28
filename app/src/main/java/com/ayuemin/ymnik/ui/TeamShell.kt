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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.ayuemin.ymnik.model.SpecialistKind
import com.ayuemin.ymnik.model.SpecialistModelRef
import com.ayuemin.ymnik.model.SpecialistProfile
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.Team
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.WebSearchPreset
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchEngine

/**
 * First visible slice of the specialist-first team architecture.
 *
 * The team is only a room. All working configuration lives on SpecialistProfile.
 */
@Composable
fun TeamDetailDialog(
    team: Team,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onConversationOpened: (String) -> Unit
) {
    var editingSpecialistId by remember(team.id) { mutableStateOf<String?>(null) }
    var teamSettingsOpen by remember(team.id) { mutableStateOf(false) }

    val teamMembers = state.specialists.filter { it.teamId == team.id }
    val orchestrator = teamMembers.firstOrNull { it.kind == SpecialistKind.ORCHESTRATOR }
    val specialists = teamMembers.filter { it.kind == SpecialistKind.SPECIALIST }.sortedBy { it.name.lowercase() }

    FullScreenPanel(title = team.name, onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            editingSpecialistId = vm.createSpecialist(team.id)
                        },
                        modifier = Modifier.weight(1f),
                        shape = UmnikFieldShape
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Новый специалист")
                    }
                    UmnikCircleAction(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Настройки команды",
                        onClick = { teamSettingsOpen = true }
                    )
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
                    SpecialistCard(
                        specialist = orchestrator,
                        onSettings = { editingSpecialistId = orchestrator.id },
                        onOpenChat = {
                            vm.openSpecialistChat(orchestrator.id)?.let(onConversationOpened)
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
                        "Специалисты",
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
                        "В кабинете пока никого нет. Создайте первого специалиста и настройте его роль, модель и рабочую среду.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(specialists, key = { it.id }) { specialist ->
                    SpecialistCard(
                        specialist = specialist,
                        onSettings = { editingSpecialistId = specialist.id },
                        onOpenChat = {
                            vm.openSpecialistChat(specialist.id)?.let(onConversationOpened)
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Специалисты изолированы: настройки обычных чатов, чужие навыки, память и база знаний сюда не наследуются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    editingSpecialistId?.let { id ->
        state.specialists.firstOrNull { it.id == id }?.let { specialist ->
            SpecialistSettingsDialog(
                specialist = specialist,
                state = state,
                vm = vm,
                onDismiss = { editingSpecialistId = null },
                onOpenChat = {
                    vm.saveSpecialist(it)
                    vm.openSpecialistChat(it.id)?.let(onConversationOpened)
                    editingSpecialistId = null
                }
            )
        }
    }

    if (teamSettingsOpen) {
        SimpleTeamSettingsDialog(
            team = team,
            vm = vm,
            onDismiss = { teamSettingsOpen = false },
            onDeleted = {
                teamSettingsOpen = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun SpecialistCard(
    specialist: SpecialistProfile,
    onSettings: () -> Unit,
    onOpenChat: () -> Unit
) {
    val primaryModelId = specialist.primaryModel?.modelId
    val primaryModelLabel = primaryModelId
        ?.substringAfter('/')
        ?.ifBlank { primaryModelId }
        ?: "Основная модель не выбрана"

    UmnikPanel(selected = specialist.kind == SpecialistKind.ORCHESTRATOR) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        specialist.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val roleLine = when {
                        specialist.role.isNotBlank() -> specialist.role
                        specialist.kind == SpecialistKind.ORCHESTRATOR -> "Руководитель команды"
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
                UmnikCircleAction(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "Настройки специалиста",
                    onClick = onSettings
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                primaryModelLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (specialist.primaryModel == null)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth(),
                shape = UmnikFieldShape
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (specialist.kind == SpecialistKind.ORCHESTRATOR) "Открыть Оркестратора" else "Перейти в чат")
            }
        }
    }
}

@Composable
private fun SpecialistSettingsDialog(
    specialist: SpecialistProfile,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (SpecialistProfile) -> Unit
) {
    var name by remember(specialist.id) { mutableStateOf(specialist.name) }
    var role by remember(specialist.id) { mutableStateOf(specialist.role) }
    var instruction by remember(specialist.id) { mutableStateOf(specialist.instruction) }
    var primaryModel by remember(specialist.id) { mutableStateOf(specialist.primaryModel?.modelId.orEmpty()) }
    var quickModelsText by remember(specialist.id) {
        mutableStateOf(specialist.quickModels.joinToString("\n") { it.modelId })
    }
    var reasoningEnabled by remember(specialist.id) { mutableStateOf(specialist.reasoningEnabled) }
    var reasoningEffort by remember(specialist.id) { mutableStateOf(specialist.reasoningEffort) }
    var webSearch by remember(specialist.id) { mutableStateOf(specialist.webSearchEnabled) }
    var webSearchPreset by remember(specialist.id) { mutableStateOf(specialist.tools.webSearchPreset) }
    var webSearchEngine by remember(specialist.id) { mutableStateOf(specialist.tools.webSearchEngine) }
    var deleteConfirm by remember(specialist.id) { mutableStateOf(false) }
    var skillEditorOpen by remember(specialist.id) { mutableStateOf(false) }
    var skillName by remember(specialist.id) { mutableStateOf("") }
    var skillBody by remember(specialist.id) { mutableStateOf("") }
    val ownedSkills = vm.specialistSkills(specialist.id)
    val ownedFiles = vm.specialistFiles(specialist.id)
    val specialistFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addSpecialistFiles(specialist.id, uris)
    }
    val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importSpecialistSkillFile(specialist.id, it) }
    }
    val skillFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importSpecialistSkillTree(specialist.id, it) }
    }

    LaunchedEffect(specialist.id) {
        if (state.modelCatalogConnectionId != "openrouter") {
            vm.loadConnectionModels("openrouter")
        }
    }

    fun ref(modelId: String): SpecialistModelRef? =
        modelId.trim().takeIf { it.isNotBlank() }?.let { SpecialistModelRef("openrouter", it) }

    val selectedPrimaryInfo = state.modelCatalog.firstOrNull { it.id == primaryModel }
        ?: state.availableTextModels.firstOrNull { it.id == primaryModel }
    val supportedReasoningEfforts = selectedPrimaryInfo?.reasoningEfforts.orEmpty()
    val reasoningKnownUnsupported = selectedPrimaryInfo != null && !selectedPrimaryInfo.supportsReasoning

    fun buildProfile(): SpecialistProfile = specialist.copy(
        name = name.trim().ifBlank {
            if (specialist.kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else "Специалист"
        },
        role = role.trim(),
        instruction = instruction.trim(),
        primaryModel = ref(primaryModel),
        quickModels = quickModelsText
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map { SpecialistModelRef("openrouter", it) }
            .toList(),
        reasoningEnabled = reasoningEnabled && !reasoningKnownUnsupported,
        reasoningEffort = reasoningEffort,
        webSearchEnabled = webSearch,
        tools = specialist.tools.copy(
            webSearch = if (webSearch) WebSearchMode.AUTO else WebSearchMode.OFF,
            webSearchPreset = webSearchPreset,
            webSearchEngine = webSearchEngine
        )
    )

    FullScreenPanel(
        title = if (specialist.kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else "Настройки специалиста",
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
                    label = { Text("Назначение специалиста") },
                    trailingIcon = {
                        UmnikInfoHint(
                            title = "Назначение специалиста",
                            text = if (specialist.kind == SpecialistKind.ORCHESTRATOR) {
                                "Коротко опишите, чем управляет Оркестратор и какие решения он должен принимать в команде."
                            } else {
                                "Коротко опишите специализацию специалиста и какие задачи ему можно поручать. Это описание видит Оркестратор."
                            }
                        )
                    },
                    minLines = 2
                )
            }
            item {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Инструкция специалисту") },
                    trailingIcon = {
                        UmnikInfoHint(
                            title = "Инструкция специалисту",
                            text = "Подробные правила работы этого специалиста: стиль ответа, порядок действий, ограничения, формат результата и другие постоянные требования."
                        )
                    },
                    minLines = 5
                )
            }

            item { SpecialistSettingsSectionTitle("Модели") }

            item {
                ModelField(
                    label = "Основная модель",
                    info = "Обязательная модель, которая отвечает в чате специалиста и выполняет его основные задачи.",
                    value = primaryModel,
                    onValueChange = { primaryModel = it },
                    onPick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings", "Настройки специалиста") }
                )
            }
            item {
                UmnikModelIdField(
                    label = "Дополнительные модели чатов",
                    value = quickModelsText,
                    onValueChange = { quickModelsText = it },
                    onPick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings", "Настройки специалиста") },
                    info = "Необязательно. Дополнительные модели для переключения прямо в чате этого специалиста. Указываются по одной модели OpenRouter в строке.",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4
                )
            }
            item { SpecialistSettingsSectionTitle("Работа модели") }

            item {
                ToggleSettingRow(
                    title = "Размышление",
                    subtitle = when {
                        selectedPrimaryInfo == null -> "Поддержка уточнится после загрузки каталога"
                        reasoningKnownUnsupported -> "Выбранная модель не поддерживает размышление"
                        selectedPrimaryInfo.supportsReasoningEffort && supportedReasoningEfforts.isNotEmpty() ->
                            "Доступно: " + supportedReasoningEfforts.joinToString(" · ")
                        selectedPrimaryInfo.supportsReasoningEffort -> "Уровень поддерживается моделью"
                        else -> "Модель поддерживает reasoning без выбора уровня"
                    },
                    info = "Umnik показывает только уровни, заявленные выбранной моделью в каталоге OpenRouter. Выбранный уровень подсвечен.",
                    checked = reasoningEnabled && !reasoningKnownUnsupported,
                    onCheckedChange = { if (!reasoningKnownUnsupported) reasoningEnabled = it }
                )
            }
            if (reasoningEnabled && !reasoningKnownUnsupported && selectedPrimaryInfo?.supportsReasoningEffort == true) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReasoningEffort.entries
                            .filter { supportedReasoningEfforts.isEmpty() || it.apiValue in supportedReasoningEfforts }
                            .forEach { effort ->
                                FilterChip(
                                    selected = reasoningEffort == effort,
                                    onClick = { reasoningEffort = effort },
                                    label = {
                                        Text(
                                            when (effort) {
                                                ReasoningEffort.MINIMAL -> "Min"
                                                ReasoningEffort.LOW -> "Low"
                                                ReasoningEffort.MEDIUM -> "Med"
                                                ReasoningEffort.HIGH -> "High"
                                                ReasoningEffort.XHIGH -> "XH"
                                                ReasoningEffort.MAX -> "Max"
                                            }
                                        )
                                    }
                                )
                            }
                    }
                }
            }
            item {
                ToggleSettingRow(
                    title = "Поиск в сети",
                    subtitle = if (webSearch) "Современный OpenRouter Web Search · ${webSearchPresetLabel(webSearchPreset)}" else "Выключен",
                    info = "Используется современный agentic Web Search OpenRouter. Старый режим поиска здесь не включается.",
                    checked = webSearch,
                    onCheckedChange = { webSearch = it }
                )
            }
            if (webSearch) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Режим поиска", fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                listOf(
                                    WebSearchPreset.ON_DEMAND,
                                    WebSearchPreset.FAST,
                                    WebSearchPreset.NORMAL,
                                    WebSearchPreset.DEEP
                                )
                            ) { preset ->
                                FilterChip(
                                    selected = webSearchPreset == preset,
                                    onClick = { webSearchPreset = preset },
                                    label = { Text(webSearchPresetLabel(preset)) }
                                )
                            }
                        }
                        Text("Сервис поиска", fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(WebSearchEngine.entries) { engine ->
                                FilterChip(
                                    selected = webSearchEngine == engine,
                                    onClick = { webSearchEngine = engine },
                                    label = { Text(webSearchEngineLabel(engine)) }
                                )
                            }
                        }
                    }
                }
            }

            item { SpecialistSettingsSectionTitle("Навыки") }
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
                        "Навыков у этого специалиста пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(ownedSkills, key = { "specialist-skill-${it.id}" }) { skill ->
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
                            checked = skill.id in specialist.skillIds,
                            onCheckedChange = { enabled ->
                                vm.setSpecialistSkillEnabled(specialist.id, skill.id, enabled)
                            }
                        )
                        IconButton(onClick = { vm.deleteSpecialistSkill(specialist.id, skill.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                        }
                    }
                    HorizontalDivider()
                }
            }

            item { SpecialistSettingsSectionTitle("Файлы специалиста") }
            item {
                FilledTonalButton(
                    onClick = { specialistFilesPicker.launch(arrayOf("*/*")) },
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
                        "Постоянных файлов у специалиста пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(ownedFiles, key = { "specialist-file-${it.id}" }) { file ->
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
                        IconButton(onClick = { vm.deleteSpecialistFile(specialist.id, file.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл специалиста")
                        }
                    }
                    HorizontalDivider()
                }
            }

            item { SpecialistSettingsSectionTitle("База знаний") }
            item {
                KnowledgeBaseSection(
                    kind = com.ayuemin.ymnik.model.KnowledgeOwnerKind.SPECIALIST,
                    ownerId = specialist.id,
                    state = state,
                    vm = vm,
                    title = "База знаний специалиста"
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpecialistSettingsSectionTitle("Локальная среда")
                    Spacer(Modifier.weight(1f))
                    UmnikInfoHint(
                        title = "Локальная среда специалиста",
                        text = "Навыки, файлы, база знаний, память и разговоры хранятся в локальной папке этого специалиста и не наследуются другими специалистами."
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val saved = buildProfile()
                        vm.saveSpecialist(saved)
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
                    onClick = { vm.saveSpecialist(buildProfile()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить")
                }
            }

            if (specialist.kind == SpecialistKind.SPECIALIST) {
                item {
                    OutlinedButton(
                        onClick = { deleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Удалить специалиста")
                    }
                }
            }
        }
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
                        if (vm.createSpecialistSkill(specialist.id, skillName, skillBody) != null) {
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
            title = { Text("Удалить специалиста?") },
            text = { Text("Будут удалены его разговоры и локальное рабочее хранилище.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSpecialist(specialist.id)
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

@Composable
private fun ModelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit,
    info: String? = null
) {
    UmnikModelIdField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        onPick = onPick,
        info = info
    )
}

private fun webSearchPresetLabel(value: WebSearchPreset): String = when (value) {
    WebSearchPreset.ON_DEMAND -> "По запросу"
    WebSearchPreset.FAST -> "Быстрый"
    WebSearchPreset.NORMAL -> "Обычный"
    WebSearchPreset.DEEP -> "Глубокий"
}

private fun webSearchEngineLabel(value: WebSearchEngine): String = when (value) {
    WebSearchEngine.AUTO -> "Авто"
    WebSearchEngine.NATIVE -> "OpenRouter Native"
    WebSearchEngine.EXA -> "Exa"
    WebSearchEngine.PARALLEL -> "Parallel"
    WebSearchEngine.PERPLEXITY -> "Perplexity"
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    info: String? = null
) {
    UmnikPanel {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Medium)
                    if (info != null) {
                        Spacer(Modifier.width(6.dp))
                        UmnikInfoHint(title = title, text = info)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SpecialistSettingsSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun SimpleTeamCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, favorite: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый команда") },
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
                    subtitle = "Показывать команда выше остальных",
                    checked = favorite,
                    onCheckedChange = { favorite = it }
                )
                Text(
                    "Инструкции, навыки и знания настраиваются у каждого специалиста отдельно.",
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
private fun SimpleTeamSettingsDialog(
    team: Team,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    var name by remember(team.id) { mutableStateOf(team.name) }
    var favorite by remember(team.id) { mutableStateOf(team.isFavorite) }
    var deleteConfirm by remember(team.id) { mutableStateOf(false) }

    FullScreenPanel(title = "Настройки команды", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                UmnikPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true,
                            shape = UmnikFieldShape
                        )
                        ToggleSettingRow(
                            title = "Закрепить",
                            subtitle = "Показывать команда выше остальных",
                            checked = favorite,
                            onCheckedChange = { favorite = it }
                        )
                        Text(
                            "У команды нет общей инструкции, навыков или базы знаний. Это кабинет для Оркестратора и специалистов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        vm.updateTeam(team.id, name, favorite)
                        onDismiss()
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = UmnikFieldShape
                ) {
                    Text("Сохранить")
                }
            }
            item {
                OutlinedButton(
                    onClick = { deleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить команда")
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить команда?") },
            text = { Text("Будут удалены Оркестратор, все специалисты и их локальные рабочие хранилища.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTeam(team.id)
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
