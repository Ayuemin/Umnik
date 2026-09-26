from pathlib import Path
import re

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))


def replace_all_expected(path: str, old: str, new: str, expected: int) -> None:
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{path}: expected {expected} matches, got {count}: {old[:120]!r}')
    write(path, text.replace(old, new))


# 1) Persist Agent mode per chat runtime and expose it to UI state.
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/model/ChatRuntimeProfile.kt',
    '    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val tools: ServerToolSettings = ServerToolSettings(),',
    '    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val agentEnabled: Boolean = false,\n    val tools: ServerToolSettings = ServerToolSettings(),'
)
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/model/Models.kt',
    '    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val reasoningEffortsByModel: Map<String, ReasoningEffort> = emptyMap(),',
    '    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val agentEnabled: Boolean = false,\n    val reasoningEffortsByModel: Map<String, ReasoningEffort> = emptyMap(),'
)

# Dedicated policy keeps the expensive-tool permission rules explicit and testable.
policy = '''package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.InternetMode

internal object AgentModePolicy {
    fun explicitlyRequestsLocalShell(prompt: String): Boolean {
        val value = prompt.lowercase()
        return listOf(
            "запусти local shell",
            "запускай local shell",
            "используй local shell",
            "сделай в local shell",
            "выполни в local shell",
            "проверь в local shell",
            "создай в local shell",
            "через local shell",
            "запусти shell",
            "используй shell",
            "сделай в shell",
            "выполни в shell",
            "проверь в shell",
            "через shell",
            "run local shell",
            "use local shell"
        ).any(value::contains)
    }

    fun explicitlyRequestsLocalBrowser(prompt: String): Boolean {
        val value = prompt.lowercase()
        return listOf(
            "открой в браузере",
            "открой через браузер",
            "используй браузер",
            "запусти браузер",
            "через браузер",
            "открой в browser",
            "use browser",
            "open in browser",
            "перейди по",
            "нажми",
            "кликни",
            "введи",
            "впиши",
            "прокрути",
            "пролистай",
            "вернись назад",
            "скачай"
        ).any(value::contains)
    }

    fun browserRequested(
        agentEnabled: Boolean,
        webSearchEnabled: Boolean,
        internetMode: InternetMode,
        prompt: String
    ): Boolean = explicitlyRequestsLocalBrowser(prompt) ||
        (webSearchEnabled && (
            internetMode == InternetMode.BROWSER ||
                (agentEnabled && internetMode == InternetMode.AUTO)
        ))

    fun shellRequested(agentEnabled: Boolean, prompt: String): Boolean =
        agentEnabled || explicitlyRequestsLocalShell(prompt)
}
'''
write('app/src/main/java/com/ayuemin/ymnik/network/AgentModePolicy.kt', policy)

policy_test = '''package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.InternetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModePolicyTest {
    @Test
    fun `agent off keeps AUTO browser unavailable`() {
        assertFalse(AgentModePolicy.browserRequested(false, true, InternetMode.AUTO, "Расскажи о проекте"))
    }

    @Test
    fun `agent on lets AUTO use browser`() {
        assertTrue(AgentModePolicy.browserRequested(true, true, InternetMode.AUTO, "Найди информацию"))
    }

    @Test
    fun `explicit browser mode remains an explicit permission`() {
        assertTrue(AgentModePolicy.browserRequested(false, true, InternetMode.BROWSER, "Найди информацию"))
    }

    @Test
    fun `direct browser instruction works with agent off`() {
        assertTrue(AgentModePolicy.browserRequested(false, false, InternetMode.AUTO, "Открой в браузере эту страницу"))
    }

    @Test
    fun `direct shell instruction works with agent off`() {
        assertTrue(AgentModePolicy.shellRequested(false, "Запусти Local Shell и проверь файл"))
    }

    @Test
    fun `discussion about shell does not itself grant permission`() {
        assertFalse(AgentModePolicy.shellRequested(false, "Что такое Local Shell и зачем он нужен?"))
    }
}
'''
write('app/src/test/java/com/ayuemin/ymnik/network/AgentModePolicyTest.kt', policy_test)

