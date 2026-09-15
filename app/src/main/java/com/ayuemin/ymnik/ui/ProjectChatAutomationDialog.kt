package com.ayuemin.ymnik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.ayuemin.ymnik.model.OrchestratorStep
import com.ayuemin.ymnik.model.OrchestratorStepType
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectChatRuntimeProfile
import com.ayuemin.ymnik.model.ProjectStage
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.UiState

@Composable
fun ProjectChatAutomationDialog(
    chat: ChatSession,
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    val isOrchestrator = vm.isOrchestratorChat(chat.id)
    val initialProfile = vm.projectChatRuntimeProfile(chat.id)
        ?: ProjectChatRuntimeProfile(
            modelId = chat.textModelOverride ?: state.textModel,
            webSearchEnabled = if (chat.id == state.currentChatId) state.webSearchEnabled else false,
            reasoningEnabled = if (chat.id == state.currentChatId) state.reasoningEnabled else false,
            reasoningEffort = if (chat.id == state.currentChatId) state.reasoningEffort else ReasoningEffort.MEDIUM,
            tools = vm.globalOpenRouterTools(),
            skillIds = vm.chatSkillIds(chat.id)
        )

    var title by remember(chat.id, chat.title) { mutableStateOf(chat.title) }
    var role by remember(chat.id, chat.assignedRole) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var instruction by remember(chat.id, chat.masterPrompt) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id, chat.isFavorite) { mutableStateOf(chat.isFavorite) }

    var modelId by remember(chat.id, initialProfile.modelId) { mutableStateOf(initialProfile.modelId ?: state.textModel) }
    var webSearch by remember(chat.id, initialProfile.webSearchEnabled) { mutableStateOf(initialProfile.webSearchEnabled) }
    var reasoning by remember(chat.id, initialProfile.reasoningEnabled) { mutableStateOf(initialProfile.reasoningEnabled) }
    var effort by remember(chat.id, initialProfile.reasoningEffort) { mutableStateOf(initialProfile.reasoningEffort) }
    var tools by remember(chat.id, initialProfile.tools) { mutableStateOf(initialProfile.tools) }
    var skillIds by remember(chat.id, initialProfile.skillIds) { mutableStateOf(initialProfile.skillIds) }

    var modelExpanded by remember(chat.id) { mutableStateOf(false) }
    var reasoningExpanded by remember(chat.id) { mutableStateOf(false) }
    var searchExpanded by remember(chat.id) { mutableStateOf(false) }
    var toolsExpanded by remember(chat.id) { mutableStateOf(false) }
    var filesExpanded by remember(chat.id) { mutableStateOf(false) }
    var skillsExpanded by remember(chat.id) { mutableStateOf(false) }
    var stagesExpanded by remember(chat.id) { mutableStateOf(false) }
    var orchestrationExpanded by remember(chat.id) { mutableStateOf(true) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }
    var stepEditorOpen by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<OrchestratorStep?>(null) }
    var steps by remember(chat.id, chat.updatedAt) { mutableStateOf(vm.orchestratorSteps(chat.id)) }

    val quickIds = state.quickTextModels.map { it.substringAfter('\u001F') }
    val models = (listOf(modelId, state.textModel) + state.availableTextModels.map { it.id } + quickIds)
        .filter { it.isNotBlank() && !it.endsWith(":batch", true) }
        .distinct()
    val allowedSkills = state.skills.filter { it.id in project.skillIds }

    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addChatContextFile(chat.id, it) }
    }

    FullScreenPanel(
        title = if (isOrchestrator) "◆ Настройки оркестратора" else "Настройки чата",
        onBack = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isOrchestrator) {
                item {
                    Text(
                        "◆ — системный знак. Оркестратор удаляется только вместе с проектом; имя после знака можно менять.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true) }
            item { OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text(if (isOrchestrator) "Роль оркестратора" else "Роль чата") }) }
            item {
                OutlinedTextField(
                    instruction,
                    { instruction = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (isOrchestrator) "Инструкция оркестратора" else "Инструкция чата") },
                    minLines = 4,
                    maxLines = 12
                )
            }
            if (!isOrchestrator) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Избранное", Modifier.weight(1f))
                        Switch(favorite, { favorite = it })
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, title, role, instruction)
                        if (!isOrchestrator) vm.setChatFavorite(chat.id, favorite)
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить основные настройки") }
            }

            item { SettingsExpander("Модель", modelId.substringAfterLast('/'), modelExpanded) { modelExpanded = !modelExpanded } }
            if (modelExpanded) {
                item {
                    Box {
                        FilledTonalButton(onClick = { modelMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                            models.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    onClick = { modelId = id; modelMenuOpen = false }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsExpander(
                    "Размышление",
                    if (reasoning) "Включено · ${effortLabel(effort)}" else "Выключено",
                    reasoningExpanded
                ) { reasoningExpanded = !reasoningExpanded }
            }
            if (reasoningExpanded) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Использовать размышление", Modifier.weight(1f))
                        Switch(reasoning, { reasoning = it })
                    }
                }
                if (reasoning) {
                    items(ReasoningEffort.entries, key = { "effort-${it.name}" }) { choice ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(effortLabel(choice), Modifier.weight(1f))
                            Checkbox(effort == choice, { if (it) effort = choice })
                        }
                    }
                }
            }

            item { SettingsExpander("Поиск", if (webSearch) "Включён" else "Выключен", searchExpanded) { searchExpanded = !searchExpanded } }
            if (searchExpanded) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Веб-поиск для этого чата", Modifier.weight(1f))
                        Switch(webSearch, { webSearch = it })
                    }
                }
            }

            item {
                val count = listOf(tools.webFetch, tools.datetime, tools.imageGeneration, tools.fusion, tools.shell).count { it }
                SettingsExpander("Инструменты OpenRouter", if (count == 0) "Не выбраны" else "Включено: $count", toolsExpanded) {
                    toolsExpanded = !toolsExpanded
                }
            }
            if (toolsExpanded) {
                item { ToolSwitch("Web Fetch", tools.webFetch) { tools = tools.copy(webFetch = it) } }
                item { ToolSwitch("Дата и время", tools.datetime) { tools = tools.copy(datetime = it) } }
                item { ToolSwitch("Генерация изображений", tools.imageGeneration) { tools = tools.copy(imageGeneration = it) } }
                item { ToolSwitch("Fusion", tools.fusion) { tools = tools.copy(fusion = it) } }
                item { ToolSwitch("Shell", tools.shell) { tools = tools.copy(shell = it) } }
                item {
                    Text(
                        "Поиск задаётся отдельно выше. Расширенные параметры инструментов, которым нужны дополнительные модели, сохраняются из общих настроек.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsExpander(
                    "Вложения",
                    if (chat.chatFiles.orEmpty().isEmpty()) "Нет файлов" else "${chat.chatFiles.orEmpty().size} файлов",
                    filesExpanded
                ) { filesExpanded = !filesExpanded }
            }
            if (filesExpanded) {
                if (chat.chatFiles.orEmpty().isEmpty()) {
                    item { Text("Нет постоянных вложений", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(chat.chatFiles.orEmpty(), key = { "runtime-file-${it.id}" }) { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(file.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.removeChatContextFile(chat.id, file.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить вложение")
                            }
                        }
                    }
                }
                item {
                    FilledTonalButton(onClick = { addFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить вложение")
                    }
                }
            }

            item {
                SettingsExpander(
                    "Навыки",
                    if (skillIds.isEmpty()) "Не выбраны" else "Выбрано: ${skillIds.size}",
                    skillsExpanded
                ) { skillsExpanded = !skillsExpanded }
            }
            if (skillsExpanded) {
                if (allowedSkills.isEmpty()) {
                    item { Text("Сначала выберите возможные навыки в настройках проекта.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(allowedSkills, key = { "runtime-skill-${it.id}" }) { skill ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(skill.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Switch(
                                checked = skill.id in skillIds,
                                onCheckedChange = { enabled ->
                                    skillIds = if (enabled) skillIds + skill.id else skillIds - skill.id
                                }
                            )
                        }
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = {
                        vm.saveProjectChatRuntimeSettings(
                            chat.id,
                            ProjectChatRuntimeProfile(modelId, webSearch, reasoning, effort, tools, skillIds)
                        )
                    },
                    enabled = modelId.isNotBlank() && !state.isLoading && !state.requestActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Сохранить параметры работы")
                }
            }

            if (isOrchestrator) {
                item {
                    SettingsExpander(
                        "Сценарий оркестратора",
                        if (steps.isEmpty()) "Нет шагов" else "${steps.size} шагов",
                        orchestrationExpanded
                    ) { orchestrationExpanded = !orchestrationExpanded }
                }
                if (orchestrationExpanded) {
                    item {
                        Text(
                            "Шаг выполняет выбранный чат по-настоящему: с его моделью, инструкцией, историей, поиском, размышлением, инструментами, навыками и файлами.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (steps.isEmpty()) {
                        item { Text("Шагов нет. Оркестратор можно оставить пустым для простого проекта.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                            OrchestratorStepCard(
                                step = step,
                                index = index,
                                total = steps.size,
                                state = state,
                                onMoveUp = {
                                    vm.moveOrchestratorStep(chat.id, step.id, -1)
                                    steps = vm.orchestratorSteps(chat.id)
                                },
                                onMoveDown = {
                                    vm.moveOrchestratorStep(chat.id, step.id, 1)
                                    steps = vm.orchestratorSteps(chat.id)
                                },
                                onEdit = { editingStep = step; stepEditorOpen = true },
                                onDelete = {
                                    vm.deleteOrchestratorStep(chat.id, step.id)
                                    steps = vm.orchestratorSteps(chat.id)
                                }
                            )
                        }
                    }
                    item {
                        FilledTonalButton(
                            onClick = { editingStep = null; stepEditorOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading && !state.requestActive
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Добавить шаг")
                        }
                    }
                    if (steps.isNotEmpty()) {
                        item {
                            Button(
                                onClick = { vm.runOrchestrator(project.id) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isLoading && !state.requestActive
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Выполнить сценарий")
                            }
                        }
                    }
                }
            } else {
                item {
                    SettingsExpander(
                        "Этапы этого чата",
                        if (chat.stages.orEmpty().isEmpty()) "Не настроены" else "${chat.stages.orEmpty().size} этапов",
                        stagesExpanded
                    ) { stagesExpanded = !stagesExpanded }
                }
                if (stagesExpanded) {
                    if (chat.stages.orEmpty().isEmpty()) {
                        item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        itemsIndexed(chat.stages.orEmpty(), key = { _, stage -> stage.id }) { index, stage ->
                            StageCard(
                                stage,
                                index,
                                chat.stages.orEmpty().size,
                                { vm.moveChatStage(chat.id, stage.id, -1) },
                                { vm.moveChatStage(chat.id, stage.id, 1) },
                                { editingStage = stage; stageEditorOpen = true },
                                { vm.deleteChatStage(chat.id, stage.id) },
                                !state.isLoading
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
                            Spacer(Modifier.width(7.dp))
                            Text("Добавить этап чата")
                        }
                    }
                }
            }
        }
    }

    if (stageEditorOpen && !isOrchestrator) {
        StageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            vm = vm,
            ownerChatId = chat.id,
            onDismiss = { stageEditorOpen = false; editingStage = null },
            onSave = { stageTitle, stageInstruction, stageModelId, files, sourceChatIds ->
                vm.upsertChatStage(chat.id, editingStage?.id, stageTitle, stageInstruction, stageModelId, files, sourceChatIds)
                stageEditorOpen = false
                editingStage = null
            }
        )
    }

    if (stepEditorOpen && isOrchestrator) {
        OrchestratorStepEditorDialog(
            project = project,
            state = state,
            vm = vm,
            step = editingStep,
            onDismiss = { stepEditorOpen = false; editingStep = null },
            onSave = { stepTitle, type, targetChatId, stepPrompt, passPrevious ->
                vm.upsertOrchestratorStep(chat.id, editingStep?.id, stepTitle, type, targetChatId, stepPrompt, passPrevious)
                steps = vm.orchestratorSteps(chat.id)
                stepEditorOpen = false
                editingStep = null
            }
        )
    }
}

@Composable
private fun ToolSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onChecked)
    }
}

private fun effortLabel(value: ReasoningEffort): String = when (value) {
    ReasoningEffort.MINIMAL -> "Минимальное"
    ReasoningEffort.LOW -> "Низкое"
    ReasoningEffort.MEDIUM -> "Среднее"
    ReasoningEffort.HIGH -> "Высокое"
    ReasoningEffort.XHIGH -> "Максимальное"
}

@Composable
private fun OrchestratorStepCard(
    step: OrchestratorStep,
    index: Int,
    total: Int,
    state: UiState,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val target = step.targetChatId?.let { id -> state.chats.firstOrNull { it.id == id } }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("${index + 1}. ${step.title}", fontWeight = FontWeight.SemiBold)
            Text(
                when (step.type) {
                    OrchestratorStepType.EXECUTE_CHAT -> "Выполнить чат · ${target?.title ?: "не выбран"}"
                    OrchestratorStepType.RUN_CHAT_STAGES -> "Выполнить этапы чата · ${target?.title ?: "не выбран"}"
                    OrchestratorStepType.RUN_PROJECT_STAGES -> "Выполнить этапы проекта"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (step.prompt.isNotBlank()) Text(step.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                if (step.passPreviousResult) "Передаёт результат предыдущего шага" else "Без результата предыдущего шага",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onMoveUp, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, contentDescription = "Выше") }
                IconButton(onClick = onMoveDown, enabled = index < total - 1) { Icon(Icons.Outlined.ArrowDownward, contentDescription = "Ниже") }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Изменить") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить") }
            }
        }
    }
}

