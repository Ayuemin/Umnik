from pathlib import Path

vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text(encoding="utf-8")
old = (
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n"
    "    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n"
    "    private val projectsRepository = ProjectRepository(context)\n"
)
new = (
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n"
    "    private val projectsRepository = ProjectRepository(context)\n"
)
if old not in vm:
    raise SystemExit("ChatViewModel manager anchor not found")
vm = vm.replace(old, new, 1)
api_line = "    private val api = OpenRouterClient(context)\n"
if api_line not in vm:
    raise SystemExit("ChatViewModel api anchor not found")
vm = vm.replace(
    api_line,
    api_line + "    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n",
    1,
)
vm_path.write_text(vm, encoding="utf-8")

ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/ChatMemorySettings.kt")
ui = ui_path.read_text(encoding="utf-8")
bad_import = "import androidx.compose.foundation.layout.weight\n"
if bad_import not in ui:
    raise SystemExit("ChatMemorySettings weight import not found")
ui_path.write_text(ui.replace(bad_import, "", 1), encoding="utf-8")

Path(".github/workflows/fix-chat-memory-v1.16.yml").unlink(missing_ok=True)
Path(".github/scripts/fix_chat_memory_compile.py").unlink(missing_ok=True)
