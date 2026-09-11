from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# Android permission
manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text()
manifest = replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.INTERNET" />\n',
    '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n',
    "record audio permission"
)
manifest_path.write_text(manifest)

# WAV voice recorder
recorder_path = Path("app/src/main/java/com/ayuemin/ymnik/audio/WavRecorder.kt")
recorder_path.parent.mkdir(parents=True, exist_ok=True)
recorder_path.write_text(r'''package com.ayuemin.ymnik.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max

class WavRecorder(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    private var targetFile: File? = null

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(): File {
        if (recording) return targetFile ?: error("Запись уже идёт")
        cleanupOldRecordings()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("Не удалось подготовить микрофон")
        val bufferSize = max(minBuffer * 2, 4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Микрофон недоступен")
        }

        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { output -> output.write(wavHeader(0)) }

        audioRecord = recorder
        targetFile = file
        recording = true
        recorder.startRecording()

        writerThread = thread(name = "UmnikVoiceRecorder") {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(file, true).use { output ->
                while (recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return file
    }

    fun stop(): File? {
        val file = targetFile ?: return null
        if (!recording) return file.takeIf { it.isFile && it.length() > 44 }

        recording = false
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        runCatching { writerThread?.join(2500) }
        runCatching { recorder?.release() }
        audioRecord = null
        writerThread = null

        if (!file.isFile || file.length() <= 44L) {
            file.delete()
            targetFile = null
            return null
        }
        patchHeader(file)
        targetFile = null
        return file
    }

    fun cancel() {
        val file = if (recording) stop() else targetFile
        file?.delete()
        targetFile = null
    }

    private fun patchHeader(file: File) {
        val dataSize = (file.length() - 44L).coerceAtLeast(0L)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(wavHeader(dataSize))
        }
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val safeData = dataSize.coerceAtMost(0xFFFF_FFFFL).toInt()
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + safeData)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(safeData)
        }.array()
    }

    private fun cleanupOldRecordings() {
        val dir = File(context.cacheDir, "voice")
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
''')

# ChatViewModel integration
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text()
vm = replace_once(
    vm,
    '''        if (mime.startsWith("audio/")) return if (info?.accepts("audio") == true) true to null else false to "Выбранная модель не принимает аудио"\n''',
    '''        if (mime.startsWith("audio/")) return if (activeConnectionProfile().type == ProviderType.OPENROUTER && info?.accepts("audio") == true) true to null else false to "Выбранная модель не принимает аудио"\n''',
    "audio provider guard"
)
vm = replace_once(
    vm,
    '''    fun removeAttachment(uri: String) {\n''',
    '''    fun addVoiceRecording(localPath: String): Boolean {\n        if (_state.value.isLoading || _state.value.requestActive) {\n            File(localPath).delete()\n            return false\n        }\n        val file = File(localPath)\n        if (!file.isFile || file.length() <= 44L) {\n            file.delete()\n            _state.value = _state.value.copy(status = "Голосовое сообщение не записалось")\n            return false\n        }\n        if (file.length() > 25L * 1024L * 1024L) {\n            file.delete()\n            _state.value = _state.value.copy(status = "Голосовое сообщение превышает ограничение 25 МБ")\n            return false\n        }\n        val attachment = PendingAttachment(\n            uri = "voice://${UUID.randomUUID()}",\n            name = "Голосовое сообщение.wav",\n            mimeType = "audio/wav",\n            size = file.length(),\n            localPath = file.absolutePath\n        )\n        val (allowed, reason) = attachmentAllowed(attachment)\n        if (!allowed) {\n            file.delete()\n            _state.value = _state.value.copy(status = reason ?: "Выбранная модель не принимает голос")\n            return false\n        }\n        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n        return true\n    }\n\n    fun removeAttachment(uri: String) {\n''',
    "voice attachment method"
)
vm = replace_once(
    vm,
    '''            text = clean.ifBlank {\n                when {\n                    mode == ChatMode.IMAGE -> "Создай вариант приложенного изображения"\n                    persistentChatFiles.isNotEmpty() -> "[Файлы чата]"\n                    else -> "[Вложения]"\n                }\n            },\n''',
    '''            text = clean.ifBlank {\n                when {\n                    mode == ChatMode.IMAGE -> "Создай вариант приложенного изображения"\n                    pending.isNotEmpty() && pending.all { it.mimeType.startsWith("audio/") } && persistentChatFiles.isEmpty() -> "Голосовое сообщение"\n                    persistentChatFiles.isNotEmpty() -> "[Файлы чата]"\n                    else -> "[Вложения]"\n                }\n            },\n''',
    "voice message label"
)
vm = replace_once(
    vm,
    '''    private fun cleanupTempAttachments(items: List<PendingAttachment>) {\n        val cameraRoot = File(context.cacheDir, "camera")\n        items.mapNotNull { it.localPath }.forEach { path ->\n            runCatching {\n                val file = File(path).canonicalFile\n                val root = cameraRoot.canonicalFile\n                if (file.path.startsWith(root.path + File.separator)) file.delete()\n            }\n        }\n    }\n''',
    '''    private fun cleanupTempAttachments(items: List<PendingAttachment>) {\n        val tempRoots = listOf(File(context.cacheDir, "camera"), File(context.cacheDir, "voice"))\n            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }\n        items.mapNotNull { it.localPath }.forEach { path ->\n            runCatching {\n                val file = File(path).canonicalFile\n                if (tempRoots.any { root -> file.path.startsWith(root.path + File.separator) }) file.delete()\n            }\n        }\n    }\n''',
    "voice temp cleanup"
)
vm_path.write_text(vm)