vm = 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
replace_once(
    vm,
    'import com.ayuemin.ymnik.network.ChatToolPolicy\n',
    'import com.ayuemin.ymnik.network.ChatToolPolicy\nimport com.ayuemin.ymnik.network.AgentModePolicy\n'
)
replace_once(
    vm,
    '            reasoningEnabled = initialRuntime.reasoningEnabled,\n            reasoningEffort = initialRuntime.reasoningEffort,\n            reasoningEffortsByModel = loadReasoningEffortsByModel(),',
    '            reasoningEnabled = initialRuntime.reasoningEnabled,\n            reasoningEffort = initialRuntime.reasoningEffort,\n            agentEnabled = initialRuntime.agentEnabled,\n            reasoningEffortsByModel = loadReasoningEffortsByModel(),'
)
replace_once(
    vm,
    '            reasoningEnabled = if (current) clean.reasoningEnabled else _state.value.reasoningEnabled,\n            reasoningEffort = if (current) clean.reasoningEffort else _state.value.reasoningEffort,\n            activeSkillIds = if (current) clean.skillIds else _state.value.activeSkillIds,',
    '            reasoningEnabled = if (current) clean.reasoningEnabled else _state.value.reasoningEnabled,\n            reasoningEffort = if (current) clean.reasoningEffort else _state.value.reasoningEffort,\n            agentEnabled = if (current) clean.agentEnabled else _state.value.agentEnabled,\n            activeSkillIds = if (current) clean.skillIds else _state.value.activeSkillIds,'
)

agent_setter = '''    fun setAgentEnabled(enabled: Boolean) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return
        if (specialistConversations.specialistIdForConversation(chat.id) != null) return
        val current = teamAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
        teamAutomation.saveProfile(chat.id, current.copy(agentEnabled = enabled))
        _state.value = _state.value.copy(agentEnabled = enabled)
        DiagnosticLog.action(
            context,
            "agent_mode_toggle",
            "enabled=$enabled; chat=${chat.id.take(8)}; model=${currentTextModelId()}"
        )
    }

'''
replace_once(vm, '    fun setReasoningEnabled(enabled: Boolean) {', agent_setter + '    fun setReasoningEnabled(enabled: Boolean) {')

# Keep agent state synchronized whenever the active chat/runtime changes.
replace_once(vm, '            internetMode = fixed.tools.internetMode,\n            pendingAttachments = emptyList(),\n            storageStats = storageRepository.stats(),', '            internetMode = fixed.tools.internetMode,\n            agentEnabled = fixed.agentEnabled,\n            pendingAttachments = emptyList(),\n            storageStats = storageRepository.stats(),')
replace_once(vm, '            internetMode = guideRuntime.tools.internetMode,\n            pendingAttachments = emptyList(),\n            status = null', '            internetMode = guideRuntime.tools.internetMode,\n            agentEnabled = guideRuntime.agentEnabled,\n            pendingAttachments = emptyList(),\n            status = null')
replace_once(vm, '            internetMode = branchRuntime.tools.internetMode,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),', '            internetMode = branchRuntime.tools.internetMode,\n            agentEnabled = branchRuntime.agentEnabled,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),')
replace_once(vm, '            internetMode = fixed.tools.internetMode,\n            apiKeyConfigured = isProfileConfigured(profile),\n            pendingAttachments = emptyList()', '            internetMode = fixed.tools.internetMode,\n            agentEnabled = fixed.agentEnabled,\n            apiKeyConfigured = isProfileConfigured(profile),\n            pendingAttachments = emptyList()')
replace_once(vm, '            internetMode = resetRuntime.tools.internetMode,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),', '            internetMode = resetRuntime.tools.internetMode,\n            agentEnabled = resetRuntime.agentEnabled,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),')

