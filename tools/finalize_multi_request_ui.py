from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, got {count}")
    return text.replace(old, new, 1)


# Chat UI: only the current chat's request may block its composer/controls.
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val requestActiveHere = state.requestActive && vm.activeRequestChatId() == state.currentChatId
    val requestActiveElsewhere = state.requestActive && !requestActiveHere
''',
    '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)
    val requestActiveElsewhere = state.requestActive && !requestActiveHere
''',
    "current-chat request state",
)

text = replace_once(
    text,
    'if (!microphoneAvailable || nonRequestBusy || state.requestActive || imagePromptMode) return',
    'if (!microphoneAvailable || nonRequestBusy || requestActiveHere || imagePromptMode) return',
    "voice per-chat guard",
)

text = replace_once(
    text,
    '''                                enabled = !state.requestActive
''',
    '''                                enabled = !requestActiveHere
''',
    "image prompt close per-chat guard",
)

text = replace_once(
    text,
    '''                                    enabled = isRecording || (!state.isLoading && !state.requestActive && microphoneAvailable),
''',
    '''                                    enabled = isRecording || (!nonRequestBusy && !requestActiveHere && microphoneAvailable),
''',
    "microphone per-chat enablement",
)

text = replace_once(
    text,
    '''                                enabled = requestActiveHere || isRecording || (!requestActiveElsewhere && !nonRequestBusy && (
''',
    '''                                enabled = requestActiveHere || isRecording || (!nonRequestBusy && (
''',
    "send button cross-chat unblock",
)

text = replace_once(
    text,
    'requestActiveElsewhere -> Text("Другой чат сейчас отвечает · здесь можно читать и готовить следующий запрос")',
    'requestActiveElsewhere -> Text("Другой чат отвечает · здесь можно отправить новый запрос")',
    "cross-chat placeholder",
)

old_action_enable = '                            enabled = !state.isLoading && !state.requestActive,\n'
count = text.count(old_action_enable)
if count != 3:
    raise SystemExit(f"project action enablement: expected 3 occurrences, got {count}")
text = text.replace(
    old_action_enable,
    '                            enabled = !nonRequestBusy && !requestActiveHere,\n',
)

path.write_text(text, encoding="utf-8")


# Sidebar: show which top-level chats are actively answering.
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''                    buildString {
                        if (projectName != null) append("$projectName · ")
                        append(sidebarDate(chat.updatedAt))
                    },
''',
    '''                    buildString {
                        if (vm.isChatRequestActive(chat.id)) append("Отвечает · ")
                        if (projectName != null) append("$projectName · ")
                        append(sidebarDate(chat.updatedAt))
                    },
''',
    "sidebar active request indicator",
)
path.write_text(text, encoding="utf-8")


# Orchestrator: do not collide with a manual request already running in a specialist chat.
path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        val all = chatsRepository.list()
        val chat = all.firstOrNull { it.id == requested.id } ?: requested
        val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
''',
    '''        val all = chatsRepository.list()
        val chat = all.firstOrNull { it.id == requested.id } ?: requested
        if (!isOrchestratorChat(chat.id) && RequestExecutionManager.hasActiveChat(chat.id)) {
            error("В чате «${chat.title}» уже выполняется другой запрос")
        }
        val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
''',
    "Orchestrator manual-chat collision guard",
)
text = replace_once(
    text,
    '''        var all = chatsRepository.list()
        val first = all.firstOrNull { it.id == requested.id } ?: requested
        val launch = ChatMessage''',
    '''        var all = chatsRepository.list()
        val first = all.firstOrNull { it.id == requested.id } ?: requested
        if (!isOrchestratorChat(first.id) && RequestExecutionManager.hasActiveChat(first.id)) {
            error("В чате «${first.title}» уже выполняется другой запрос")
        }
        val launch = ChatMessage''',
    "Orchestrator stage collision guard",
)
path.write_text(text, encoding="utf-8")

print("final multi-request UI and collision guards prepared")
