from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# ChatViewModel: while this chat's parent request is waiting for Local Shell,
# route typed text directly to the already-running worker instead of rejecting it
# as a second request. Persist it as a guidance user message for chat history.
p = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
s = p.read_text()
marker = '''    fun send(text: String) {
'''
helper = '''    private fun trySendActiveLocalShellGuidance(text: String): Boolean {
        val chatId = _state.value.currentChatId
        val active = AsyncJobEvents.localShellActivity.value ?: return false
        if (active.chatId != chatId || !RequestExecutionManager.hasActiveChat(chatId)) return false
        if (_state.value.mode != ChatMode.TEXT) return false

        val clean = text.trim()
        if (clean.isBlank()) return false
        if (!LocalShellRuntime.addGuidance(clean)) {
            _state.value = _state.value.copy(status = "Local Shell уже завершает работу. Дождитесь итогового ответа.")
            return true
        }

        val guidance = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean,
            deliveryState = "guidance"
        )
        val chats = chatsRepository.updateChat(chatId) { chat ->
            chat.copy(
                messages = chat.messages + guidance,
                updatedAt = System.currentTimeMillis()
            )
        }
        publishChats(chats)
        val pendingCount = _state.value.pendingAttachments.size
        _state.value = _state.value.copy(
            status = if (pendingCount > 0) {
                "Уточнение передано Local Shell. Новые вложения останутся для следующего запроса."
            } else {
                "Уточнение передано Local Shell"
            }
        )
        DiagnosticLog.record(
            context,
            "LOCAL_SHELL_GUIDANCE",
            "chat guidance queued; chat=${chatId.take(8)}; chars=${clean.length}; pendingAttachments=$pendingCount"
        )
        return true
    }

'''
if s.count(marker) != 1:
    raise SystemExit(f"send marker expected 1, found {s.count(marker)}")
s = s.replace(marker, helper + marker, 1)
needle = '''        DiagnosticLog.action(
            context,
            "send_pressed",
            "chat=${_state.value.currentChatId.take(8)}; mode=${_state.value.mode}; model=${currentTextModelId()}; promptChars=${text.length}; pending=${_state.value.pendingAttachments.size}"
        )
'''
replacement = needle + '''        if (trySendActiveLocalShellGuidance(text)) return
'''
s = replace_once(s, needle, replacement, "send guidance route")
p.write_text(s)


# ChatRepository: if guidance was sent while the original request was pending,
# insert the final assistant after the last guidance message, not before it.
p = Path("app/src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt")
s = p.read_text()
old = '''            val messages = chat.messages.toMutableList()
            messages[userIndex] = messages[userIndex].copy(deliveryState = if (completedAssistant == null) "failed" else null)
            if (completedAssistant != null) messages.add(userIndex + 1, completedAssistant)
            chat.copy(messages = messages, updatedAt = System.currentTimeMillis())
'''
new = '''            val messages = chat.messages.toMutableList()
            messages[userIndex] = messages[userIndex].copy(deliveryState = if (completedAssistant == null) "failed" else null)
            if (completedAssistant != null) {
                val lastGuidanceIndex = (userIndex + 1 until messages.size).lastOrNull { index ->
                    messages[index].role == "user" && messages[index].deliveryState == "guidance"
                }
                val assistantIndex = lastGuidanceIndex?.plus(1) ?: (userIndex + 1)
                messages.add(assistantIndex.coerceAtMost(messages.size), completedAssistant)
            }
            chat.copy(messages = messages, updatedAt = System.currentTimeMillis())
'''
s = replace_once(s, old, new, "finishRequest guidance ordering")
p.write_text(s)


# Compose UI: while Local Shell is active, keep Stop as its own button and show a
# separate Send button only when the user has typed guidance. Hide the disabled mic.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
s = p.read_text()
activity_anchor = '''    val localShellActivity by AsyncJobEvents.localShellActivity.collectAsState()
'''
activity_new = activity_anchor + '''    val localShellGuidanceHere = requestActiveHere && localShellActivity?.chatId == state.currentChatId
'''
s = replace_once(s, activity_anchor, activity_new, "localShellGuidanceHere")

trailing_start = s.index('''                    trailingIcon = {
''')
trailing_end = s.index('''                    placeholder = {
''', trailing_start)
trailing = s[trailing_start:trailing_end]
if trailing.count('''                            if (!imagePromptMode) {
''') != 1:
    raise SystemExit("composer mic condition not unique in trailingIcon")
trailing = trailing.replace(
    '''                            if (!imagePromptMode) {
''',
    '''                            if (!imagePromptMode && !requestActiveHere) {
''',
    1,
)
main_button = '''                            IconButton(
                                onClick = {
                                    if (requestActiveHere) {
                                        vm.stopGeneration()
'''
if trailing.count(main_button) != 1:
    raise SystemExit("main composer action button anchor not found")
guidance_button = '''                            if (localShellGuidanceHere && text.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        vm.send(text)
                                        text = ""
                                    },
                                    enabled = !nonRequestBusy,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Send,
                                        contentDescription = "Передать уточнение Local Shell",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
'''
trailing = trailing.replace(main_button, guidance_button + main_button, 1)
s = s[:trailing_start] + trailing + s[trailing_end:]

placeholder_old = '''                        when {
                            requestActiveHere -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),
'''
placeholder_new = '''                        when {
                            localShellGuidanceHere -> Text(
                                text = "Local Shell · ${formatRequestDuration(requestElapsedSeconds)} · можно уточнить задачу",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                            )
                            requestActiveHere -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),
'''
s = replace_once(s, placeholder_old, placeholder_new, "Local Shell guidance placeholder")
p.write_text(s)


# Extend existing source-level regression guard so this path cannot silently regress.
p = Path("app/src/test/java/com/ayuemin/ymnik/network/AgentLocalShellAwaitRegressionTest.kt")
s = p.read_text()
closing = '''    }
}
'''
extra = r'''

    @Test fun activeShellGuidanceUsesSameWorkerAndKeepsStopAvailable() {
        val vmSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
        ).first { it.isFile }.readText()
        val uiSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
        ).first { it.isFile }.readText()
        val repoSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt")
        ).first { it.isFile }.readText()

        val route = vmSource.indexOf("if (trySendActiveLocalShellGuidance(text)) return")
        val activeRequestGuard = vmSource.indexOf("if (RequestExecutionManager.hasActiveChat(chatId))", route)
        assertTrue(route >= 0)
        assertTrue(activeRequestGuard > route)
        assertTrue(vmSource.contains("LocalShellRuntime.addGuidance(clean)"))
        assertTrue(vmSource.contains("deliveryState = \"guidance\""))
        assertTrue(uiSource.contains("localShellGuidanceHere && text.isNotBlank()"))
        assertTrue(uiSource.contains("contentDescription = \"Передать уточнение Local Shell\""))
        assertTrue(uiSource.contains("vm.stopGeneration()"))
        assertTrue(repoSource.contains("deliveryState == \"guidance\""))
        assertTrue(repoSource.contains("lastGuidanceIndex?.plus(1)"))
    }
'''
if not s.endswith(closing):
    raise SystemExit("test closing anchor not found")
s = s[:-len(closing)] + '''    }
''' + extra + '''}
'''
p.write_text(s)
