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


# ChatViewModel: failures are addressed to one chat request and model discovery is
# OpenRouter-only.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)

fail_pattern = re.compile(r"(?m)^(\s*)RequestExecutionManager\.fail\(friendlyError\)\s*$")


def fail_replacement(match: re.Match) -> str:
    indent = match.group(1)
    return (
        f"{indent}RequestExecutionManager.snapshotForChat(chatId)?.requestId?.let {{ activeRequestId ->\n"
        f"{indent}    RequestExecutionManager.fail(activeRequestId, friendlyError)\n"
        f"{indent}}}"
    )


text, fail_count = fail_pattern.subn(fail_replacement, text)
if fail_count != 2:
    raise SystemExit(f"per-chat fail migration: expected 2 calls, got {fail_count}")

models_pattern = re.compile(
    r"    private suspend fun textModelsForProfile\(profile: ConnectionProfile\): List<ModelInfo> \{.*?\n    \}\n\n(?=    private suspend fun imageModelsForProfile)",
    re.S,
)
models_replacement = """    private suspend fun textModelsForProfile(profile: ConnectionProfile): List<ModelInfo> {
        require(profile.type == ProviderType.OPENROUTER) { \"Umnik использует только OpenRouter\" }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        return api.models(key, effectiveTextBaseUrl(profile))
            .filter { ModelCategory.TEXT in it.categories && !it.isBatch }
    }

"""
text, model_count = models_pattern.subn(models_replacement, text, count=1)
if model_count != 1:
    raise SystemExit(f"OpenRouter model catalog migration: expected 1 function, got {model_count}")
if "compatibleApi" in text:
    raise SystemExit("compatibleApi reference survived OpenRouter-only migration")
if "nvidiaImageApi" in text:
    raise SystemExit("nvidiaImageApi reference survived OpenRouter-only migration")
write(path, text)

# Batch needs an exact request lookup, never an arbitrary active chat.
path = "app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt"
text = read(path)
text = replace_once(
    text,
    """    fun snapshotForChat(chatId: String): Snapshot? = synchronized(lock) {
        runtimes.values.firstOrNull { it.snapshot.chatId == chatId }?.snapshot
    }
""",
    """    fun snapshotForChat(chatId: String): Snapshot? = synchronized(lock) {
        runtimes.values.firstOrNull { it.snapshot.chatId == chatId }?.snapshot
    }

    fun snapshotForRequest(requestId: String): Snapshot? = synchronized(lock) {
        runtimes[requestId]?.snapshot
    }
""",
    "request snapshot lookup",
)
write(path, text)

# Every model call created by one top-level request belongs to that request's network session.
path = "app/src/main/java/com/ayuemin/ymnik/RequestNetworkSession.kt"
text = read(path)
text = replace_once(
    text,
    "private fun openRouter(): OpenRouterClient = OpenRouterClient(app) { label -> updatePhase(label) }",
    "private fun openRouter(): OpenRouterClient = OpenRouterClient(app, requestId) { label -> updatePhase(label) }",
    "request-bound OpenRouter client",
)
write(path, text)

# OpenRouterClient carries request identity into Batch tracking.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
text = read(path)
text = replace_once(
    text,
    """class OpenRouterClient(
    private val context: Context,
    private val phaseCallback: (String) -> Unit = {}
) {""",
    """class OpenRouterClient(
    private val context: Context,
    private val requestId: String? = null,
    private val phaseCallback: (String) -> Unit = {}
) {""",
    "OpenRouter request identity",
)
text = replace_once(
    text,
    "private val chatBatchRunner = OpenRouterChatBatchRunner(context)",
    "private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId)",
    "Batch request identity",
)
write(path, text)

# Batch resolves origin and cancellation from this exact top-level request.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterChatBatchRunner.kt"
text = read(path)
text = replace_once(
    text,
    "internal class OpenRouterChatBatchRunner(private val context: Context) {",
    """internal class OpenRouterChatBatchRunner(
    private val context: Context,
    private val requestId: String? = null
) {""",
    "Batch runner constructor",
)
text = replace_once(
    text,
    """        RequestExecutionManager.fail(
            \"Отслеживание Batch остановлено. Задание OpenRouter может продолжать выполняться и тарифицироваться на сервере.\"
        )""",
    """        requestId?.let { id ->
            RequestExecutionManager.fail(
                id,
                \"Отслеживание Batch остановлено. Задание OpenRouter может продолжать выполняться и тарифицироваться на сервере.\"
            )
        }""",
    "Batch per-request failure",
)
text = replace_once(
    text,
    """        val originChatId = RequestExecutionManager.snapshots.value.activeChatId
        val originChat = chats.list().firstOrNull { it.id == originChatId }
        val originMessageId = originChat?.messages
            ?.lastOrNull { it.role == \"user\" && it.deliveryState == \"pending\" }
            ?.id""",
    """        val originSnapshot = requestId
            ?.let(RequestExecutionManager::snapshotForRequest)
            ?: error(\"Batch-запрос не привязан к активной сессии\")
        val originChatId = originSnapshot.chatId
        val originChat = chats.list().firstOrNull { it.id == originChatId }
        val originMessageId = originSnapshot.messageId""",
    "Batch origin binding",
)
write(path, text)

print("OpenRouter request isolation finalized")
