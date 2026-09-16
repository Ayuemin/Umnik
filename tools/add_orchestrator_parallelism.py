from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, got {count}")
    return text.replace(old, new, 1)


# Repository-level atomic chat updates are required before Orchestrator workers can
# safely write to different chats at the same time.
path = "app/src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt"
text = read(path)
anchor = """    @Synchronized
    fun updateMessage(chatId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) {
"""
method = """    fun updateChat(chatId: String, transform: (ChatSession) -> ChatSession): List<ChatSession> = synchronized(fileLock) {
        val chats = list()
        val updated = chats.map { chat -> if (chat.id == chatId) transform(chat) else chat }
        save(updated)
        updated
    }

"""
text = replace_once(text, anchor, method + anchor, "atomic ChatRepository.updateChat")
write(path, text)

path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)

# Atomic user/assistant writes for one Orchestrator specialist chat.
text = replace_once(
    text,
    """        var updated = all.map { if (it.id == chat.id) it.copy(messages = it.messages + user, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(updated)
        publishChats(updated)
""",
    """        var updated = chatsRepository.updateChat(chat.id) { current ->
            current.copy(messages = current.messages + user, updatedAt = System.currentTimeMillis())
        }
        publishChats(updated)
""",
    "atomic Orchestrator user append",
)
text = replace_once(
    text,
    """        val latest = chatsRepository.list()
        updated = latest.map { if (it.id == chat.id) it.copy(messages = it.messages + assistant, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(updated)
        publishChats(updated)
""",
    """        updated = chatsRepository.updateChat(chat.id) { current ->
            current.copy(messages = current.messages + assistant, updatedAt = System.currentTimeMillis())
        }
        publishChats(updated)
""",
    "atomic Orchestrator assistant append",
)

# Atomic launch message for a specialist stage sequence.
text = replace_once(
    text,
    """        all = all.map { if (it.id == first.id) it.copy(messages = it.messages + launch, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(all)
        publishChats(all)
""",
    """        all = chatsRepository.updateChat(first.id) { current ->
            current.copy(messages = current.messages + launch, updatedAt = System.currentTimeMillis())
        }
        publishChats(all)
""",
    "atomic Orchestrator stage launch",
)

# Stage output append must also be atomic because different specialist chats can
# finish their stage sequences at the same time.
append_pattern = re.compile(
    r"    private fun appendProjectStageMessage\(.*?\n    \}\n\n(?=    fun setProjectFavorite)",
    re.S,
)
append_replacement = """    private fun appendProjectStageMessage(
        chatId: String,
        number: Int,
        title: String,
        text: String,
        modelId: String?,
        result: OpenRouterClient.Result?
    ) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = \"assistant\",
            text = \"## Этап $number: $title\\n\\n$text\",
            generatedFiles = result?.files.orEmpty(),
            modelId = modelId,
            providerName = result?.providerName,
            costUsd = result?.costUsd,
            inputTokens = result?.inputTokens,
            outputTokens = result?.outputTokens
        )
        val updated = chatsRepository.updateChat(chatId) { chat ->
            chat.copy(messages = chat.messages + message, updatedAt = System.currentTimeMillis())
        }
        val updatedChat = updated.firstOrNull { it.id == chatId } ?: return
        _state.value = _state.value.copy(
            chats = updated,
            messages = if (_state.value.currentChatId == chatId) updatedChat.messages else _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

"""
text, count = append_pattern.subn(append_replacement, text, count=1)
if count != 1:
    raise SystemExit(f"atomic stage output: expected 1 function, got {count}")

# Natural-language Orchestrator plans: consecutive independent specialist actions
# can fan out. Dependent actions preserve the original linear semantics.
old_control_loop = """                var previous = \"\"
                plan.actions.forEachIndexed { index, action ->
                    _state.value = _state.value.copy(
                        busyLabel = \"◆ Оркестратор · шаг ${index + 1} из ${plan.actions.size}: ${action.title.ifBlank { action.type }}\"
                    )
                    val result = executeOrchestratorControlAction(project, orchestrator, action, previous, pending, network)
                    previous = result
                    appendOrchestratorLog(chatId, index + 1, action.title.ifBlank { action.type }, result)
                }
"""
new_control_loop = """                executeOrchestratorControlActions(
                    project = project,
                    orchestrator = orchestrator,
                    actions = plan.actions,
                    pending = pending,
                    network = network,
                    logChatId = chatId
                )
"""
text = replace_once(text, old_control_loop, new_control_loop, "natural Orchestrator loop")

