from pathlib import Path

ROOT = Path('.')
VM = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
UI = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
CHANGELOG = ROOT / 'CHANGELOG.md'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected one match, found {count}')
    return text.replace(old, new, 1)


vm = VM.read_text(encoding='utf-8')

vm = replace_once(
    vm,
    'import com.ayuemin.ymnik.model.OrchestratorStep\nimport com.ayuemin.ymnik.model.OrchestratorStepType\n',
    'import com.ayuemin.ymnik.model.OrchestratorStep\nimport com.ayuemin.ymnik.model.OrchestratorStepType\nimport com.ayuemin.ymnik.model.OrchestratorControlAction\nimport com.ayuemin.ymnik.model.OrchestratorControlCodec\nimport com.ayuemin.ymnik.model.OrchestratorControlPlan\n',
    'orchestrator imports'
)

control_methods = r'''
    private fun orchestratorReasoningEffort(value: String?): ReasoningEffort? = when (value?.trim()?.lowercase()) {
        "minimal" -> ReasoningEffort.MINIMAL
        "low" -> ReasoningEffort.LOW
        "medium" -> ReasoningEffort.MEDIUM
        "high" -> ReasoningEffort.HIGH
        "xhigh" -> ReasoningEffort.XHIGH
        else -> null
    }

    private fun orchestratorControlSystemPrompt(project: Project, orchestrator: ChatSession): String {
        val projectChats = _state.value.chats.filter { it.projectId == project.id && !isOrchestratorChat(it.id) }
        val chatsDescription = projectChats.joinToString("\n") { chat ->
            val runtime = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
            val files = (chat.chatFiles.orEmpty().map { it.name } +
                chat.messages.flatMap { message -> message.generatedFiles.map { it.name } }).distinct().takeLast(12)
            val role = chat.assignedRole?.takeIf { it.isNotBlank() } ?: "не задана"
            "- id=${chat.id}; name=${chat.title}; role=$role; stages=${chat.stages.orEmpty().size}; " +
                "model=${runtime.modelId ?: chat.textModelOverride ?: "по умолчанию"}; web=${runtime.webSearchEnabled}; " +
                "reasoning=${runtime.reasoningEnabled}/${runtime.reasoningEffort.apiValue}; files=${files.joinToString().ifBlank { "нет" }}"
        }.ifBlank { "- В проекте пока нет рабочих чатов." }
        val skillDescription = _state.value.skills.joinToString("\n") { "- id=${it.id}; name=${it.name}" }
            .ifBlank { "- Библиотека навыков пуста." }
        val orchestratorFiles = orchestrator.chatFiles.orEmpty().joinToString { it.name }.ifBlank { "нет" }
        return """
            Ты ◆ Оркестратор проекта «${project.name}». Ты не суперагент и не принимаешь скрытых автономных решений.
            Твоя задача: понять обычную человеческую команду пользователя и либо ответить разговорно, либо составить безопасный линейный план действий Umnik.

            ДОСТУПНЫЕ РАБОЧИЕ ЧАТЫ ПРОЕКТА:
            $chatsDescription

            НАВЫКИ:
            $skillDescription

            ФАЙЛЫ, ЗАКРЕПЛЁННЫЕ В ЧАТЕ ОРКЕСТРАТОРА: $orchestratorFiles
            ОБЩИЕ ЭТАПЫ ПРОЕКТА: ${project.stages.orEmpty().size}

            Разрешённые действия:
            EXECUTE_CHAT — реально выполнить выбранный чат с указанным prompt.
            RUN_CHAT_STAGES — реально выполнить индивидуальные этапы выбранного чата.
            RUN_PROJECT_STAGES — выполнить общие этапы проекта.
            SHOW_LAST_RESULT — показать последний ответ выбранного чата без нового запроса к модели.
            TRANSFER_FILES — скопировать файлы из sourceChatId или из чата Оркестратора в targetChatId.
            UPDATE_CHAT_SETTINGS — изменить постоянные настройки выбранного чата только если пользователь явно просит сохранить/изменить их постоянно.

            Для EXECUTE_CHAT/RUN_CHAT_STAGES можно временно переопределить модель, поиск, reasoning, силу reasoning и навыки.
            Временные параметры действуют только на этот запуск и НЕ меняют постоянные настройки, если persistSettings=false.
            persistSettings=true ставь только при явных словах вроде «сохрани», «постоянно», «сделай настройкой чата».
            useOrchestratorFiles=true означает передать файлы, которые пользователь приложил/закрепил в ◆ Оркестраторе.
            includeSourceResult=true означает добавить последний ответ sourceChatId к заданию.
            includeSourceFiles=true означает приложить файлы sourceChatId к заданию.
            persistTransferredFiles=true означает закрепить передаваемые файлы в целевом чате, а не использовать их только один раз.
            passPreviousResult=true передаёт текст результата предыдущего шага текущему шагу.

            ВАЖНО:
            - Не придумывай id. Используй только id из списка выше.
            - Никогда не выбирай сам ◆ Оркестратор как targetChatId рабочего действия.
            - Максимум 8 линейных действий.
            - Никаких циклов, скрытых повторов, if/else и автономных проверок. Если пользователь требует условие/цикл, объясни в reply, что это следующий уровень и сейчас нужен линейный вариант; execute=false.
            - Если пользователь просто разговаривает, спрашивает совет или обсуждает проект, верни actions=[] и нормальный reply.
            - Если пользователь просит «только покажи план», выставь execute=false, но actions заполни.
            - Для доработки существующего результата используй sourceChatId самого чата, includeSourceResult=true и новый prompt.
            - Если сказано «передай файл/файлы», предпочитай TRANSFER_FILES с persistTransferredFiles=true.
            - Если сказано «передай результат», используй includeSourceResult=true; копировать текст вручную в prompt не нужно.

            Верни ТОЛЬКО JSON без markdown и без пояснений вокруг него:
            {
              "reply": "короткое объяснение пользователю",
              "execute": true,
              "actions": [
                {
                  "type": "EXECUTE_CHAT",
                  "title": "человеческое название шага",
                  "targetChatId": "точный id",
                  "sourceChatId": null,
                  "prompt": "точный промпт исполнителю",
                  "passPreviousResult": true,
                  "includeSourceResult": false,
                  "includeSourceFiles": false,
                  "persistTransferredFiles": false,
                  "useOrchestratorFiles": false,
                  "fileNameContains": null,
                  "temporaryModelId": null,
                  "temporaryWebSearchEnabled": null,
                  "temporaryReasoningEnabled": null,
                  "temporaryReasoningEffort": null,
                  "temporarySkillIds": null,
                  "persistSettings": false
                }
              ]
            }
        """.trimIndent()
    }

    private suspend fun planOrchestratorControl(
        project: Project,
        orchestrator: ChatSession,
        command: String,
        history: List<ChatMessage>
    ): OrchestratorControlPlan {
        val runtime = projectAutomation.profile(orchestrator.id) ?: defaultRuntimeProfile(orchestrator)
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == orchestrator.connectionProfileId } ?: openRouterProfile()
        if (!isProfileConfigured(profile)) error("Подключение Оркестратора не настроено")
        val apiKey = secrets.getProfileApiKey(profile.id).orEmpty()
        if (apiKey.isBlank()) error("Для Оркестратора не сохранён API-ключ")
        val modelId = runtime.modelId ?: orchestrator.textModelOverride ?: loadTextModelForProfile(profile)
        val infos = runCatching { textModelsForProfile(profile) }.getOrDefault(emptyList())
        val info = infos.firstOrNull { it.id == modelId }
        val actualReasoning = runtime.reasoningEnabled && info?.supportsReasoning == true &&
            (info.reasoningEfforts.isEmpty() || runtime.reasoningEffort.apiValue in info.reasoningEfforts)
        val effort = if (actualReasoning && info?.supportsReasoningEffort == true) runtime.reasoningEffort.apiValue else null
        val requestInfo = (info ?: ModelInfo(modelId)).copy(
            contextLength = listOfNotNull(info?.contextLength, profile.contextLimitTokens).minOrNull()
        )
        val systemPrompt = orchestratorControlSystemPrompt(project, orchestrator)
        val selectedHistory = history.takeLast(18)
        val result = if (profile.type == ProviderType.OPENROUTER) {
            api.chat(
                apiKey,
                modelId,
                selectedHistory,
                command,
                emptyList(),
                systemPrompt,
                false,
                actualReasoning,
                effort,
                false,
                effectiveTextBaseUrl(profile),
                requestInfo
            )
        } else {
            compatibleApi.chat(
                apiKey,
                effectiveTextBaseUrl(profile),
                modelId,
                selectedHistory,
                command,
                emptyList(),
                systemPrompt,
                requestInfo
            )
        }
        return runCatching { OrchestratorControlCodec.parse(result.text) }
            .getOrElse {
                OrchestratorControlPlan(
                    reply = "Я понял сообщение, но не смог надёжно превратить его в команды проекта. Переформулируйте поручение чуть конкретнее.\n\nОтвет модели: ${result.text.take(1200)}",
                    execute = false
                )
            }
    }

    private fun orchestratorControlPlanText(project: Project, plan: OrchestratorControlPlan): String {
        val names = _state.value.chats.associate { it.id to it.title }
        fun actionLine(index: Int, action: OrchestratorControlAction): String {
            val target = action.targetChatId?.let { names[it] ?: it } ?: "проект"
            val source = action.sourceChatId?.let { names[it] ?: it }
            val verb = when (action.type) {
                "EXECUTE_CHAT" -> "Выполнить чат «$target»"
                "RUN_CHAT_STAGES" -> "Выполнить этапы чата «$target»"
                "RUN_PROJECT_STAGES" -> "Выполнить общие этапы проекта «${project.name}»"
                "SHOW_LAST_RESULT" -> "Показать последний результат «$target»"
                "TRANSFER_FILES" -> "Передать файлы${source?.let { " из «$it»" }.orEmpty()} → «$target»"
                "UPDATE_CHAT_SETTINGS" -> "Изменить постоянные настройки чата «$target»"
                else -> action.type
            }
            val flags = buildList {
                if (action.temporaryWebSearchEnabled == true) add("поиск")
                if (action.temporaryReasoningEnabled == true) add("размышление ${action.temporaryReasoningEffort ?: ""}".trim())
                action.temporaryModelId?.let { add("модель $it") }
                if (action.includeSourceResult) add("результат источника")
                if (action.includeSourceFiles || action.useOrchestratorFiles) add("файлы")
            }
            return "${index + 1}. $verb" + if (flags.isEmpty()) "" else " · ${flags.joinToString(" · ")}"
        }
        val lines = plan.actions.mapIndexed(::actionLine)
        return buildString {
            if (plan.reply.isNotBlank()) appendLine(plan.reply)
            if (lines.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### План")
                lines.forEach(::appendLine)
                appendLine()
                append(if (plan.execute) "Запускаю линейный план." else "План показан без запуска.")
            }
        }.trim().ifBlank { "Готов помочь с проектом." }
    }

    private fun latestAssistantText(chat: ChatSession): String =
        chat.messages.asReversed().firstOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text.orEmpty()

    private fun generatedFileAttachment(file: GeneratedFile): PendingAttachment = PendingAttachment(
        uri = "generated://${file.id}",
        name = file.name,
        mimeType = file.mimeType,
        size = file.size,
        localPath = file.localPath
    )

    private fun filesFromProjectChat(chat: ChatSession, filter: String?): List<PendingAttachment> {
        val generated = chat.messages.asReversed()
            .filter { it.role == "assistant" && it.generatedFiles.isNotEmpty() }
            .take(8)
            .flatMap { it.generatedFiles }
            .map(::generatedFileAttachment)
        val persistent = chat.chatFiles.orEmpty().map(::chatFileAsAttachment)
        val needle = filter?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return (persistent + generated)
            .filter { needle == null || it.name.lowercase().contains(needle) }
            .distinctBy { "${it.name.lowercase()}::${it.size}" }
            .take(20)
    }

    private fun persistControlFiles(targetChatId: String, files: List<PendingAttachment>): List<PendingAttachment> {
        if (files.isEmpty()) return emptyList()
        val stored = chatsRepository.list()
        val target = stored.firstOrNull { it.id == targetChatId } ?: return emptyList()
        val existing = target.chatFiles.orEmpty().associateBy { "${it.name.lowercase()}::${it.size}" }
        val imported = files.mapNotNull { attachment ->
            val key = "${attachment.name.lowercase()}::${attachment.size}"
            if (key in existing) return@mapNotNull null
            runCatching { chatFilesRepository.importFile(targetChatId, attachment) }.getOrNull()
        }
        if (imported.isNotEmpty()) {
            val latest = chatsRepository.list()
            val updated = latest.map { chat ->
                if (chat.id == targetChatId) chat.copy(
                    chatFiles = (chat.chatFiles.orEmpty() + imported).distinctBy { "${it.name.lowercase()}::${it.size}" },
                    updatedAt = System.currentTimeMillis()
                ) else chat
            }
            chatsRepository.save(updated)
            publishChats(updated)
        }
        return files
    }

    private fun runtimeForControlAction(chat: ChatSession, action: OrchestratorControlAction): ProjectChatRuntimeProfile {
        val base = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
        val knownSkillIds = _state.value.skills.map { it.id }.toSet()
        val requestedSkills = action.temporarySkillIds?.filter { it in knownSkillIds }?.toSet()
        return base.copy(
            modelId = action.temporaryModelId ?: base.modelId,
            webSearchEnabled = action.temporaryWebSearchEnabled ?: base.webSearchEnabled,
            reasoningEnabled = action.temporaryReasoningEnabled ?: base.reasoningEnabled,
            reasoningEffort = orchestratorReasoningEffort(action.temporaryReasoningEffort) ?: base.reasoningEffort,
            skillIds = requestedSkills ?: base.skillIds
        )
    }

    private fun persistRuntimeFromControl(chat: ChatSession, runtime: ProjectChatRuntimeProfile) {
        projectAutomation.saveProfile(chat.id, runtime)
        prefs.edit().putStringSet(chatSkillsKey(chat.id), runtime.skillIds).apply()
        val latest = chatsRepository.list()
        val updated = latest.map {
            if (it.id == chat.id) it.copy(textModelOverride = runtime.modelId, updatedAt = System.currentTimeMillis()) else it
        }
        chatsRepository.save(updated)
        publishChats(updated)
        if (_state.value.currentChatId == chat.id) {
            _state.value = _state.value.copy(
                currentChatTextModel = runtime.modelId,
                webSearchEnabled = runtime.webSearchEnabled,
                reasoningEnabled = runtime.reasoningEnabled,
                reasoningEffort = runtime.reasoningEffort,
                activeSkillIds = runtime.skillIds
            )
        }
    }

    private fun actionExtraFiles(
        project: Project,
        orchestrator: ChatSession,
        action: OrchestratorControlAction,
        commandAttachments: List<PendingAttachment>
    ): List<PendingAttachment> {
        val latestChats = chatsRepository.list()
        val fromSource = if (action.includeSourceFiles) {
            action.sourceChatId?.let { id -> latestChats.firstOrNull { it.id == id && it.projectId == project.id } }
                ?.let { filesFromProjectChat(it, action.fileNameContains) }.orEmpty()
        } else emptyList()
        val fromOrchestrator = if (action.useOrchestratorFiles) {
            val refreshed = latestChats.firstOrNull { it.id == orchestrator.id } ?: orchestrator
            (commandAttachments + filesFromProjectChat(refreshed, action.fileNameContains))
        } else emptyList()
        return (fromSource + fromOrchestrator)
            .filter { action.fileNameContains.isNullOrBlank() || it.name.contains(action.fileNameContains!!, ignoreCase = true) }
            .distinctBy { "${it.name.lowercase()}::${it.size}" }
    }

    private suspend fun executeOrchestratorControlAction(
        project: Project,
        orchestrator: ChatSession,
        action: OrchestratorControlAction,
        previous: String,
        commandAttachments: List<PendingAttachment>
    ): String {
        fun targetChat(): ChatSession {
            val id = action.targetChatId ?: error("Не выбран целевой чат")
            return chatsRepository.list().firstOrNull { it.id == id && it.projectId == project.id && !isOrchestratorChat(it.id) }
                ?: error("Целевой чат для шага «${action.title.ifBlank { action.type }}» не найден")
        }
        val sourceText = if (action.includeSourceResult) {
            action.sourceChatId?.let { id ->
                chatsRepository.list().firstOrNull { it.id == id && it.projectId == project.id }?.let(::latestAssistantText)
            }.orEmpty()
        } else ""
        val carried = buildString {
            if (action.passPreviousResult && previous.isNotBlank()) append(previous)
            if (sourceText.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append("===== РЕЗУЛЬТАТ ВЫБРАННОГО ЧАТА =====\n").append(sourceText)
            }
        }
        return when (action.type) {
            "SHOW_LAST_RESULT" -> {
                val chat = targetChat()
                latestAssistantText(chat).ifBlank { "У чата «${chat.title}» пока нет ответа модели." }
            }
            "TRANSFER_FILES" -> {
                val target = targetChat()
                val latest = chatsRepository.list()
                val sourceFiles = action.sourceChatId?.let { id -> latest.firstOrNull { it.id == id && it.projectId == project.id } }
                    ?.let { filesFromProjectChat(it, action.fileNameContains) }.orEmpty()
                val orchestratorFiles = if (action.useOrchestratorFiles) {
                    (commandAttachments + filesFromProjectChat(latest.firstOrNull { it.id == orchestrator.id } ?: orchestrator, action.fileNameContains))
                } else emptyList()
                val files = (sourceFiles + orchestratorFiles).distinctBy { "${it.name.lowercase()}::${it.size}" }
                if (files.isEmpty()) error("Не нашёл файлов для передачи")
                persistControlFiles(target.id, files)
                "Передано в чат «${target.title}»: ${files.joinToString { it.name }}"
            }
            "UPDATE_CHAT_SETTINGS" -> {
                val target = targetChat()
                val runtime = runtimeForControlAction(target, action)
                persistRuntimeFromControl(target, runtime)
                "Настройки чата «${target.title}» сохранены: модель ${runtime.modelId ?: "по умолчанию"}, " +
                    "поиск ${if (runtime.webSearchEnabled) "включён" else "выключен"}, " +
                    "размышление ${if (runtime.reasoningEnabled) runtime.reasoningEffort.apiValue else "выключено"}."
            }
            "EXECUTE_CHAT" -> {
                var target = targetChat()
                val runtime = runtimeForControlAction(target, action)
                if (action.persistSettings) {
                    persistRuntimeFromControl(target, runtime)
                    target = chatsRepository.list().firstOrNull { it.id == target.id } ?: target
                }
                var extra = actionExtraFiles(project, orchestrator, action, commandAttachments)
                if (action.persistTransferredFiles && extra.isNotEmpty()) {
                    persistControlFiles(target.id, extra)
                    target = chatsRepository.list().firstOrNull { it.id == target.id } ?: target
                }
                executeOrchestratorChatTask(
                    project = project,
                    requested = target,
                    prompt = action.prompt,
                    previous = carried,
                    passPrevious = carried.isNotBlank(),
                    runtimeOverride = runtime,
                    extraAttachments = extra
                )
            }
            "RUN_CHAT_STAGES" -> {
                var target = targetChat()
                if (target.stages.orEmpty().isEmpty()) error("У чата «${target.title}» нет индивидуальных этапов")
                val runtime = runtimeForControlAction(target, action)
                if (action.persistSettings) {
                    persistRuntimeFromControl(target, runtime)
                    target = chatsRepository.list().firstOrNull { it.id == target.id } ?: target
                }
                val extra = actionExtraFiles(project, orchestrator, action, commandAttachments)
                if (action.persistTransferredFiles && extra.isNotEmpty()) persistControlFiles(target.id, extra)
                executeOrchestratorStageSequence(
                    project = project,
                    requested = target,
                    stages = target.stages.orEmpty(),
                    prompt = action.prompt,
                    previous = carried,
                    passPrevious = carried.isNotBlank(),
                    runtimeOverride = runtime,
                    extraAttachments = extra
                )
            }
            "RUN_PROJECT_STAGES" -> {
                if (project.stages.orEmpty().isEmpty()) error("У проекта нет общих этапов")
                val runtime = runtimeForControlAction(orchestrator, action)
                val extra = actionExtraFiles(project, orchestrator, action, commandAttachments)
                executeOrchestratorStageSequence(
                    project = project,
                    requested = orchestrator,
                    stages = project.stages.orEmpty(),
                    prompt = action.prompt,
                    previous = carried,
                    passPrevious = carried.isNotBlank(),
                    runtimeOverride = runtime,
                    extraAttachments = extra
                )
            }
            else -> error("Неизвестное действие Оркестратора: ${action.type}")
        }
    }

    private fun sendOrchestratorControl(
        command: String,
        pending: List<PendingAttachment>,
        persistentChatFiles: List<PendingAttachment>
    ) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chatId = _state.value.currentChatId
        val orchestrator = _state.value.chats.firstOrNull { it.id == chatId && isOrchestratorChat(it.id) } ?: return
        val project = orchestrator.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } } ?: return
        val clean = command.trim().ifBlank {
            if (pending.isNotEmpty() || persistentChatFiles.isNotEmpty())
                "Посмотри приложенные материалы и предложи, как лучше распределить работу между чатами проекта. Только покажи план, пока не запускай."
            else return
        }
        val before = orchestrator.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean,
            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct(),
            deliveryState = "pending"
        )
        val nextMessages = before + user
        val nextChats = replaceChatMessages(_state.value.chats, chatId, nextMessages, null)
        chatsRepository.save(nextChats)
        _state.value = _state.value.copy(
            messages = nextMessages,
            chats = nextChats,
            pendingAttachments = emptyList(),
            isLoading = true,
            requestActive = true,
            busyLabel = "◆ Оркестратор · разбираю поручение…",
            status = null
        )
        val requestId = ++requestGeneration
        activeRequestPending = pending
        activeRequestJob = launchRequest(chatId, user.id, "◆ Оркестратор · ${project.name}") {
            var failed: Throwable? = null
            try {
                val plan = planOrchestratorControl(project, orchestrator, clean, before)
                if (requestId != requestGeneration) return@launchRequest
                val planMessage = ChatMessage(
                    UUID.randomUUID().toString(),
                    "assistant",
                    orchestratorControlPlanText(project, plan),
                    modelId = projectAutomation.profile(orchestrator.id)?.modelId ?: orchestrator.textModelOverride
                )
                val finished = chatsRepository.finishRequest(chatId, user.id, planMessage)
                publishChats(finished)
                if (!plan.execute || plan.actions.isEmpty()) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        requestActive = false,
                        busyLabel = null,
                        status = null
                    )
                    playReadySound()
                    return@launchRequest
                }
                var previous = ""
                plan.actions.forEachIndexed { index, action ->
                    _state.value = _state.value.copy(
                        busyLabel = "◆ Оркестратор · шаг ${index + 1} из ${plan.actions.size}: ${action.title.ifBlank { action.type }}"
                    )
                    val result = executeOrchestratorControlAction(project, orchestrator, action, previous, pending)
                    previous = result
                    appendOrchestratorLog(chatId, index + 1, action.title.ifBlank { action.type }, result)
                }
                appendOrchestratorLog(chatId, 0, "Оркестратор", "Поручение выполнено. Шагов: ${plan.actions.size}.")
                playReadySound()
            } catch (error: Throwable) {
                failed = error
                val current = chatsRepository.list().firstOrNull { it.id == chatId }
                val pendingStillExists = current?.messages?.any { it.id == user.id && it.deliveryState == "pending" } == true
                if (pendingStillExists) {
                    val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                    publishChats(failedChats)
                }
                appendOrchestratorLog(chatId, -1, "Поручение остановлено", error.message ?: "Ошибка выполнения")
            } finally {
                cleanupTempAttachments(pending)
                if (requestId == requestGeneration) {
                    activeRequestJob = null
                    activeRequestPending = emptyList()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        requestActive = false,
                        busyLabel = null,
                        status = failed?.message
                    )
                }
            }
        }
    }

'''