# OpenRouter voice-only prompt
or_path = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
or_text = or_path.read_text()
or_text = replace_once(
    or_text,
    '''        val parts = JsonArray()\n        parts.add(JsonObject().apply {\n            addProperty("type", "text")\n            addProperty("text", text.ifBlank { "Изучи вложения и помоги мне с ними." })\n        })\n''',
    '''        val parts = JsonArray()\n        val fallbackText = if (attachments.isNotEmpty() && attachments.all { it.mimeType.startsWith("audio/") }) {\n            "Ответь на голосовое сообщение."\n        } else {\n            "Изучи вложения и помоги мне с ними."\n        }\n        parts.add(JsonObject().apply {\n            addProperty("type", "text")\n            addProperty("text", text.ifBlank { fallbackText })\n        })\n''',
    "voice fallback prompt"
)
or_path.write_text(or_text)

# Compose UI
ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text()
ui = replace_once(ui, 'import android.content.ClipData\n', 'import android.Manifest\nimport android.content.ClipData\n', "manifest import")
ui = replace_once(ui, 'import android.content.Context\n', 'import android.content.Context\nimport android.content.pm.PackageManager\n', "package manager import")
ui = replace_once(ui, 'import androidx.compose.material.icons.outlined.Language\n', 'import androidx.compose.material.icons.outlined.Language\nimport androidx.compose.material.icons.outlined.Mic\n', "mic icon import")
ui = replace_once(ui, 'import androidx.core.content.FileProvider\n', 'import androidx.core.content.ContextCompat\nimport androidx.core.content.FileProvider\n', "context compat import")
ui = replace_once(ui, 'import com.ayuemin.ymnik.ChatViewModel\n', 'import com.ayuemin.ymnik.ChatViewModel\nimport com.ayuemin.ymnik.audio.WavRecorder\n', "wav recorder import")