# Request-level expensive tool permissions. Web search/fetch stays under the Internet control;
# Browser AUTO and autonomous Shell additionally require Agent mode. Explicit user commands win.
replace_once(
    vm,
    '''        val webSearchEnabled = _state.value.webSearchEnabled
        val webSearchPreset = _state.value.webSearchPreset
        val internetMode = _state.value.internetMode
        val providerWebSearchEnabled = webSearchEnabled && internetMode != InternetMode.BROWSER
        val localWebFetchEnabled = webSearchEnabled && internetMode != InternetMode.BROWSER
        val localBrowserToolsEnabled = webSearchEnabled && internetMode != InternetMode.SEARCH_ONLY
        val reasoningEnabled = _state.value.reasoningEnabled
''',
    '''        val webSearchEnabled = _state.value.webSearchEnabled
        val webSearchPreset = _state.value.webSearchPreset
        val internetMode = _state.value.internetMode
        val agentEnabled = _state.value.agentEnabled
        val providerWebSearchEnabled = webSearchEnabled && internetMode != InternetMode.BROWSER
        val localWebFetchEnabled = webSearchEnabled && internetMode != InternetMode.BROWSER
        val localBrowserRequested = AgentModePolicy.browserRequested(
            agentEnabled = agentEnabled,
            webSearchEnabled = webSearchEnabled,
            internetMode = internetMode,
            prompt = clean
        )
        val localShellRequested = AgentModePolicy.shellRequested(agentEnabled, clean)
        val reasoningEnabled = _state.value.reasoningEnabled
'''
)
replace_once(
    vm,
    '''                        val createFileToolEnabled = (autoRouter || modelInfo?.supportsTools == true) &&
                            ChatToolPolicy.needsCreateFile(
                                prompt = clean,
                                instructions = listOf(
                                    skillText,
                                    currentChat?.masterPrompt.orEmpty(),
                                    requestSpecialist?.instruction.orEmpty()
                                )
                            )
                        val localShellToolsEnabled = requestSpecialist == null &&
                            (autoRouter || modelInfo?.supportsTools == true)
''',
    '''                        val modelToolsAvailable = autoRouter || modelInfo?.supportsTools == true
                        val createFileToolEnabled = modelToolsAvailable &&
                            ChatToolPolicy.needsCreateFile(
                                prompt = clean,
                                instructions = listOf(
                                    skillText,
                                    currentChat?.masterPrompt.orEmpty(),
                                    requestSpecialist?.instruction.orEmpty()
                                )
                            )
                        val localBrowserToolsEnabled = modelToolsAvailable && if (requestSpecialist != null) {
                            webSearchEnabled && internetMode != InternetMode.SEARCH_ONLY
                        } else {
                            localBrowserRequested
                        }
                        val localShellToolsEnabled = requestSpecialist == null &&
                            modelToolsAvailable && localShellRequested
'''
)
replace_once(
    vm,
    '                                    localBrowserToolsEnabled = localBrowserToolsEnabled,\n                                    localShellToolsEnabled = localShellToolsEnabled\n',
    '                                    localBrowserToolsEnabled = localBrowserToolsEnabled,\n                                    localShellToolsEnabled = localShellToolsEnabled,\n                                    agentModeEnabled = agentEnabled\n'
)
replace_once(
    vm,
    '        localBrowserToolsEnabled: Boolean = false,\n        localShellToolsEnabled: Boolean = false\n    ): String = buildString {',
    '        localBrowserToolsEnabled: Boolean = false,\n        localShellToolsEnabled: Boolean = false,\n        agentModeEnabled: Boolean = false\n    ): String = buildString {'
)
replace_once(
    vm,
    '        appendLine("Не проси пользователя повторно прислать материал, если нужный текст, результат или сведения уже присутствуют в переданной истории, долговременной памяти, базе знаний или приложенных файлах.")\n        if (toolsEnabled) {',
    '        appendLine("Не проси пользователя повторно прислать материал, если нужный текст, результат или сведения уже присутствуют в переданной истории, долговременной памяти, базе знаний или приложенных файлах.")\n        if (specialist == null && !agentModeEnabled) {\n            appendLine("Агентный режим этого чата выключен. Не инициируй Browser или Local Shell самостоятельно. Если они могли бы заметно упростить задачу, можешь кратко предложить пользователю включить «Агент» или прямо попросить нужный инструмент.")\n            appendLine("Если Browser или Local Shell всё же доступен среди инструментов текущего запроса, это означает явное разрешение пользователя: он прямо попросил этот инструмент либо выбрал соответствующий режим. В таком случае выполняй задачу без повторного согласования и не спорь с выбором пользователя.")\n        }\n        if (toolsEnabled) {'
)
replace_once(
    vm,
    '            appendLine("Если пользователь прямо просит сделать что-то «в Shell», через Local Shell, локально с файлом/архивом/проектом или просит реально переименовать, распаковать, собрать, преобразовать, проверить или изменить файл, не ограничивайся советом: при ясной задаче запускай local_shell_start.")\n',
    '            appendLine("Если пользователь прямо просит сделать что-то «в Shell», через Local Shell, локально с файлом/архивом/проектом или просит реально переименовать, распаковать, собрать, преобразовать, проверить или изменить файл, не ограничивайся советом: при ясной задаче запускай local_shell_start.")\n            appendLine("Если пользователь прямо просит запустить Local Shell именно для технического теста или диагностики, не заменяй реальный запуск рассуждением о том, что тест «не нужен». Повторы и зацикливание контролирует сама среда Local Shell; задача модели — выполнить явно запрошенный безопасный тест и дать среде показать результат защиты.")\n'
)

