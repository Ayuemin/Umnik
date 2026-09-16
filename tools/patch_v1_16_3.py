from pathlib import Path
import re


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    found = text.count(old)
    if found != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {found}: {old[:100]!r}")
    p.write_text(text.replace(old, new))


vm = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
text = vm.read_text()
old = '    private companion object { const val QUICK_MODEL_SEPARATOR = "\\u001F" }'
new = '''    private companion object {
        const val QUICK_MODEL_SEPARATOR = "\\u001F"
        const val MAX_ATTACHMENT_MB = 50
        const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024L * 1024L
    }'''
if old not in text:
    raise SystemExit("ChatViewModel companion marker not found")
text = text.replace(old, new, 1)

old = '''        if (_state.value.isLoading) return _state.value.currentChatId

        val current = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }'''
new = '''        if (_state.value.isLoading && !_state.value.requestActive) return _state.value.currentChatId

        val current = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }'''
if text.count(old) != 1:
    raise SystemExit("createChat loading guard not found exactly once")
text = text.replace(old, new, 1)

old = '''    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return'''
new = '''    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading && !_state.value.requestActive) return'''
if text.count(old) != 1:
    raise SystemExit("switchChat loading guard not found exactly once")
text = text.replace(old, new, 1)

marker = '    fun isOrchestratorChat(chatId: String): Boolean = projectAutomation.isOrchestrator(chatId)\n'
if marker not in text:
    raise SystemExit("orchestrator marker not found")
text = text.replace(
    marker,
    marker + '    fun activeRequestChatId(): String? = RequestExecutionManager.snapshots.value.activeChatId\n',
    1,
)

text, n1 = re.subn(r'25L \* 1024L \* 1024L', 'MAX_ATTACHMENT_BYTES', text)
text, n2 = re.subn(r'25L \* 1024 \* 1024', 'MAX_ATTACHMENT_BYTES', text)
if n1 + n2 < 4:
    raise SystemExit(f"Expected at least 4 attachment limits, changed {n1 + n2}")
text = text.replace(
    'Ограничение Umnik сейчас 25 МБ на один файл',
    'Прямое вложение ограничено $MAX_ATTACHMENT_MB МБ. Большие документы лучше добавлять в «Базу знаний».',
)
vm.write_text(text)

replace_exact(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '.pingInterval(20, TimeUnit.SECONDS)',
    '.pingInterval(5, TimeUnit.SECONDS)',
)

nav = Path("app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt")
text = nav.read_text()
old = '''                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(20.dp))'''
new = '''                            enabled = !state.isLoading || state.requestActive,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(20.dp))'''
if text.count(old) != 1:
    raise SystemExit("NavigationSidebar new chat block not found exactly once")
nav.write_text(text.replace(old, new, 1))

project = Path("app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt")
text = project.read_text()
old = '''        FilledTonalButton(
            onClick = {
                vm.createChat()
                onDismiss()
            },
            enabled = !state.isLoading,'''
new = '''        FilledTonalButton(
            onClick = {
                vm.createChat()
                onDismiss()
            },
            enabled = !state.isLoading || state.requestActive,'''
if text.count(old) != 1:
    raise SystemExit("ProjectDialogs new chat block not found exactly once")
project.write_text(text.replace(old, new, 1))

ui = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
text = ui.read_text()
replace_old = '    var text by remember { mutableStateOf("") }'
replace_new = '    var text by remember(state.currentChatId) { mutableStateOf("") }'
if text.count(replace_old) != 1:
    raise SystemExit("YmnikApp composer state marker not found")
text = text.replace(replace_old, replace_new, 1)