ui = replace_once(
    ui,
    '''    val listState = rememberLazyListState()\n    val context = LocalContext.current\n''',
    '''    val listState = rememberLazyListState()\n    val context = LocalContext.current\n    val voiceRecorder = remember(context) { WavRecorder(context) }\n    var isRecording by remember { mutableStateOf(false) }\n    var recordingStartedAt by remember { mutableStateOf(0L) }\n    var recordingSeconds by remember { mutableIntStateOf(0) }\n\n    DisposableEffect(voiceRecorder) {\n        onDispose { voiceRecorder.cancel() }\n    }\n''',
    "voice recorder state"
)
ui = replace_once(
    ui,
    '''    val textModelInfo = state.availableTextModels.firstOrNull { it.id == activeTextModel }\n    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n''',
    '''    val textModelInfo = state.availableTextModels.firstOrNull { it.id == activeTextModel }\n    val microphoneAvailable = !imagePromptMode && activeProfile.type == ProviderType.OPENROUTER && textModelInfo?.accepts("audio") == true\n    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n''',
    "microphone capability"
)
ui = replace_once(
    ui,
    '''    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n''',
    '''    fun startVoiceRecording() {\n        if (!microphoneAvailable || state.isLoading || state.requestActive || imagePromptMode) return\n        runCatching { voiceRecorder.start() }\n            .onSuccess {\n                recordingStartedAt = System.currentTimeMillis()\n                recordingSeconds = 0\n                isRecording = true\n            }\n            .onFailure {\n                Toast.makeText(context, it.message ?: "Не удалось начать запись", Toast.LENGTH_SHORT).show()\n            }\n    }\n\n    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->\n        if (granted) startVoiceRecording()\n        else Toast.makeText(context, "Для голосового сообщения нужен доступ к микрофону", Toast.LENGTH_SHORT).show()\n    }\n\n    LaunchedEffect(isRecording, recordingStartedAt) {\n        while (isRecording) {\n            recordingSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(0)\n            if (recordingSeconds >= 600) {\n                val file = voiceRecorder.stop()\n                isRecording = false\n                file?.let { vm.addVoiceRecording(it.absolutePath) }\n                Toast.makeText(context, "Достигнут максимум записи 10 минут", Toast.LENGTH_SHORT).show()\n                break\n            }\n            delay(250)\n        }\n    }\n\n    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n''',
    "voice permission launcher"
)
ui = replace_once(
    ui,
    '''                OutlinedTextField(\n''',
    '''                if (isRecording) {\n                    RecordingStatusBar(\n                        seconds = recordingSeconds,\n                        onCancel = {\n                            voiceRecorder.cancel()\n                            isRecording = false\n                            recordingStartedAt = 0L\n                            recordingSeconds = 0\n                        }\n                    )\n                }\n\n                OutlinedTextField(\n''',
    "recording status bar placement"
)
old_trailing = '''                    trailingIcon = {\n                        IconButton(\n                            onClick = {\n                                if (state.requestActive) {\n                                    vm.stopGeneration()\n                                } else if (imagePromptMode) {\n                                    if (vm.sendImagePrompt(text)) {\n                                        text = ""\n                                        imagePromptMode = false\n                                    }\n                                } else {\n                                    vm.send(text)\n                                    text = ""\n                                }\n                            },\n                            enabled = state.requestActive || (!state.isLoading && (\n                                text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())\n                            ))\n                        ) {\n                            if (state.requestActive) {\n                                WorkingStopIcon()\n                            } else {\n                                Icon(\n                                    Icons.Outlined.Send,\n                                    contentDescription = if (imagePromptMode) "Создать изображение" else "Отправить"\n                                )\n                            }\n                        }\n                    },\n'''
new_trailing = '''                    trailingIcon = {\n                        Row(verticalAlignment = Alignment.CenterVertically) {\n                            if (!imagePromptMode) {\n                                IconButton(\n                                    onClick = {\n                                        if (isRecording) {\n                                            val file = voiceRecorder.stop()\n                                            isRecording = false\n                                            recordingStartedAt = 0L\n                                            recordingSeconds = 0\n                                            file?.let { vm.addVoiceRecording(it.absolutePath) }\n                                        } else if (microphoneAvailable) {\n                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {\n                                                startVoiceRecording()\n                                            } else {\n                                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)\n                                            }\n                                        }\n                                    },\n                                    enabled = isRecording || (!state.isLoading && !state.requestActive && microphoneAvailable),\n                                    modifier = Modifier.size(42.dp)\n                                ) {\n                                    Icon(\n                                        Icons.Outlined.Mic,\n                                        contentDescription = when {\n                                            isRecording -> "Остановить запись и прикрепить"\n                                            microphoneAvailable -> "Записать голосовое сообщение"\n                                            else -> "Выбранная модель не поддерживает аудио"\n                                        },\n                                        tint = when {\n                                            isRecording -> MaterialTheme.colorScheme.error\n                                            microphoneAvailable -> MaterialTheme.colorScheme.onSurfaceVariant\n                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)\n                                        }\n                                    )\n                                }\n                            }\n                            IconButton(\n                                onClick = {\n                                    if (state.requestActive) {\n                                        vm.stopGeneration()\n                                    } else if (isRecording) {\n                                        val file = voiceRecorder.stop()\n                                        isRecording = false\n                                        recordingStartedAt = 0L\n                                        recordingSeconds = 0\n                                        if (file != null && vm.addVoiceRecording(file.absolutePath)) {\n                                            vm.send(text)\n                                            text = ""\n                                        }\n                                    } else if (imagePromptMode) {\n                                        if (vm.sendImagePrompt(text)) {\n                                            text = ""\n                                            imagePromptMode = false\n                                        }\n                                    } else {\n                                        vm.send(text)\n                                        text = ""\n                                    }\n                                },\n                                enabled = state.requestActive || isRecording || (!state.isLoading && (\n                                    text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())\n                                ))\n                            ) {\n                                if (state.requestActive) {\n                                    WorkingStopIcon()\n                                } else {\n                                    Icon(\n                                        Icons.Outlined.Send,\n                                        contentDescription = when {\n                                            isRecording -> "Остановить запись и отправить"\n                                            imagePromptMode -> "Создать изображение"\n                                            else -> "Отправить"\n                                        }\n                                    )\n                                }\n                            }\n                        }\n                    },\n'''
ui = replace_once(ui, old_trailing, new_trailing, "microphone composer controls")
ui = replace_once(
    ui,
    '''@Composable\nprivate fun ComposerInlineIndicator(\n''',
    '''@Composable\nprivate fun RecordingStatusBar(seconds: Int, onCancel: () -> Unit) {\n    val transition = rememberInfiniteTransition(label = "voiceRecording")\n    val pulse by transition.animateFloat(\n        initialValue = 0.82f,\n        targetValue = 1.12f,\n        animationSpec = infiniteRepeatable(\n            animation = tween(durationMillis = 620),\n            repeatMode = RepeatMode.Reverse\n        ),\n        label = "voicePulse"\n    )\n    Surface(\n        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),\n        shape = RoundedCornerShape(14.dp),\n        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)\n    ) {\n        Row(\n            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {\n            Icon(\n                Icons.Outlined.Mic,\n                contentDescription = null,\n                modifier = Modifier.size(20.dp).scale(pulse),\n                tint = MaterialTheme.colorScheme.error\n            )\n            Spacer(Modifier.width(9.dp))\n            Text(\n                "Запись ${formatRecordingDuration(seconds)}",\n                modifier = Modifier.weight(1f),\n                fontWeight = FontWeight.SemiBold,\n                color = MaterialTheme.colorScheme.onErrorContainer\n            )\n            TextButton(onClick = onCancel) { Text("Отмена") }\n        }\n    }\n}\n\nprivate fun formatRecordingDuration(seconds: Int): String =\n    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)\n\n@Composable\nprivate fun ComposerInlineIndicator(\n''',
    "recording status composable"
)
ui_path.write_text(ui)