# 4) UI: all model-supported reasoning efforts, elastic indicators and third Agent tile.
ui = 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
replace_once(ui, 'import androidx.compose.material.icons.outlined.Storage\n', 'import androidx.compose.material.icons.outlined.Storage\nimport androidx.compose.material.icons.outlined.SmartToy\n')
replace_once(
    ui,
    '    var webSearchModeInfoOpen by remember(state.currentChatId) { mutableStateOf(false) }\n    var attachmentsExpanded by remember(state.currentChatId) { mutableStateOf(false) }',
    '    var webSearchModeInfoOpen by remember(state.currentChatId) { mutableStateOf(false) }\n    var agentModeInfoOpen by remember(state.currentChatId) { mutableStateOf(false) }\n    var attachmentsExpanded by remember(state.currentChatId) { mutableStateOf(false) }'
)
replace_once(
    ui,
    '''    val reasoningAvailable = !imagePromptMode && textModelInfo?.supportsReasoning == true
    val reasoningLevelSelectable = reasoningAvailable &&
        textModelInfo?.supportsReasoningEffort == true &&
        textModelInfo.reasoningEfforts.isNotEmpty()
''',
    '''    val reasoningAvailable = !imagePromptMode && textModelInfo?.supportsReasoning == true
    val supportedReasoningEfforts = if (
        reasoningAvailable && textModelInfo?.supportsReasoningEffort == true
    ) {
        ReasoningEffort.entries.filter { effort -> effort.apiValue in textModelInfo.reasoningEfforts }
    } else {
        emptyList()
    }
    val reasoningLevelSelectable = supportedReasoningEfforts.isNotEmpty()
'''
)