control_helpers = r'''    private fun parallelControlCandidate(action: OrchestratorControlAction): Boolean =
        action.type in setOf("EXECUTE_CHAT", "RUN_CHAT_STAGES") &&
            !action.passPreviousResult &&
            action.sourceChatId.isNullOrBlank() &&
            !action.includeSourceResult &&
            !action.includeSourceFiles &&
            !action.persistSettings &&
            !action.targetChatId.isNullOrBlank()

    private suspend fun executeOrchestratorControlActions(
        project: Project,
        orchestrator: ChatSession,
        actions: List<OrchestratorControlAction>,
        pending: List<PendingAttachment>,
        network: RequestNetworkSession,
        logChatId: String
    ): String {
        var previous = ""
        var index = 0
        while (index < actions.size) {
            val first = actions[index]
            val batch = mutableListOf<Pair<Int, OrchestratorControlAction>>()
            if (parallelControlCandidate(first)) {
                val targets = mutableSetOf<String>()
                var cursor = index
                while (cursor < actions.size) {
                    val candidate = actions[cursor]
                    val target = candidate.targetChatId
                    if (!parallelControlCandidate(candidate) || target == null || !targets.add(target)) break
                    batch += cursor to candidate
                    cursor += 1
                }
            }

            if (batch.size > 1) {
                network.updatePhase("◆ Оркестратор · параллельно ${batch.size} исполнителя…")
                val completed = coroutineScope {
                    batch.map { (actionIndex, action) ->
                        async {
                            val outcome = try {
                                Result.success(
                                    executeOrchestratorControlAction(
                                        project, orchestrator, action, "", pending, network
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                Result.failure<String>(error)
                            }
                            Triple(actionIndex, action, outcome)
                        }
                    }.awaitAll()
                }.sortedBy { it.first }

                completed.forEach { (actionIndex, action, outcome) ->
                    outcome.getOrNull()?.let { result ->
                        appendOrchestratorLog(
                            logChatId,
                            actionIndex + 1,
                            action.title.ifBlank { action.type },
                            result
                        )
                    }
                }
                completed.firstOrNull { it.third.isFailure }
                    ?.third?.exceptionOrNull()?.let { throw it }
                previous = completed.last().third.getOrThrow()
                index += batch.size
            } else {
                val action = first
                network.updatePhase(
                    "◆ Оркестратор · шаг ${index + 1} из ${actions.size}: ${action.title.ifBlank { action.type }}"
                )
                val result = executeOrchestratorControlAction(
                    project, orchestrator, action, previous, pending, network
                )
                previous = result
                appendOrchestratorLog(
                    logChatId,
                    index + 1,
                    action.title.ifBlank { action.type },
                    result
                )
                index += 1
            }
        }
        return previous
    }

'''
text = replace_once(text, "    fun runOrchestrator(projectId: String): String? {\n", control_helpers + "    fun runOrchestrator(projectId: String): String? {\n", "control parallel helpers")

# Fixed Orchestrator scenarios get the same safe fan-out for independent chat steps.
fixed_loop_pattern = re.compile(
    r"                steps\.forEachIndexed \{ index, step ->.*?\n                    appendOrchestratorLog\(orchestratorId, index \+ 1, step\.title, result\)\n                \}\n",
    re.S,
)
fixed_loop_replacement = """                executeFixedOrchestratorSteps(
                    project = project,
                    orchestrator = orchestrator,
                    steps = steps,
                    network = network,
                    logChatId = orchestratorId
                )
"""
text, count = fixed_loop_pattern.subn(fixed_loop_replacement, text, count=1)
if count != 1:
    raise SystemExit(f"fixed Orchestrator loop: expected 1 block, got {count}")