vm = replace_once(
    vm,
    '    fun runOrchestrator(projectId: String): String? {\n',
    control_methods + '    fun runOrchestrator(projectId: String): String? {\n',
    'insert conversational orchestrator methods'
)

vm = replace_once(
    vm,
    '''    private suspend fun executeOrchestratorChatTask(\n        project: Project,\n        requested: ChatSession,\n        prompt: String,\n        previous: String,\n        passPrevious: Boolean\n    ): String {\n''',
    '''    private suspend fun executeOrchestratorChatTask(\n        project: Project,\n        requested: ChatSession,\n        prompt: String,\n        previous: String,\n        passPrevious: Boolean,\n        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList()\n    ): String {\n''',
    'chat task signature'
)
vm = replace_once(
    vm,
    '        val runtime = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)\n',
    '        val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)\n',
    'chat task runtime override'
)
vm = replace_once(
    vm,
    '        val attachments = orchestratorAttachments(project, chat, modelInfo)\n',
    '        val attachments = (orchestratorAttachments(project, chat, modelInfo) + extraAttachments).distinctBy { it.localPath ?: it.uri }\n',
    'chat task extra attachments'
)

vm = replace_once(
    vm,
    '''    private suspend fun executeOrchestratorStageSequence(\n        project: Project,\n        requested: ChatSession,\n        stages: List<ProjectStage>,\n        prompt: String,\n        previous: String,\n        passPrevious: Boolean\n    ): String {\n''',
    '''    private suspend fun executeOrchestratorStageSequence(\n        project: Project,\n        requested: ChatSession,\n        stages: List<ProjectStage>,\n        prompt: String,\n        previous: String,\n        passPrevious: Boolean,\n        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList()\n    ): String {\n''',
    'stage sequence signature'
)
# This is the second occurrence after the chat-task replacement.
old_runtime = '            val runtime = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)\n'
if vm.count(old_runtime) < 1:
    raise RuntimeError('stage runtime override: no remaining match')
