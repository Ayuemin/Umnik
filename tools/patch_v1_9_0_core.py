from pathlib import Path


def load(path): return Path(path).read_text(encoding="utf-8")
def save(path, text): Path(path).write_text(text, encoding="utf-8")
def one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)

# ChatViewModel: local guide + high-value diagnostics.
p = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
s = load(p)
s = one(s, "import com.ayuemin.ymnik.diagnostics.DiagnosticLog\n", "import com.ayuemin.ymnik.diagnostics.DiagnosticLog\nimport com.ayuemin.ymnik.help.UmnikUsageGuide\n", "guide import")
s = one(s, "    fun setWebSearchEnabled(enabled: Boolean) {\n", "    fun setWebSearchEnabled(enabled: Boolean) {\n        DiagnosticLog.action(context, \"web_search_toggle\", \"enabled=$enabled; model=${currentTextModelId()}\")\n", "web log")
s = one(s, "    fun setReasoningEnabled(enabled: Boolean) {\n", "    fun setReasoningEnabled(enabled: Boolean) {\n        DiagnosticLog.action(context, \"reasoning_toggle\", \"enabled=$enabled; model=${currentTextModelId()}; effort=${_state.value.reasoningEffort.name}\")\n", "reasoning log")
s = one(s, "    fun addAttachment(uri: Uri, forImageGeneration: Boolean = false) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n", "    fun addAttachment(uri: Uri, forImageGeneration: Boolean = false) {\n        DiagnosticLog.action(context, \"attachment_pick\", \"imageGeneration=$forImageGeneration\")\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                DiagnosticLog.record(context, \"ATTACHMENT\", \"loaded; mime=${attachment.mimeType}; bytes=${attachment.size}; imageGeneration=$forImageGeneration\")\n", "attachment log")
s = one(s, "    fun addCameraAttachment(uri: Uri, localPath: String, forImageGeneration: Boolean = false) {\n        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }\n            .onSuccess { attachment ->\n", "    fun addCameraAttachment(uri: Uri, localPath: String, forImageGeneration: Boolean = false) {\n        DiagnosticLog.action(context, \"camera_result\", \"imageGeneration=$forImageGeneration\")\n        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }\n            .onSuccess { attachment ->\n                DiagnosticLog.record(context, \"ATTACHMENT\", \"camera loaded; mime=${attachment.mimeType}; bytes=${attachment.size}; imageGeneration=$forImageGeneration\")\n", "camera log")
s = one(s, "    fun addVoiceRecording(localPath: String): Boolean {\n", "    fun addVoiceRecording(localPath: String): Boolean {\n        DiagnosticLog.action(context, \"voice_recording_result\")\n", "voice log")
s = one(s, "        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n        return true\n    }\n\n    fun removeAttachment", "        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n        DiagnosticLog.record(context, \"ATTACHMENT\", \"voice accepted; mime=audio/wav; bytes=${file.length()}\")\n        return true\n    }\n\n    fun removeAttachment", "voice accepted log")
anchor = '''        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats(),
            status = null
        )
        return chat.id
    }

    fun branchFromMessage'''
replacement = '''        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats(),
            status = null
        )
        DiagnosticLog.action(context, "new_chat", "chat=${chat.id.take(8)}; project=${projectId ?: "none"}")
        return chat.id
    }

    fun openUsageGuide(): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId
        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }
            ?: defaultOpenRouterProfile()
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = UmnikUsageGuide.TEXT,
            providerName = "Umnik"
        )
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Памятка по Umnik",
            messages = listOf(message),
            mode = ChatMode.TEXT,
            connectionProfileId = profile.id,
            createdAt = now,
            updatedAt = now
        )
        val retained = _state.value.chats.filterNot { old -> old.projectId == null && isBareEmptyChat(old) }
        val next = listOf(chat) + retained
        chatsRepository.save(next)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("active_connection_profile", profile.id)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putBoolean("reasoning_enabled", false)
            .apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = listOf(message),
            mode = ChatMode.TEXT,
            activeConnectionProfileId = profile.id,
            textModel = loadTextModelForProfile(profile),
            currentChatTextModel = null,
            reasoningEnabled = false,
            pendingAttachments = emptyList(),
            status = null
        )
        DiagnosticLog.action(context, "usage_guide_opened", "chat=${chat.id.take(8)}; local=true")
        if (isProfileConfigured(profile)) refreshModelCapabilities()
        return chat.id
    }

    fun branchFromMessage'''
s = one(s, anchor, replacement, "usage guide function")
s = one(s, "        if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) refreshModelCapabilities()\n    }\n\n    fun deleteChat", "        DiagnosticLog.action(context, \"switch_chat\", \"chat=${id.take(8)}; messages=${chat.messages.size}; model=$modelId\")\n        if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) refreshModelCapabilities()\n    }\n\n    fun deleteChat", "switch chat log")
save(p, s)

