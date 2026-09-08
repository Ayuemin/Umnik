from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, 1))

vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
client = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"

# Switching models never enables reasoning automatically. If it was enabled,
# keep it only when the new model and selected effort are compatible.
replace_once(
    vm,
    '''                val info = _state.value.availableTextModels.firstOrNull { it.id == clean }\n                val keepReasoning = _state.value.reasoningEnabled && info?.supportsReasoning == true''',
    '''                val info = _state.value.availableTextModels.firstOrNull { it.id == clean }\n                val keepReasoning = _state.value.reasoningEnabled && info?.supportsReasoning == true &&\n                    (info.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in info.reasoningEfforts)'''
)

# Re-check queued attachments at send time in case the user changed model after attaching them.
replace_once(
    vm,
    '''        if (_state.value.mode == ChatMode.IMAGE && pending.any { !it.mimeType.startsWith("image/") }) {\n            _state.value = _state.value.copy(status = "В режиме изображений можно прикладывать только изображения-референсы")\n            return\n        }\n\n        val chatId = _state.value.currentChatId''',
    '''        val invalidPending = pending.firstOrNull { !attachmentAllowed(it).first }\n        if (invalidPending != null) {\n            _state.value = _state.value.copy(status = attachmentAllowed(invalidPending).second ?: "Вложение не поддерживается выбранной моделью")\n            return\n        }\n\n        val chatId = _state.value.currentChatId'''
)

# Project files are persistent, so only attach the subset compatible with the currently selected text model.
replace_once(
    vm,
    '''                        val projectFiles = currentProject?.files.orEmpty().map { file ->\n                            PendingAttachment(\n                                uri = "project://${file.id}",\n                                name = file.name,\n                                mimeType = file.mimeType,\n                                size = file.size,\n                                localPath = file.localPath\n                            )\n                        }\n                        val modelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }''',
    '''                        val projectFiles = currentProject?.files.orEmpty().map { file ->\n                            PendingAttachment(\n                                uri = "project://${file.id}",\n                                name = file.name,\n                                mimeType = file.mimeType,\n                                size = file.size,\n                                localPath = file.localPath\n                            )\n                        }.filter { attachmentAllowed(it).first }\n                        val modelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }'''
)

# Reference images from a project are only sent to image models that accept image input.
replace_once(
    vm,
    '''                        val projectImages = currentProject?.files.orEmpty()\n                            .filter { it.mimeType.startsWith("image/") }\n                            .map { file -> PendingAttachment(''',
    '''                        val projectImages = currentProject?.files.orEmpty()\n                            .filter { it.mimeType.startsWith("image/") && currentImageModelInfo()?.accepts("image") == true }\n                            .map { file -> PendingAttachment('''
)

# Treat common source/config files as text instead of binary file parts.
replace_once(
    client,
    '''                    attachment.name.endsWith(".md", true) ||\n                    attachment.name.endsWith(".json", true) ||\n                    attachment.name.endsWith(".csv", true) -> {''',
    '''                    attachment.name.endsWith(".md", true) ||\n                    attachment.name.endsWith(".json", true) ||\n                    attachment.name.endsWith(".csv", true) ||\n                    attachment.name.endsWith(".yaml", true) ||\n                    attachment.name.endsWith(".yml", true) ||\n                    attachment.name.endsWith(".xml", true) -> {'''
)

print("Umnik 0.6.3 capability hardening applied")