old_tiles = '''                if (!imagePromptMode && currentSpecialistId == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ComposerToggleTile(
                            icon = Icons.Outlined.Psychology,
                            level = if (reasoningLevelSelectable) reasoningEffortIndicatorLevel(state.reasoningEffort) else 0,
                            levelCount = 3,
                            levelDescription = if (state.reasoningEnabled) "Размышление: " + reasoningEffortUiLabel(state.reasoningEffort) else "Размышление выключено",
                            checked = state.reasoningEnabled,
                            enabled = reasoningAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { reasoningModeOpen = true },
                            onCheckedChange = vm::setReasoningEnabled
                        )
                        ComposerToggleTile(
                            icon = Icons.Outlined.Language,
                            level = when (state.internetMode) {
                                InternetMode.BROWSER -> 4
                                else -> webSearchPresetIndicatorLevel(state.webSearchPreset)
                            },
                            levelCount = 4,
                            indicatorText = when (state.internetMode) {
                                InternetMode.SEARCH_ONLY -> null
                                InternetMode.AUTO -> "AUTO"
                                InternetMode.BROWSER -> "BROW"
                            },
                            levelDescription = when {
                                !state.webSearchEnabled -> "Интернет выключен"
                                state.internetMode == InternetMode.SEARCH_ONLY -> "Только поиск: " + webSearchPresetUiLabel(state.webSearchPreset)
                                state.internetMode == InternetMode.AUTO -> "Автоматически: " + webSearchPresetUiLabel(state.webSearchPreset)
                                else -> "Браузер"
                            },
                            checked = state.webSearchEnabled,
                            enabled = webSearchAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { webSearchModeOpen = true },
                            onCheckedChange = vm::setWebSearchEnabled
                        )
                    }
                }
'''
new_tiles = '''                if (!imagePromptMode && currentSpecialistId == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        ComposerToggleTile(
                            icon = Icons.Outlined.Psychology,
                            level = supportedReasoningEfforts.indexOf(state.reasoningEffort).let { index ->
                                if (index >= 0) index + 1 else 0
                            },
                            levelCount = supportedReasoningEfforts.size.coerceAtLeast(1),
                            levelDescription = if (state.reasoningEnabled) "Размышление: " + reasoningEffortUiLabel(state.reasoningEffort) else "Размышление выключено",
                            checked = state.reasoningEnabled,
                            enabled = reasoningAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { reasoningModeOpen = true },
                            onCheckedChange = vm::setReasoningEnabled
                        )
                        ComposerToggleTile(
                            icon = Icons.Outlined.Language,
                            level = when (state.internetMode) {
                                InternetMode.BROWSER -> 4
                                else -> webSearchPresetIndicatorLevel(state.webSearchPreset)
                            },
                            levelCount = 4,
                            indicatorText = when (state.internetMode) {
                                InternetMode.SEARCH_ONLY -> null
                                InternetMode.AUTO -> "AUTO"
                                InternetMode.BROWSER -> "BROW"
                            },
                            levelDescription = when {
                                !state.webSearchEnabled -> "Интернет выключен"
                                state.internetMode == InternetMode.SEARCH_ONLY -> "Только поиск: " + webSearchPresetUiLabel(state.webSearchPreset)
                                state.internetMode == InternetMode.AUTO -> "Автоматически: " + webSearchPresetUiLabel(state.webSearchPreset)
                                else -> "Браузер"
                            },
                            checked = state.webSearchEnabled,
                            enabled = webSearchAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { webSearchModeOpen = true },
                            onCheckedChange = vm::setWebSearchEnabled
                        )
                        ComposerToggleTile(
                            icon = Icons.Outlined.SmartToy,
                            level = 5,
                            levelCount = 5,
                            indicatorText = "АГЕНТ",
                            levelDescription = if (state.agentEnabled) "Агентный режим включён" else "Агентный режим выключен",
                            checked = state.agentEnabled,
                            enabled = openRouterProfile,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { agentModeInfoOpen = true },
                            onCheckedChange = vm::setAgentEnabled
                        )
                    }
                }
'''
replace_once(ui, old_tiles, new_tiles)