# OpenRouterHubController: feature starts and chat-delivery evidence.
p = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
s = load(p)
s = one(s, "import com.ayuemin.ymnik.data.VideoJobRepository\n", "import com.ayuemin.ymnik.data.VideoJobRepository\nimport com.ayuemin.ymnik.diagnostics.DiagnosticLog\n", "hub import")
for old, new, label in [
    ("    fun submitBatch(raw: String) {\n", "    fun submitBatch(raw: String) {\n        DiagnosticLog.action(context, \"batch_submit\", \"inputChars=${raw.length}\")\n", "batch start"),
    ("    fun submitVideo(promptRaw: String, references: List<Uri> = emptyList()) {\n", "    fun submitVideo(promptRaw: String, references: List<Uri> = emptyList()) {\n        DiagnosticLog.action(context, \"video_submit\", \"promptChars=${promptRaw.length}; refs=${references.size}\")\n", "video start"),
    ("    fun transcribe(uri: Uri) {\n", "    fun transcribe(uri: Uri) {\n        DiagnosticLog.action(context, \"transcription_submit\")\n", "stt start"),
    ("    fun synthesize(textRaw: String) {\n", "    fun synthesize(textRaw: String) {\n        DiagnosticLog.action(context, \"speech_submit\", \"textChars=${textRaw.length}\")\n", "tts start"),
    ("    fun runShell(promptRaw: String, attachments: List<Uri> = emptyList()) {\n", "    fun runShell(promptRaw: String, attachments: List<Uri> = emptyList()) {\n        DiagnosticLog.action(context, \"shell_submit\", \"promptChars=${promptRaw.length}; attachments=${attachments.size}\")\n", "shell start"),
]: s = one(s, old, new, label)
s = one(s, "        chats.save(next)\n        AsyncJobEvents.notifyChanged()\n    }\n\n    private fun appendHubExchange", "        chats.save(next)\n        DiagnosticLog.record(context, \"CHAT_RESULT\", \"hub user message added; chat=${chatId.take(8)}; chars=${text.length}\")\n        AsyncJobEvents.notifyChanged()\n    }\n\n    private fun appendHubExchange", "hub user result")
s = one(s, "        chats.save(next)\n    }\n\n    private data class UriData", "        chats.save(next)\n        DiagnosticLog.record(context, \"CHAT_RESULT\", \"hub exchange added; chat=${chatId.take(8)}; assistantChars=${assistantText.length}; files=${files.size}; fileTypes=${files.map { it.mimeType }.distinct().joinToString(\",\")}\")\n    }\n\n    private data class UriData", "hub exchange result")
save(p, s)

# Background Batch/Video delivery.
p = "app/src/main/java/com/ayuemin/ymnik/OpenRouterBackgroundWorker.kt"
s = load(p)
s = one(s, "import com.ayuemin.ymnik.data.VideoJobRepository\n", "import com.ayuemin.ymnik.data.VideoJobRepository\nimport com.ayuemin.ymnik.diagnostics.DiagnosticLog\n", "worker import")
s = one(s, "        if (batchJobs.isEmpty() && videoJobs.isEmpty()) return Result.success()\n\n        val secrets = SecretStore(applicationContext)\n", "        if (batchJobs.isEmpty() && videoJobs.isEmpty()) return Result.success()\n        DiagnosticLog.record(applicationContext, \"BACKGROUND\", \"job worker start; batches=${batchJobs.size}; videos=${videoJobs.size}\")\n\n        val secrets = SecretStore(applicationContext)\n", "worker start")
s = one(s, '''                    markDelivered("batches", current.remoteId)
                } else retry = true
                batches.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { retry = true }
''', '''                    markDelivered("batches", current.remoteId)
                    DiagnosticLog.record(applicationContext, "BACKGROUND", "Batch delivered; status=${current.status}; chat=${current.chatId?.take(8) ?: "none"}; items=${current.items.size}")
                } else retry = true
                batches.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                retry = true
                DiagnosticLog.record(applicationContext, "BACKGROUND", "Batch worker failure", error)
            }
''', "batch worker")
s = one(s, '''                    markDelivered("videos", current.remoteId)
                } else retry = true
                videos.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { retry = true }
''', '''                    markDelivered("videos", current.remoteId)
                    DiagnosticLog.record(applicationContext, "BACKGROUND", "Video delivered; status=${current.status}; chat=${current.chatId?.take(8) ?: "none"}; hasFile=${!current.localPath.isNullOrBlank()}")
                } else retry = true
                videos.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                retry = true
                DiagnosticLog.record(applicationContext, "BACKGROUND", "Video worker failure", error)
            }
''', "video worker")
save(p, s)

Path("tools/patch_v1_9_0_core.py").unlink(missing_ok=True)
