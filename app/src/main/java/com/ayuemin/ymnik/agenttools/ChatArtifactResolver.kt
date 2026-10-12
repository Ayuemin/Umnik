package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.model.ChatFile
import java.io.File

/**
 * Resolves an existing chat-file ID without creating a second file store.
 *
 * Only records supplied by the trusted repository for [chatId] are eligible.
 * The resolved file must still exist inside that chat's canonical private
 * directory. This never grants a model access to an arbitrary local path.
 *
 * The caller must separately check its task grant and operation permissions.
 */
internal class ChatArtifactResolver(private val appFilesDir: File) {
    fun verifiedFiles(chatId: String, repositoryFiles: List<ChatFile>): List<ChatFile> {
        if (chatId.isBlank()) return emptyList()
        val safeChatId = chatId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        if (safeChatId.isBlank()) return emptyList()
        val ownedRoot = File(File(appFilesDir, "chat_files"), safeChatId).canonicalFile
        val ownedPrefix = ownedRoot.path + File.separator
        val idCounts = repositoryFiles.groupingBy(ChatFile::id).eachCount()

        return repositoryFiles.mapNotNull { entry ->
            if (entry.id.isBlank() || idCounts[entry.id] != 1 || entry.localPath.isBlank()) {
                return@mapNotNull null
            }
            val file = runCatching { File(entry.localPath).canonicalFile }.getOrNull()
                ?: return@mapNotNull null
            if (!file.path.startsWith(ownedPrefix) || !file.isFile) return@mapNotNull null
            val realSize = file.length()
            if (realSize != entry.size || realSize > MAX_ARTIFACT_BYTES) return@mapNotNull null
            entry.copy(localPath = file.path)
        }
    }

    fun resolve(
        chatId: String,
        fileId: String,
        repositoryFiles: List<ChatFile>
    ): ChatFile? {
        if (fileId.isBlank()) return null
        return verifiedFiles(chatId, repositoryFiles).firstOrNull { it.id == fileId }
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 50L * 1024L * 1024L
    }
}