@Composable
private fun OrchestratorStepEditorDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    step: OrchestratorStep?,
    onDismiss: () -> Unit,
    onSave: (String, OrchestratorStepType, String?, String, Boolean) -> Unit
) {
    var title by remember(step?.id) { mutableStateOf(step?.title.orEmpty()) }
    var type by remember(step?.id) { mutableStateOf(step?.type ?: OrchestratorStepType.EXECUTE_CHAT) }
    var targetChatId by remember(step?.id) { mutableStateOf(step?.targetChatId) }
    var prompt by remember(step?.id) { mutableStateOf(step?.prompt.orEmpty()) }
    var passPrevious by remember(step?.id) { mutableStateOf(step?.passPreviousResult ?: true) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var chatMenuOpen by remember { mutableStateOf(false) }
    val usableChats = state.chats
        .filter { it.projectId == project.id && !vm.isOrchestratorChat(it.id) }
        .sortedByDescending { it.updatedAt }
    val selectedTarget = targetChatId?.let { id -> usableChats.firstOrNull { it.id == id } }

    FullScreenPanel(title = if (step == null) "Новый шаг оркестратора" else "Изменить шаг", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Название шага") }, singleLine = true) }
            item {
                Text("Действие", fontWeight = FontWeight.SemiBold)
                Box {
                    FilledTonalButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (type) {
                            OrchestratorStepType.EXECUTE_CHAT -> "Выполнить чат"
                            OrchestratorStepType.RUN_CHAT_STAGES -> "Выполнить этапы чата"
                            OrchestratorStepType.RUN_PROJECT_STAGES -> "Выполнить этапы проекта"
                        })
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        OrchestratorStepType.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(when (choice) {
                                    OrchestratorStepType.EXECUTE_CHAT -> "Выполнить чат"
                                    OrchestratorStepType.RUN_CHAT_STAGES -> "Выполнить этапы чата"
                                    OrchestratorStepType.RUN_PROJECT_STAGES -> "Выполнить этапы проекта"
                                }) },
                                onClick = {
                                    type = choice
                                    if (choice == OrchestratorStepType.RUN_PROJECT_STAGES) targetChatId = null
                                    typeMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            if (type != OrchestratorStepType.RUN_PROJECT_STAGES) {
                item {
                    Text("Целевой чат", fontWeight = FontWeight.SemiBold)
                    Box {
                        FilledTonalButton(onClick = { chatMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedTarget?.title ?: "Выберите чат", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = chatMenuOpen, onDismissRequest = { chatMenuOpen = false }) {
                            usableChats.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    onClick = { targetChatId = target.id; chatMenuOpen = false }
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Промпт шага") },
                    placeholder = { Text(if (type == OrchestratorStepType.EXECUTE_CHAT) "Что отправить выбранному чату. Можно оставить пустым." else "Исходная задача для этапов") },
                    minLines = 6,
                    maxLines = 18
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Передать результат предыдущего шага")
                        Text("Он будет добавлен к промпту.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(passPrevious, { passPrevious = it })
                }
            }
        }
        FilledTonalButton(
            onClick = { onSave(title, type, targetChatId, prompt, passPrevious) },
            enabled = type == OrchestratorStepType.RUN_PROJECT_STAGES || targetChatId != null,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить шаг") }
    }
}