old = '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val isUsageGuide = currentChat?.title == "Памятка по Umnik"'''
new = '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val requestActiveHere = state.requestActive && vm.activeRequestChatId() == state.currentChatId
    val requestActiveElsewhere = state.requestActive && !requestActiveHere
    val nonRequestBusy = state.isLoading && !state.requestActive
    val isUsageGuide = currentChat?.title == "Памятка по Umnik"'''
if text.count(old) != 1:
    raise SystemExit("YmnikApp current chat marker not found")
text = text.replace(old, new, 1)

old = '        if (!microphoneAvailable || state.isLoading || state.requestActive || imagePromptMode) return'
new = '        if (!microphoneAvailable || nonRequestBusy || state.requestActive || imagePromptMode) return'
if text.count(old) != 1:
    raise SystemExit("YmnikApp microphone guard not found")
text = text.replace(old, new, 1)

old = '''    LaunchedEffect(state.requestActive) {
        if (!state.requestActive) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            requestElapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            delay(250)
        }
    }'''
new = '''    LaunchedEffect(requestActiveHere) {
        if (!requestActiveHere) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            requestElapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            delay(250)
        }
    }'''
if text.count(old) != 1:
    raise SystemExit("YmnikApp request timer block not found")
text = text.replace(old, new, 1)

old = '''                                    if (state.requestActive) {
                                        vm.stopGeneration()'''
new = '''                                    if (requestActiveHere) {
                                        vm.stopGeneration()'''
if text.count(old) != 1:
    raise SystemExit("YmnikApp stop click condition not found")
text = text.replace(old, new, 1)

old = '                                enabled = state.requestActive || isRecording || (!state.isLoading && ('
new = '                                enabled = requestActiveHere || isRecording || (!requestActiveElsewhere && !nonRequestBusy && ('
if text.count(old) != 1:
    raise SystemExit("YmnikApp send enabled condition not found")
text = text.replace(old, new, 1)

old = '''                                if (state.requestActive) {
                                    WorkingStopIcon()'''
new = '''                                if (requestActiveHere) {
                                    WorkingStopIcon()'''
if text.count(old) != 1:
    raise SystemExit("YmnikApp stop icon condition not found")
text = text.replace(old, new, 1)

old = '''                            state.requestActive -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),'''
new = '''                            requestActiveHere -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),'''
if text.count(old) != 1:
    raise SystemExit("YmnikApp request placeholder condition not found")
text = text.replace(old, new, 1)

needle = '''                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
                            )
                            imagePromptMode -> Text("Опишите изображение")'''
repl = '''                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
                            )
                            requestActiveElsewhere -> Text("Другой чат сейчас отвечает · здесь можно читать и готовить следующий запрос")
                            imagePromptMode -> Text("Опишите изображение")'''
if text.count(needle) != 1:
    raise SystemExit("YmnikApp other-chat placeholder insertion point not found")
text = text.replace(needle, repl, 1)
ui.write_text(text)

build = Path("app/build.gradle.kts")
text = build.read_text()
for old, new in [
    ('// Umnik v1.16.2', '// Umnik v1.16.3'),
    ('versionCode = 121', 'versionCode = 122'),
    ('versionName = "1.16.2"', 'versionName = "1.16.3"'),
]:
    if text.count(old) != 1:
        raise SystemExit(f"build version marker not found exactly once: {old}")
    text = text.replace(old, new, 1)
build.write_text(text)

changelog = Path("CHANGELOG.md")
text = changelog.read_text()
entry = '''## v1.16.3

- Исправлена навигация во время активного запроса: можно открыть другой обычный или проектный чат и создать новый чат, пока исходный запрос продолжает выполняться.
- Кнопка остановки и таймер запроса теперь относятся только к чату, который действительно выполняет запрос; в другом чате больше нет ложной кнопки остановки.
- Та же развязка работает для проектных чатов и Оркестратора: выполняющаяся работа больше не удерживает пользователя на одном экране.
- HTTP/2 keep-alive для OpenRouter усилен с 20 до 5 секунд, чтобы снизить риск обрыва мобильного соединения при сворачивании Umnik.
- Лимит прямого вложения увеличен с 25 до 50 МБ; для более крупных документов Umnik явно рекомендует локальную «Базу знаний», чтобы не раздувать запрос целым файлом.
- Версия: 1.16.3 / versionCode 122.

'''
if '## v1.16.3' not in text:
    text = entry + text
changelog.write_text(text)

print("v1.16.3 patch applied")
