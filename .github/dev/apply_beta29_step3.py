from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"

replace_once(
    path,
    "import com.ayuemin.ymnik.network.AgentModePolicy\nimport com.ayuemin.ymnik.network.LocalShellAgentClient\n",
    "import com.ayuemin.ymnik.network.AgentModePolicy\nimport com.ayuemin.ymnik.network.HistoricalAttachmentPolicy\nimport com.ayuemin.ymnik.network.LocalShellAgentClient\n",
)

replace_once(
    path,
    '''                        val localShellToolsEnabled = requestSpecialist == null &&
                            modelToolsAvailable && localShellRequested
                        val modelPendingAttachments = pending.filter(::shouldSendAttachmentToChatModel)
                        val allAttachments = (modelPendingAttachments + requestPersistentTextAttachments + teamFiles)
                            .distinctBy { it.localPath ?: it.uri }
                        answerAttachmentCount = allAttachments.size
''',
    '''                        val localShellToolsEnabled = requestSpecialist == null &&
                            modelToolsAvailable && localShellRequested
                        val modelPendingAttachments = pending.filter(::shouldSendAttachmentToChatModel)
                        val historicalAttachments = if (requestSpecialist == null) {
                            HistoricalAttachmentPolicy.select(
                                prompt = clean,
                                history = before,
                                files = currentChat?.chatFiles.orEmpty()
                            )
                                .map(::chatFileAsAttachment)
                                .filter(::shouldSendAttachmentToChatModel)
                        } else {
                            emptyList()
                        }
                        val allAttachments = (
                            modelPendingAttachments + historicalAttachments +
                                requestPersistentTextAttachments + teamFiles
                            )
                            .distinctBy { it.localPath ?: it.uri }
                        if (historicalAttachments.isNotEmpty()) {
                            DiagnosticLog.record(
                                context,
                                "ATTACHMENT",
                                "rehydrated historical=${historicalAttachments.size}; names=" +
                                    historicalAttachments.joinToString(",") { it.name.take(80) }
                            )
                        }
                        answerAttachmentCount = allAttachments.size
''',
)

replace_once(
    path,
    '''                } else {
                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)
                    if (!allowed) {
                        File(localPath).delete()
                        _state.value = _state.value.copy(status = reason)
                    } else {
                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                    }
                }
''',
    '''                } else if (!forImageGeneration && shouldPersistInChat(attachment)) {
                    persistChatAttachment(attachment)
                    File(localPath).delete()
                } else {
                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)
                    if (!allowed) {
                        File(localPath).delete()
                        _state.value = _state.value.copy(status = reason)
                    } else {
                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                    }
                }
''',
)

replace_once(
    path,
    '''        appendLine("Не проси пользователя повторно прислать материал, если нужный текст, результат или сведения уже присутствуют в переданной истории, долговременной памяти, базе знаний или приложенных файлах.")
''',
    '''        appendLine("Не проси пользователя повторно прислать материал, если нужный текст, результат или сведения уже присутствуют в переданной истории, долговременной памяти, базе знаний или приложенных файлах.")
        appendLine("Если пользователь просит точно прочитать или переписать текст с изображения, не достраивай неразборчивые слова по смыслу. Помечай неуверенные места и не выдавай предположение за точную расшифровку.")
''',
)

print("beta.29 step3 historical attachment patch applied")
