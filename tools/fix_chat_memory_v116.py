from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Anchor not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1. ChatViewModel: ChatMemoryManager must be initialized after OpenRouterClient.
cvm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    cvm,
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n"
    "    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n",
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n",
)
replace_once(
    cvm,
    "    private val api = OpenRouterClient(context)\n",
    "    private val api = OpenRouterClient(context)\n"
    "    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n",
)

# 2. Compose: weight is a RowScope/ColumnScope extension; explicit foundation import is invalid.
settings = "app/src/main/java/com/ayuemin/ymnik/ui/ChatMemorySettings.kt"
p = Path(settings)
text = p.read_text(encoding="utf-8")
text = text.replace("import androidx.compose.foundation.layout.weight\n", "")
p.write_text(text, encoding="utf-8")

# 3. Do not summarize every single turn after the first checkpoint.
# Keep not-yet-indexed old turns in direct history until they form a checkpoint-sized batch.
manager = "app/src/main/java/com/ayuemin/ymnik/data/ChatMemoryManager.kt"
replace_once(
    manager,
    "            val snapshot = repository.snapshot(chat.id)\n"
    "            val recent = completed.takeLast(recentCount.coerceAtMost(completed.size))\n"
    "            val queryVector = embeddings.embed(\n",
    "            val snapshot = repository.snapshot(chat.id)\n"
    "            val recent = completed.takeLast(recentCount.coerceAtMost(completed.size))\n"
    "            val recentIds = recent.map { it.id }.toSet()\n"
    "            val indexedIds = snapshot?.indexedFingerprints?.keys.orEmpty()\n"
    "            val directHistory = completed.filter { message ->\n"
    "                message.id !in indexedIds || message.id in recentIds\n"
    "            }\n"
    "            val queryVector = embeddings.embed(\n",
)
replace_once(
    manager,
    "                \"chat=${chat.id.take(8)}; mode=$mode; tokens=$totalTokens/$threshold; original=${completed.size}; recent=${recent.size}; hits=${hits.size}; checkpoints=${snapshot?.checkpoints?.size ?: 0}; memoryChars=${memoryText.length}\"\n"
    "            )\n"
    "            PreparedContext(recent, \"\\n$memoryText\\n\", \"hybrid\")\n",
    "                \"chat=${chat.id.take(8)}; mode=$mode; tokens=$totalTokens/$threshold; original=${completed.size}; recent=${recent.size}; direct=${directHistory.size}; hits=${hits.size}; checkpoints=${snapshot?.checkpoints?.size ?: 0}; memoryChars=${memoryText.length}\"\n"
    "            )\n"
    "            PreparedContext(directHistory, \"\\n$memoryText\\n\", \"hybrid\")\n",
)
replace_once(
    manager,
    "        if (newTurns.isEmpty()) return\n\n"
    "        val groups = mutableListOf<MutableList<ChatMessage>>()\n",
    "        if (newTurns.isEmpty()) return\n\n"
    "        val pendingTokens = newTurns.sumOf { turn ->\n"
    "            turn.sumOf { ConversationContext.estimateTokens(it.text) + 24 } + 64\n"
    "        }\n"
    "        if (snapshot != null && pendingTokens < settings.checkpointTokens) {\n"
    "            DiagnosticLog.record(\n"
    "                context,\n"
    "                \"CHAT_MEMORY\",\n"
    "                \"checkpoint pending chat=${chat.id.take(8)}; tokens=$pendingTokens/${settings.checkpointTokens}; turns=${newTurns.size}\"\n"
    "            )\n"
    "            return\n"
    "        }\n\n"
    "        val groups = mutableListOf<MutableList<ChatMessage>>()\n",
)

print("v1.16 memory compile and checkpoint batching fixes applied")
