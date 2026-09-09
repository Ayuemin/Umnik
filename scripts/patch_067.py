from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:280]!r}")
    write(path, text.replace(old, new, 1))


# ---------------- ChatViewModel: show and preflight chat-scoped files ----------------
vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm,
    '''        val persistentChatFiles = if (mode == ChatMode.TEXT) {\n            currentChat?.chatFiles.orEmpty().map(::chatFileAsAttachment)\n        } else {\n            emptyList()\n        }\n        if (clean.isBlank() && pending.isEmpty() && persistentChatFiles.isEmpty()) return\n''',
    '''        val persistentChatFiles = if (mode == ChatMode.TEXT) {\n            currentChat?.chatFiles.orEmpty().map(::chatFileAsAttachment)\n        } else {\n            emptyList()\n        }\n        if (clean.isBlank() && pending.isEmpty() && persistentChatFiles.isEmpty()) return\n\n        val missingChatFile = persistentChatFiles.firstOrNull { attachment ->\n            attachment.localPath?.takeIf { it.isNotBlank() }?.let { !File(it).isFile } == true\n        }\n        if (missingChatFile != null) {\n            _state.value = _state.value.copy(\n                status = "Файл чата «${missingChatFile.name}» не найден. Удалите его из контекста и прикрепите заново."\n            )\n            return\n        }\n'''
)
replace_once(
    vm,
    '''            attachmentNames = pending.map { it.name }\n''',
    '''            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct()\n'''
)

# ---------------- UI: retry remains valid for chat-scoped files ----------------
ui = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(
    ui,
    '''                    onRetry = if (message.role == "user" && message.text.isNotBlank() && message.attachmentNames.isEmpty()) {\n                        { vm.send(message.text) }\n                    } else null\n''',
    '''                    onRetry = if (\n                        message.role == "user" &&\n                        message.text.isNotBlank() &&\n                        message.attachmentNames.all { name ->\n                            currentChatFiles.any { file -> file.name == name }\n                        }\n                    ) {\n                        { vm.send(message.text) }\n                    } else null\n'''
)

# ---------------- Network: do not Base64-encode plain text files unnecessarily ----------------
net = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(
    net,
    '''        attachments.forEach { attachment ->\n            val bytes = readAttachment(attachment)\n            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)\n            when {\n                attachment.mimeType.startsWith("image/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "image_url")\n                    add("image_url", JsonObject().apply {\n                        addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n                attachment.mimeType.startsWith("audio/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "input_audio")\n                    add("input_audio", JsonObject().apply {\n                        addProperty("data", b64)\n                        addProperty("format", audioFormat(attachment))\n                    })\n                })\n                attachment.mimeType.startsWith("video/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "video_url")\n                    add("video_url", JsonObject().apply {\n                        addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n                attachment.mimeType.startsWith("text/") ||\n                    attachment.name.endsWith(".md", true) ||\n                    attachment.name.endsWith(".json", true) ||\n                    attachment.name.endsWith(".csv", true) ||\n                    attachment.name.endsWith(".yaml", true) ||\n                    attachment.name.endsWith(".yml", true) ||\n                    attachment.name.endsWith(".xml", true) -> {\n                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "text")\n                        addProperty("text", "\\n--- Вложение: ${attachment.name} ---\\n$content\\n--- Конец вложения ---")\n                    })\n                }\n                else -> parts.add(JsonObject().apply {\n                    addProperty("type", "file")\n                    add("file", JsonObject().apply {\n                        addProperty("filename", attachment.name)\n                        addProperty("file_data", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n            }\n        }\n''',
    '''        attachments.forEach { attachment ->\n            val bytes = readAttachment(attachment)\n            when {\n                attachment.mimeType.startsWith("image/") -> {\n                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "image_url")\n                        add("image_url", JsonObject().apply {\n                            addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                        })\n                    })\n                }\n                attachment.mimeType.startsWith("audio/") -> {\n                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "input_audio")\n                        add("input_audio", JsonObject().apply {\n                            addProperty("data", b64)\n                            addProperty("format", audioFormat(attachment))\n                        })\n                    })\n                }\n                attachment.mimeType.startsWith("video/") -> {\n                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "video_url")\n                        add("video_url", JsonObject().apply {\n                            addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                        })\n                    })\n                }\n                attachment.mimeType.startsWith("text/") ||\n                    attachment.name.endsWith(".md", true) ||\n                    attachment.name.endsWith(".json", true) ||\n                    attachment.name.endsWith(".csv", true) ||\n                    attachment.name.endsWith(".yaml", true) ||\n                    attachment.name.endsWith(".yml", true) ||\n                    attachment.name.endsWith(".xml", true) -> {\n                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "text")\n                        addProperty("text", "\\n--- Вложение: ${attachment.name} ---\\n$content\\n--- Конец вложения ---")\n                    })\n                }\n                else -> {\n                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)\n                    parts.add(JsonObject().apply {\n                        addProperty("type", "file")\n                        add("file", JsonObject().apply {\n                            addProperty("filename", attachment.name)\n                            addProperty("file_data", "data:${attachment.mimeType};base64,$b64")\n                        })\n                    })\n                }\n            }\n        }\n'''
)

# ---------------- Version ----------------
gradle = "app/build.gradle.kts"
replace_once(gradle, "// Umnik v0.6.6 stoppable model requests + compact composer", "// Umnik v0.6.7 chat-file request visibility and preflight")
replace_once(gradle, 'versionCode = 15', 'versionCode = 16')
replace_once(gradle, 'versionName = "0.6.6"', 'versionName = "0.6.7"')

print("Umnik 0.6.7 attachment request patch applied")