replace_once(
    ui,
    '''    if (reasoningModeOpen) {
        val supportedEfforts = if (textModelInfo?.supportsReasoningEffort == true) {
            ReasoningEffort.entries.filter { effort ->
                textModelInfo.reasoningEfforts.isNotEmpty() && effort.apiValue in textModelInfo.reasoningEfforts
            }
        } else {
            emptyList()
        }
        val controllableEfforts = if (supportedEfforts.size <= 3) {
            supportedEfforts
        } else {
            listOfNotNull(
                supportedEfforts.firstOrNull { it == ReasoningEffort.LOW } ?: supportedEfforts.firstOrNull(),
                supportedEfforts.firstOrNull { it == ReasoningEffort.HIGH } ?: supportedEfforts.getOrNull(supportedEfforts.lastIndex / 2),
                supportedEfforts.firstOrNull { it == ReasoningEffort.MAX } ?: supportedEfforts.lastOrNull()
            ).distinct()
        }
''',
    '''    if (reasoningModeOpen) {
        val controllableEfforts = supportedReasoningEfforts
'''
)

agent_dialog = '''
    if (agentModeInfoOpen) {
        AlertDialog(
            onDismissRequest = { agentModeInfoOpen = false },
            title = { Text("Агентный режим") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Настройка относится только к текущему чату.")
                    Text("Когда Агент выключен, Umnik не запускает Browser или Local Shell по собственной инициативе. Если инструмент заметно помог бы, модель может предложить его.")
                    Text("Прямая команда пользователя имеет приоритет: если вы сами попросили Browser или Local Shell, конкретный инструмент можно запустить и при выключенном Агенте.")
                    Text("Когда Агент включён, модель может сама выбирать и сочетать доступные инструменты. Длинная работа может увеличить расход OpenRouter.")
                }
            },
            confirmButton = {
                TextButton(onClick = { agentModeInfoOpen = false }) { Text("Понятно") }
            }
        )
    }

'''
replace_once(ui, '    if (teamsOpen) {\n', agent_dialog + '    if (teamsOpen) {\n')

# Compact the three tiles while keeping the current theme-driven colors and touch semantics.
replace_once(ui, '            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),', '            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),')
replace_once(ui, '                    modifier = Modifier.size(20.dp),\n                    tint = if (enabled) MaterialTheme.colorScheme.primary', '                    modifier = Modifier.size(18.dp),\n                    tint = if (enabled) MaterialTheme.colorScheme.primary')
replace_once(ui, '                Spacer(Modifier.width(8.dp))\n                Row(\n                    modifier = Modifier.weight(1f),\n                    horizontalArrangement = Arrangement.spacedBy(4.dp),', '                Spacer(Modifier.width(5.dp))\n                Row(\n                    modifier = Modifier.weight(1f),\n                    horizontalArrangement = Arrangement.spacedBy(\n                        when {\n                            levelCount >= 5 -> 1.dp\n                            levelCount >= 4 -> 2.dp\n                            else -> 3.dp\n                        }\n                    ),')
replace_once(ui, '                                    style = MaterialTheme.typography.labelMedium,\n                                    fontWeight = FontWeight.Bold,', '                                    style = if (indicatorText.length >= 5) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,\n                                    fontWeight = FontWeight.Bold,')
replace_once(ui, '                                    modifier = Modifier.width(14.dp).height(6.dp),', '                                    modifier = Modifier.fillMaxWidth().height(6.dp),')
replace_once(ui, '            Spacer(Modifier.width(8.dp))\n            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)', '            Spacer(Modifier.width(4.dp))\n            Switch(\n                checked = checked,\n                onCheckedChange = onCheckedChange,\n                enabled = enabled,\n                modifier = Modifier.width(42.dp).scale(0.86f)\n            )')

# 5) Version for the signed test build.
build = 'app/build.gradle.kts'
text = read(build)
text = text.replace('// Umnik v1.20.0-beta.26 — guarded long Local Shell runs', '// Umnik v1.20.0-beta.27 — per-chat Agent mode and adaptive reasoning UI', 1)
text = text.replace('versionCode = 183', 'versionCode = 184', 1)
text = text.replace('versionName = "1.20.0-beta.26"', 'versionName = "1.20.0-beta.27"', 1)
if 'versionCode = 184' not in text or 'versionName = "1.20.0-beta.27"' not in text:
    raise SystemExit('build.gradle.kts version bump failed')
write(build, text)

print('Agent mode + dynamic reasoning patch applied successfully')
