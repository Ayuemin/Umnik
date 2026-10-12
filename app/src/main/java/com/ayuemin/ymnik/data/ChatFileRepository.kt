package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.PendingAttachment
import java.io.File
import java.util.UUID

class ChatFileRepository(private val context: Context) {
    private val root = File(context.filesDir, "chat_files").apply { mkdirs() }

    fun importFile(chatId: String, attachment: PendingAttachment): ChatFile =
        importBoundedFile(chatId, attachment, MAX_BYTES)

    /**
     * Only APKs already downloaded to the internal GitHub artifact cache may
     * use the larger limit. Ordinary user attachments stay capped at 50 MiB.
     */
    fun importGeneratedGitHubApk(chatId: String, attachment: PendingAttachment): ChatFile {
        require(attachment.mimeType == "application/vnd.android.package-archive") { "invalid_apk_type" }
        val trustedRoot = File(context.cacheDir, "github_artifacts").canonicalFile
        val source = attachment.localPath?.let { File(it).canonicalFile }
        require(source != null && source.isFile &&
            source.path.startsWith(trustedRoot.path + File.separator) &&
            source.name.endsWith(".apk", ignoreCase = true)) { "invalid_apk_source" }
        require(source.length() in 1..MAX_GENERATED_APK_BYTES) { "generated_apk_too_large" }
        return importBoundedFile(chatId, attachment, MAX_GENERATED_APK_BYTES)
    }

    private fun importBoundedFile(
        chatId: String, attachment: PendingAttachment, maxBytes: Long
    ): ChatFile {
        require(attachment.size <= maxBytes || attachment.size <= 0L) {
            "Файл чата должен быть не больше ${maxBytes / 1024 / 1024} МБ"
        }
        val dir = File(root, safeSegment(chatId)).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val target = File(dir, "${id}_${safeName(attachment.name)}")
        runCatching {
            val input = attachment.localPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).inputStream() }
                ?: context.contentResolver.openInputStream(Uri.parse(attachment.uri))
                ?: error("Не удалось открыть ${attachment.name}")
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maxBytes) { "chat_file_size_limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }.onFailure {
            target.delete()
            throw it
        }
        val actualSize = target.length()
        require(actualSize <= maxBytes) {
            target.delete()
            "Файл чата должен быть не больше ${maxBytes / 1024 / 1024} МБ"
        }
        return ChatFile(
            id = id,
            name = attachment.name,
            mimeType = attachment.mimeType,
            localPath = target.absolutePath,
            size = actualSize
        )
    }

    fun delete(file: ChatFile): Boolean = runCatching {
        val target = File(file.localPath).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) return false
        target.delete()
    }.getOrDefault(false)

    fun deleteChat(chatId: String) {
        File(root, safeSegment(chatId)).deleteRecursively()
    }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)
        .ifBlank { "file" }

    companion object {
        private const val MAX_BYTES = 50L * 1024L * 1024L
        private const val MAX_GENERATED_APK_BYTES = 200L * 1024L * 1024L
    }
}
