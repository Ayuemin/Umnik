package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.model.ChatFile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArtifactResolverTest {
    private fun entry(id: String, file: File): ChatFile =
        ChatFile(id, file.name, "text/plain", file.absolutePath, file.length())

    @Test fun resolvesOnlyExistingFileInOwningChatDirectory() {
        val root = Files.createTempDirectory("umnik-artifact-test").toFile()
        try {
            val dir = File(root, "chat_files/chat-A").apply { mkdirs() }
            val file = File(dir, "a.txt").apply { writeText("verified") }
            val resolver = ChatArtifactResolver(root)
            val found = resolver.resolve("chat-A", "artifact-1", listOf(entry("artifact-1", file)))
            assertEquals(file.canonicalPath, found?.localPath)
            assertNull(resolver.resolve("chat-B", "artifact-1", listOf(entry("artifact-1", file))))
            assertNull(resolver.resolve("chat-A", "unknown", listOf(entry("artifact-1", file))))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsForeignAndMissingFilesAndSizeMismatches() {
        val root = Files.createTempDirectory("umnik-artifact-test").toFile()
        try {
            val ownDir = File(root, "chat_files/chat-A").apply { mkdirs() }
            val anotherDir = File(root, "chat_files/chat-B").apply { mkdirs() }
            val foreign = File(anotherDir, "foreign.txt").apply { writeText("secret") }
            val missing = File(ownDir, "missing.txt")
            val own = File(ownDir, "own.txt").apply { writeText("hello") }
            val resolver = ChatArtifactResolver(root)
            val entries = listOf(
                entry("foreign", foreign),
                ChatFile("missing", "missing.txt", "text/plain", missing.path, 1),
                entry("changed", own).copy(size = 99)
            )
            assertTrue(resolver.verifiedFiles("chat-A", entries).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesAmbiguousIdsEvenWhenBothFilesAreInSameChat() {
        val root = Files.createTempDirectory("umnik-artifact-test").toFile()
        try {
            val dir = File(root, "chat_files/chat-A").apply { mkdirs() }
            val first = File(dir, "a.txt").apply { writeText("one") }
            val second = File(dir, "b.txt").apply { writeText("two") }
            val resolver = ChatArtifactResolver(root)
            assertNull(resolver.resolve("chat-A", "duplicate", listOf(
                entry("duplicate", first), entry("duplicate", second)
            )))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun symbolicLinkCannotEscapeChatRoot() {
        val root = Files.createTempDirectory("umnik-artifact-test").toFile()
        try {
            val dir = File(root, "chat_files/chat-A").apply { mkdirs() }
            val outside = File(root, "outside.txt").apply { writeText("private") }
            val link = File(dir, "link.txt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(ChatArtifactResolver(root).resolve("chat-A", "link-id", listOf(entry("link-id", link))))
        } finally {
            root.deleteRecursively()
        }
    }
}