vm = vm.replace(old_runtime, '            val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)\n', 1)

vm = replace_once(
    vm,
    '''            val attachments = (\n                orchestratorAttachments(project, chat, modelInfo) +\n                    stage.files.orEmpty().map { PendingAttachment("stage://${it.id}", it.name, it.mimeType, it.size, it.localPath) } +\n                    sourceChats.flatMap { it.chatFiles.orEmpty() }.map(::chatFileAsAttachment)\n            ).distinctBy { it.localPath ?: it.uri }\n''',
    '''            val attachments = (\n                orchestratorAttachments(project, chat, modelInfo) +\n                    extraAttachments +\n                    stage.files.orEmpty().map { PendingAttachment("stage://${it.id}", it.name, it.mimeType, it.size, it.localPath) } +\n                    sourceChats.flatMap { it.chatFiles.orEmpty() }.map(::chatFileAsAttachment)\n            ).distinctBy { it.localPath ?: it.uri }\n''',
    'stage extra attachments'
)

send_marker = '''        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }\n        val before = _state.value.messages\n'''
send_replacement = '''        if (mode == ChatMode.TEXT && currentChat != null && isOrchestratorChat(currentChat.id)) {\n            sendOrchestratorControl(clean, pending, persistentChatFiles)\n            return\n        }\n\n        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }\n        val before = _state.value.messages\n'''
vm = replace_once(vm, send_marker, send_replacement, 'send orchestrator interception')

