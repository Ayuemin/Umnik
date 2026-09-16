from pathlib import Path

VM = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
CHANGELOG = Path("CHANGELOG.md")

text = VM.read_text()

# 1) Direct project/chat stage runs must keep the originating chat state and use
#    the same process-wide foreground execution owner as normal chat/orchestrator.
start = text.index("    private fun runStageSequence(\n")
end = text.index("    private fun appendProjectStageMessage(\n", start)
section = text[start:end]

old = "        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null\n        val baseHistory = currentChat.messages"
new = "        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null\n        // Freeze the originating chat runtime. Navigation may change global UI state while stages run.\n        val launchState = _state.value\n        val baseHistory = currentChat.messages"
if section.count(old) != 1:
    raise SystemExit("runStageSequence currentChat marker mismatch")
section = section.replace(old, new, 1)

old = "        projectStagesJob = viewModelScope.launch {\n            val results = mutableListOf<Pair<ProjectStage, String>>()"
new = "        projectStagesJob = launchRequest(chatId, user.id, \"Этапы $sequenceName · ${project.name}\") {\n            val results = mutableListOf<Pair<ProjectStage, String>>()"
if section.count(old) != 1:
    raise SystemExit("runStageSequence launcher marker mismatch")
section = section.replace(old, new, 1)

if section.count("return@launch") != 2:
    raise SystemExit(f"Expected 2 return@launch in runStageSequence, found {section.count('return@launch')}")
section = section.replace("return@launch", "return@launchRequest")

old = "                    val currentState = _state.value\n"
new = "                    val currentState = launchState\n"
if section.count(old) != 1:
    raise SystemExit("runStageSequence currentState marker mismatch")
section = section.replace(old, new, 1)

old = "                    _state.value = _state.value.copy(busyLabel = \"Этап ${index + 1} из ${stages.size}: ${stage.title}\")\n                    DiagnosticLog.record("
new = "                    val phaseLabel = \"Этап ${index + 1} из ${stages.size}: ${stage.title}\"\n                    RequestExecutionManager.updatePhase(context, phaseLabel)\n                    _state.value = _state.value.copy(busyLabel = phaseLabel)\n                    DiagnosticLog.record("
if section.count(old) != 1:
    raise SystemExit("runStageSequence phase marker mismatch")
section = section.replace(old, new, 1)

text = text[:start] + section + text[end:]

# 2) Normal chat requests must not read mutable global chat state after navigation.
old = """        val webSearchEnabled = _state.value.webSearchEnabled
        val reasoningEnabled = _state.value.reasoningEnabled
        val reasoningEffort = _state.value.reasoningEffort
        val requestId = ++requestGeneration
"""
new = """        val webSearchEnabled = _state.value.webSearchEnabled
        val reasoningEnabled = _state.value.reasoningEnabled
        val reasoningEffort = _state.value.reasoningEffort
        // Everything below belongs to the chat that launched the request. Do not read
        // mutable current-chat state from inside the background job after navigation.
        val requestSkillIds = _state.value.activeSkillIds
        val requestTextModelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }
            ?: _state.value.modelCatalog.firstOrNull { it.id == textModel }
        val requestProjectTextAttachments = if (mode == ChatMode.TEXT) {
            currentProject?.files.orEmpty().map { file ->
                PendingAttachment(
                    uri = "project://${file.id}",
                    name = file.name,
                    mimeType = file.mimeType,
                    size = file.size,
                    localPath = file.localPath
                )
            }.filter { attachmentAllowed(it).first }
        } else emptyList()
        val requestPersistentTextAttachments = if (mode == ChatMode.TEXT) {
            persistentChatFiles.filter { attachmentAllowed(it).first }
        } else emptyList()
        val requestProjectImages = if (mode == ChatMode.IMAGE) {
            currentProject?.files.orEmpty()
                .filter { it.mimeType.startsWith("image/") && imageInfo?.accepts("image") == true }
                .map { file -> PendingAttachment(
                    uri = "project://${file.id}",
                    name = file.name,
                    mimeType = file.mimeType,
                    size = file.size,
                    localPath = file.localPath
                ) }
        } else emptyList()
        val requestId = ++requestGeneration
"""
if text.count(old) != 1:
    raise SystemExit("send request snapshot insertion marker mismatch")
text = text.replace(old, new, 1)

old = """                    ChatMode.TEXT -> {
                        val skillIds = _state.value.activeSkillIds
                        val skillText = skills.promptFor(skillIds)
                        val projectFiles = currentProject?.files.orEmpty().map { file ->
                            PendingAttachment(
                                uri = "project://${file.id}",
                                name = file.name,
                                mimeType = file.mimeType,
                                size = file.size,
                                localPath = file.localPath
                            )
                        }.filter { attachmentAllowed(it).first }
                        val modelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }
"""
new = """                    ChatMode.TEXT -> {
                        val skillText = skills.promptFor(requestSkillIds)
                        val projectFiles = requestProjectTextAttachments
                        val modelInfo = requestTextModelInfo
"""
if text.count(old) != 1:
    raise SystemExit("send mutable text-state block mismatch")
text = text.replace(old, new, 1)

old = """                        val allAttachments = (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)
                            .distinctBy { it.localPath ?: it.uri }
"""
new = """                        val allAttachments = (pending + requestPersistentTextAttachments + projectFiles)
                            .distinctBy { it.localPath ?: it.uri }
"""
if text.count(old) != 1:
    raise SystemExit("send attachment filtering block mismatch")
text = text.replace(old, new, 1)

old = """                    ChatMode.IMAGE -> {
                        val projectImages = currentProject?.files.orEmpty()
                            .filter { it.mimeType.startsWith("image/") && currentImageModelInfo()?.accepts("image") == true }
                            .map { file -> PendingAttachment(
                                uri = "project://${file.id}",
                                name = file.name,
                                mimeType = file.mimeType,
                                size = file.size,
                                localPath = file.localPath
                            ) }
                        val projectPrefix = buildImageProjectPrompt(currentProject, currentChat)
"""
new = """                    ChatMode.IMAGE -> {
                        val projectImages = requestProjectImages
                        val projectPrefix = buildImageProjectPrompt(currentProject, currentChat)
"""
if text.count(old) != 1:
    raise SystemExit("send mutable image-state block mismatch")
text = text.replace(old, new, 1)

VM.write_text(text)

changelog = CHANGELOG.read_text()
needle = "- Та же развязка работает для проектных чатов и Оркестратора: выполняющаяся работа больше не удерживает пользователя на одном экране.\n"
addition = needle + "- Прямой запуск этапов проекта/чата переведён на тот же foreground/wake-lock механизм, а параметры активного запроса фиксируются в момент отправки и больше не меняются при переходе в другой чат.\n"
if needle in changelog and "параметры активного запроса фиксируются" not in changelog:
    changelog = changelog.replace(needle, addition, 1)
CHANGELOG.write_text(changelog)