# Version
build_path = Path("app/build.gradle.kts")
build = build_path.read_text()
build = replace_once(build, '// Umnik v1.2.0-beta.3\n', '// Umnik v1.2.0-beta.4\n', "version comment")
build = replace_once(build, '        versionCode = 32\n', '        versionCode = 33\n', "version code")
build = replace_once(build, '        versionName = "1.2.0-beta.3"\n', '        versionName = "1.2.0-beta.4"\n', "version name")
build_path.write_text(build)

# Changelog
changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text()
entry = '''## v1.2.0-beta.4 - 2026-09-11\n\n- В поле ввода добавлен микрофон для голосовых сообщений. Он активен только у моделей OpenRouter, которые в метаданных заявляют поддержку аудиовхода.\n- Голос записывается локально в WAV PCM 16-bit mono 16 kHz и отправляется модели напрямую как аудио без предварительного распознавания в текст.\n- Во время записи показываются пульсирующий индикатор, таймер и кнопка отмены; кнопка отправки завершает запись и сразу отправляет голосовое сообщение.\n- Повторное нажатие на микрофон завершает запись и оставляет WAV вложением, чтобы при желании добавить текст перед отправкой.\n- На моделях без поддержки аудио микрофон остаётся видимым, но неактивным. Максимальная длительность одной записи — 10 минут.\n\n'''
changelog = replace_once(changelog, '## Unreleased\n\n', '## Unreleased\n\n' + entry, "changelog beta4")
changelog_path.write_text(changelog)