VM.write_text(vm, encoding='utf-8')

ui = UI.read_text(encoding='utf-8')
ui = replace_once(
    ui,
    '''                            imagePromptMode -> Text("Опишите изображение")\n                        }\n''',
    '''                            imagePromptMode -> Text("Опишите изображение")\n                            currentChat != null && vm.isOrchestratorChat(currentChat.id) -> Text("Поручите работу проекту обычным языком")\n                        }\n''',
    'orchestrator composer placeholder'
)
UI.write_text(ui, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = replace_once(gradle, '// Umnik v1.12.0', '// Umnik v1.13.0', 'version comment')
gradle = replace_once(gradle, 'versionCode = 112', 'versionCode = 113', 'version code')
gradle = replace_once(gradle, 'versionName = "1.12.0"', 'versionName = "1.13.0"', 'version name')
GRADLE.write_text(gradle, encoding='utf-8')

changelog = CHANGELOG.read_text(encoding='utf-8')
entry = '''# Changelog\n\n## v1.13.0\n\n- Чат `◆ Оркестратор` стал разговорным пультом проекта: обычная фраза превращается в ограниченный линейный план реальных действий.\n- Оркестратор умеет выполнить конкретный чат с собственным промптом, запустить его индивидуальные этапы или общие этапы проекта.\n- Поддерживаются многоходовки до 8 шагов с передачей результата предыдущего шага следующему.\n- Можно попросить показать последний результат выбранного чата без нового запроса к модели.\n- Добавлен обмен файлами между чатами проекта: файлы можно передать на один запуск или закрепить в целевом чате.\n- Для доработки Оркестратор может взять последний результат выбранного чата, вернуть его тому же или другому исполнителю и добавить новое человеческое замечание.\n- На отдельный запуск можно временно переопределить модель, веб-поиск, reasoning, силу reasoning и набор навыков; постоянные настройки меняются только по явной команде пользователя.\n- Если пользователь просит условные ветвления или циклы, Оркестратор их не запускает: v1.13.0 сознательно остаётся линейным и управляемым человеком.\n- В поле ввода Оркестратора появилась подсказка, что поручения можно писать обычным языком.\n- Версия: 1.13.0 / versionCode 113.\n\n'''
if not changelog.startswith('# Changelog\n\n'):
    raise RuntimeError('unexpected changelog header')
changelog = entry + changelog[len('# Changelog\n\n'):]
CHANGELOG.write_text(changelog, encoding='utf-8')

print('v1.13.0 conversational orchestrator patch applied')