fixed_helpers = r'''    private fun parallelFixedStepCandidate(step: OrchestratorStep): Boolean =
        step.type in setOf(OrchestratorStepType.EXECUTE_CHAT, OrchestratorStepType.RUN_CHAT_STAGES) &&
            !step.passPreviousResult &&
            !step.targetChatId.isNullOrBlank()

    private suspend fun executeFixedOrchestratorStep(
        project: Project,
        orchestrator: ChatSession,
        step: OrchestratorStep,
        previous: String,
        network: RequestNetworkSession
    ): String = when (step.type) {
        OrchestratorStepType.EXECUTE_CHAT -> {
            val target = chatsRepository.list().firstOrNull {
                it.id == step.targetChatId && it.projectId == project.id && !isOrchestratorChat(it.id)
            } ?: error("Для шага «${step.title}» не выбран чат")
            executeOrchestratorChatTask(
                project, target, step.prompt, previous, step.passPreviousResult, network = network
            )
        }
        OrchestratorStepType.RUN_CHAT_STAGES -> {
            val target = chatsRepository.list().firstOrNull {
                it.id == step.targetChatId && it.projectId == project.id && !isOrchestratorChat(it.id)
            } ?: error("Для шага «${step.title}» не выбран чат")
            if (target.stages.orEmpty().isEmpty()) error("У чата «${target.title}» нет этапов")
            executeOrchestratorStageSequence(
                project, target, target.stages.orEmpty(), step.prompt, previous,
                step.passPreviousResult, network = network
            )
        }
        OrchestratorStepType.RUN_PROJECT_STAGES -> {
            if (project.stages.orEmpty().isEmpty()) error("У проекта нет общих этапов")
            executeOrchestratorStageSequence(
                project, orchestrator, project.stages.orEmpty(), step.prompt, previous,
                step.passPreviousResult, network = network
            )
        }
    }

    private suspend fun executeFixedOrchestratorSteps(
        project: Project,
        orchestrator: ChatSession,
        steps: List<OrchestratorStep>,
        network: RequestNetworkSession,
        logChatId: String
    ): String {
        var previous = ""
        var index = 0
        while (index < steps.size) {
            val first = steps[index]
            val batch = mutableListOf<Pair<Int, OrchestratorStep>>()
            if (parallelFixedStepCandidate(first)) {
                val targets = mutableSetOf<String>()
                var cursor = index
                while (cursor < steps.size) {
                    val candidate = steps[cursor]
                    val target = candidate.targetChatId
                    if (!parallelFixedStepCandidate(candidate) || target == null || !targets.add(target)) break
                    batch += cursor to candidate
                    cursor += 1
                }
            }

            if (batch.size > 1) {
                network.updatePhase("Оркестратор · параллельно ${batch.size} исполнителя…")
                val completed = coroutineScope {
                    batch.map { (stepIndex, step) ->
                        async {
                            val outcome = try {
                                Result.success(executeFixedOrchestratorStep(project, orchestrator, step, "", network))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                Result.failure<String>(error)
                            }
                            Triple(stepIndex, step, outcome)
                        }
                    }.awaitAll()
                }.sortedBy { it.first }

                completed.forEach { (stepIndex, step, outcome) ->
                    outcome.getOrNull()?.let { result ->
                        appendOrchestratorLog(logChatId, stepIndex + 1, step.title, result)
                    }
                }
                completed.firstOrNull { it.third.isFailure }
                    ?.third?.exceptionOrNull()?.let { throw it }
                previous = completed.last().third.getOrThrow()
                index += batch.size
            } else {
                val step = first
                network.updatePhase("Оркестратор · шаг ${index + 1} из ${steps.size}: ${step.title}")
                val result = executeFixedOrchestratorStep(project, orchestrator, step, previous, network)
                previous = result
                appendOrchestratorLog(logChatId, index + 1, step.title, result)
                index += 1
            }
        }
        return previous
    }

'''
text = replace_once(text, "    private fun orchestratorPrompt(prompt: String, previous: String, passPrevious: Boolean): String = buildString {\n", fixed_helpers + "    private fun orchestratorPrompt(prompt: String, previous: String, passPrevious: Boolean): String = buildString {\n", "fixed parallel helpers")

write(path, text)
print("safe Orchestrator parallelism patch prepared")
