package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.PendingAttachment
import java.io.File
import java.util.UUID

class ChatFileRepository(private val context: Context) {
    private val root = File(context.filesDir, "chat_files").apply { mkdirs() }

    fun importFile(chatId: String, attachment: PendingAttachment): ChatFile {
        val dir = File(root, safeSegment(chatId)).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val target = File(dir, "${id}_${safeName(attachment.name)}")
        runCatching {
            val input = attachment.localPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).inputStream() }
                ?: context.contentResolver.openInputStream(Uri.parse(attachment.uri))
                ?: error("Не удалось открыть ${attachment.name}")
            input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
        }.onFailure {
            target.delete()
            throw it
        }
        return ChatFile(
            id = id,
            name = attachment.name,
            mimeType = attachment.mimeType,
            localPath = target.absolutePath,
            size = target.length()
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
}
