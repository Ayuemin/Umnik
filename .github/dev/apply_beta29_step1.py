from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Show Agent mode beside the existing reasoning/internet composer indicators.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                            if (!imagePromptMode && state.webSearchEnabled) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.Language,
                                    description = "Поиск в сети включён"
                                )
                            }
''',
    '''                            if (!imagePromptMode && state.webSearchEnabled) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.Language,
                                    description = "Поиск в сети включён"
                                )
                            }
                            if (!imagePromptMode && state.agentEnabled) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.SmartToy,
                                    description = "Агентный режим включён"
                                )
                            }
'''
)

# 2) The Local Shell started by a parent chat request must not survive that parent.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val startedAt = System.currentTimeMillis()
        LocalShellRuntime.scope.launch {
            var lastKeepAliveRefreshAt = 0L
            runCatching {
''',
    '''        val startedAt = System.currentTimeMillis()
        LocalShellRuntime.scope.launch {
            var lastKeepAliveRefreshAt = 0L
            val parentOwned = originUserMessageId != null
            val parentWatcher = if (parentOwned) {
                launch {
                    while (RequestExecutionManager.hasActiveChat(chatId)) {
                        delay(100L)
                    }
                    cancelRequested.set(true)
                    localShellClient.cancelActive()
                    DiagnosticLog.record(
                        context,
                        "LOCAL_SHELL_LIFECYCLE",
                        "parent request ended; cancelling child shell chat=${chatId.take(8)} task=${taskId.take(8)}"
                    )
                }
            } else {
                null
            }
            runCatching {
'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            AsyncJobEvents.markLocalShellFinished(chatId)
            runCatching { RequestKeepAliveService.update(context) }
            AsyncJobEvents.notifyChanged()
            LocalShellRuntime.clear()
''',
    '''            parentWatcher?.cancel()
            AsyncJobEvents.markLocalShellFinished(chatId)
            runCatching { RequestKeepAliveService.update(context) }
            AsyncJobEvents.notifyChanged()
            LocalShellRuntime.clear()
'''
)

# 3) Protect the parent model/tool loop itself, not only Browser/Shell internals.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''        val requestRunId = UUID.randomUUID().toString()
        var loops = 0
''',
    '''        val agentToolLoopGuard = AgentToolLoopGuard()
        val requestRunId = UUID.randomUUID().toString()
        var loops = 0
'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''            phaseCallback("Выполняю инструменты…")
            messages.add(responseMessage.deepCopy())
            for (callElement in toolCalls) {
''',
    '''            phaseCallback("Выполняю инструменты…")
            messages.add(responseMessage.deepCopy())
            var agentLoopDecisionForBatch: AgentToolLoopDecision? = null
            for (callElement in toolCalls) {
'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''                    else -> gson.toJson(mapOf("ok" to false, "error" to "Неизвестный инструмент: $name"))
                }
                if (name.startsWith("local_browser_")) {
''',
    '''                    else -> gson.toJson(mapOf("ok" to false, "error" to "Неизвестный инструмент: $name"))
                }
                val agentLoopDecision = agentToolLoopGuard.observeTool(name, argsRaw, resultText)
                if (agentLoopDecision != null &&
                    (agentLoopDecisionForBatch == null || agentLoopDecision.shouldStop)
                ) {
                    agentLoopDecisionForBatch = agentLoopDecision
                }
                if (name.startsWith("local_browser_")) {
'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''                if (name.startsWith("local_browser_")) {
                    browserToolCallIds += callId
                }
            }
''',
    '''                if (name.startsWith("local_browser_")) {
                    browserToolCallIds += callId
                }
            }
            agentLoopDecisionForBatch?.let { decision ->
                DiagnosticLog.record(
                    context,
                    "AGENT_TOOL_LOOP_GUARD",
                    "pattern=${decision.patternSize}; strike=${decision.strike}; stop=${decision.shouldStop}; request=$requestRunId; step=$loops"
                )
                if (decision.shouldStop) {
                    error(
                        "Агент остановлен: повторяющийся цикл инструментов не меняет состояние после попытки перестроить план. " +
                            "Операция остановлена до аварийного лимита как защита от лишних расходов."
                    )
                }
                messages.add(
                    message(
                        "system",
                        "Защита Umnik обнаружила повторяющийся цикл инструментов без изменения результата. " +
                            "Это первое предупреждение: перестрой план и не повторяй ту же последовательность. " +
                            "Если цикл повторится до заметного прогресса, выполнение будет остановлено."
                    )
                )
            }
'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''        Result(
            if (localBrowserToolsEnabled) {
                "Local Browser достиг аварийного предела $maxToolLoops циклов без завершения. " +
                    "Операция остановлена как последняя защита от бесконтрольных расходов."
            } else {
                "Модель слишком много раз вызывала инструменты. Операция остановлена."
            },
            created
''',
    '''        Result(
            if (localBrowserToolsEnabled && !localShellToolsEnabled && !localWebFetchEnabled) {
                "Local Browser достиг аварийного предела $maxToolLoops циклов без завершения. " +
                    "Операция остановлена как последняя защита от бесконтрольных расходов."
            } else {
                "Агент достиг аварийного предела $maxToolLoops автономных циклов без завершения. " +
                    "Операция остановлена как последняя защита от бесконтрольных расходов."
            },
            created
'''
)

print("beta.29 step1 patch applied")
