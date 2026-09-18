package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ChatContextMode
import com.ayuemin.ymnik.model.ChatMemoryCheckpoint
import com.ayuemin.ymnik.model.ChatMemoryChunk
import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.model.ChatMemoryHit
import com.ayuemin.ymnik.model.ChatMemorySnapshot
import com.ayuemin.ymnik.model.ChatMemoryStats
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Private long-term memory for chat history. It deliberately lives outside
 * user files and outside the Knowledge Base/RAG repository.
 */
class ChatMemoryRepository(private val context: Context) {
    private val root = File(context.filesDir, "chat_memory").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun settings(): ChatMemoryGlobalSettings = runCatching {
        prefs.getString(KEY_SETTINGS, null)?.let { gson.fromJson(it, ChatMemoryGlobalSettings::class.java) }
    }.getOrNull()?.let(::sanitize) ?: ChatMemoryGlobalSettings()

    fun saveSettings(value: ChatMemoryGlobalSettings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(sanitize(value))).apply()
    }

    /**
     * Agent conversations may have a fully isolated memory/context profile.
     * Ordinary chats without an override continue to use the global chat defaults.
     */
    fun settingsForChat(chatId: String): ChatMemoryGlobalSettings = runCatching {
        prefs.getString(settingsKey(chatId), null)
            ?.let { gson.fromJson(it, ChatMemoryGlobalSettings::class.java) }
    }.getOrNull()?.let(::sanitize) ?: settings()

    fun saveSettingsForChat(chatId: String, value: ChatMemoryGlobalSettings?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(settingsKey(chatId))
        } else {
            editor.putString(settingsKey(chatId), gson.toJson(sanitize(value)))
        }
        editor.apply()
    }

    /** Null means that this chat follows the global default context mode. */
    fun modeOverride(chatId: String): ChatContextMode? {
        val key = modeKey(chatId)
        if (!prefs.contains(key)) return null
        return runCatching {
            ChatContextMode.valueOf(prefs.getString(key, null).orEmpty())
        }.getOrNull()
    }

    /** Effective mode after applying the global default to chats without an override. */
    fun mode(chatId: String): ChatContextMode = modeOverride(chatId) ?: settingsForChat(chatId).defaultContextMode

    fun saveMode(chatId: String, mode: ChatContextMode?) {
        val editor = prefs.edit()
        if (mode == null) editor.remove(modeKey(chatId)) else editor.putString(modeKey(chatId), mode.name)
        editor.apply()
    }

    fun snapshot(chatId: String): ChatMemorySnapshot? = synchronized(this) {
        val file = snapshotFile(chatId, create = false) ?: return@synchronized null
        if (!file.isFile) return@synchronized null
        runCatching {
            AtomicJsonFile(file).read { raw ->
                runCatching { gson.fromJson(raw, ChatMemorySnapshot::class.java) }.isSuccess
            }?.let { gson.fromJson(it, ChatMemorySnapshot::class.java) }
        }.getOrNull()
    }

    @Synchronized
    fun appendCheckpoint(
        chatId: String,
        settings: ChatMemoryGlobalSettings,
        checkpoint: ChatMemoryCheckpoint,
        chunks: List<ChatMemoryChunk>,
        vectors: List<FloatArray>,
        stateCard: String,
        fingerprints: Map<String, String>
    ): ChatMemorySnapshot {
        require(chunks.size == vectors.size) { "Число фрагментов памяти и embeddings не совпадает" }
        val clean = sanitize(settings)
        val current = snapshot(chatId)?.takeIf {
            it.embeddingModelId == clean.embeddingModelId &&
                it.summaryModelId == clean.summaryModelId &&
                it.chunkTokens == clean.chunkTokens &&
                it.chunkOverlapTokens == clean.chunkOverlapTokens &&
                it.embeddingContextTokens == clean.embeddingContextTokens
        } ?: ChatMemorySnapshot(
            chatId = chatId,
            embeddingModelId = clean.embeddingModelId,
            summaryModelId = clean.summaryModelId,
            chunkTokens = clean.chunkTokens,
            chunkOverlapTokens = clean.chunkOverlapTokens,
            embeddingContextTokens = clean.embeddingContextTokens
        )

        val storedChunks = chunks.mapIndexed { index, chunk ->
            val vector = vectors[index]
            require(vector.isNotEmpty()) { "Embedding памяти пуст" }
            writeVector(vectorFile(chatId, chunk.id), vector)
            chunk.copy(vectorDimension = vector.size)
        }
        val next = current.copy(
            stateCard = stateCard.take(clean.stateCardMaxChars),
            checkpoints = current.checkpoints + checkpoint,
            chunks = current.chunks + storedChunks,
            indexedFingerprints = current.indexedFingerprints + fingerprints,
            updatedAt = System.currentTimeMillis()
        )
        saveSnapshot(next)
        return next
    }

    suspend fun retrieve(
        chatId: String,
        query: FloatArray,
        topK: Int,
        minimumScore: Double,
        neighborRadius: Int = 0
    ): List<ChatMemoryHit> = withContext(Dispatchers.IO) {
        val snapshot = snapshot(chatId) ?: return@withContext emptyList()
        if (query.isEmpty()) return@withContext emptyList()
        val queryNorm = sqrt(query.fold(0.0) { acc, value -> acc + value * value })
        if (queryNorm <= 0.0) return@withContext emptyList()

        data class Scored(val chunk: ChatMemoryChunk, val score: Double)

        val scored = snapshot.chunks.mapNotNull { chunk ->
            if (chunk.vectorDimension != query.size) return@mapNotNull null
            val vector = readVector(vectorFile(chatId, chunk.id)) ?: return@mapNotNull null
            if (vector.size != query.size) return@mapNotNull null
            var dot = 0.0
            var norm = 0.0
            for (index in query.indices) {
                dot += query[index] * vector[index]
                norm += vector[index] * vector[index]
            }
            val score = if (norm <= 0.0) 0.0 else dot / (queryNorm * sqrt(norm))
            Scored(chunk, score)
        }
        val centers = scored
            .filter { it.score >= minimumScore }
            .sortedByDescending { it.score }
            .take(topK.coerceIn(1, 10))
        if (centers.isEmpty()) return@withContext emptyList()

        val radius = neighborRadius.coerceIn(0, 1)
        val scoredById = scored.associateBy { it.chunk.id }
        val selectedIds = linkedSetOf<String>()
        centers.forEach { center ->
            snapshot.chunks.forEach { candidate ->
                if (
                    candidate.checkpointId == center.chunk.checkpointId &&
                    abs(candidate.ordinal - center.chunk.ordinal) <= radius &&
                    candidate.id in scoredById
                ) {
                    selectedIds += candidate.id
                }
            }
        }

        selectedIds.mapNotNull { id ->
            val item = scoredById[id] ?: return@mapNotNull null
            ChatMemoryHit(
                text = item.chunk.text,
                messageIds = item.chunk.messageIds,
                startTimestamp = item.chunk.startTimestamp,
                endTimestamp = item.chunk.endTimestamp,
                score = item.score
            )
        }.sortedWith(compareBy<ChatMemoryHit> { it.startTimestamp }.thenBy { it.endTimestamp })
    }

    fun stats(chatId: String): ChatMemoryStats {
        val snap = snapshot(chatId)
        val dir = chatDir(chatId, create = false)
        return ChatMemoryStats(
            checkpoints = snap?.checkpoints?.size ?: 0,
            chunks = snap?.chunks?.size ?: 0,
            bytes = dir?.let(::directorySize) ?: 0L,
            stateCardChars = snap?.stateCard?.length ?: 0,
            updatedAt = snap?.updatedAt
        )
    }

    fun totalBytes(): Long = directorySize(root)

    /** Removes generated memory but preserves the selected context-mode override. */
    @Synchronized
    fun clearMemory(chatId: String) {
        chatDir(chatId, create = false)?.deleteRecursively()
    }

    /** Removes generated memory for all chats but preserves per-chat modes and global settings. */
    @Synchronized
    fun clearAllMemory() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    /** Called when the chat itself is deleted. */
    @Synchronized
    fun deleteChat(chatId: String) {
        clearMemory(chatId)
        prefs.edit()
            .remove(modeKey(chatId))
            .remove(settingsKey(chatId))
            .apply()
    }

    private fun saveSnapshot(value: ChatMemorySnapshot) {
        val file = snapshotFile(value.chatId, create = true) ?: error("Не удалось открыть хранилище памяти")
        AtomicJsonFile(file).write(gson.toJson(value)) { raw ->
            runCatching { gson.fromJson(raw, ChatMemorySnapshot::class.java) }.isSuccess
        }
    }

    private fun snapshotFile(chatId: String, create: Boolean): File? =
        chatDir(chatId, create)?.let { File(it, SNAPSHOT_FILE) }

    private fun vectorFile(chatId: String, chunkId: String): File {
        val dir = File(chatDir(chatId, true) ?: error("Не удалось открыть память чата"), "vectors").apply { mkdirs() }
        return File(dir, "${safe(chunkId)}.bin")
    }

    private fun chatDir(chatId: String, create: Boolean): File? {
        val dir = File(root, safe(chatId))
        if (create) dir.mkdirs()
        return dir.takeIf { it.exists() || create }
    }

    private fun writeVector(file: File, vector: FloatArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
            output.writeInt(VECTOR_MAGIC)
            output.writeInt(vector.size)
            vector.forEach(output::writeFloat)
        }
        if (file.exists() && !file.delete()) error("Не удалось заменить embedding памяти")
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun readVector(file: File): FloatArray? = runCatching {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            if (input.readInt() != VECTOR_MAGIC) return@use null
            val dimension = input.readInt()
            if (dimension <= 0 || dimension > 100_000) return@use null
            FloatArray(dimension) { input.readFloat() }
        }
    }.getOrNull()

    private fun sanitize(value: ChatMemoryGlobalSettings): ChatMemoryGlobalSettings {
        val legacy = value.schemaVersion < ChatMemoryGlobalSettings.CURRENT_SCHEMA_VERSION
        val autoThreshold = value.autoThresholdTokens.coerceIn(8_000, 1_000_000)
        val economyThreshold = value.economyThresholdTokens.coerceIn(4_000, autoThreshold)
        val defaultMode = if (legacy) {
            ChatContextMode.AUTO
        } else {
            runCatching { value.defaultContextMode }.getOrNull() ?: ChatContextMode.AUTO
        }
        val overlap = if (legacy) 80 else value.chunkOverlapTokens.coerceIn(0, 1_000)
        val neighbors = if (legacy) 1 else value.neighborChunks.coerceIn(0, 1)
        val embeddingId = runCatching { value.embeddingModelId }.getOrNull()?.trim().orEmpty()
        val summaryId = runCatching { value.summaryModelId }.getOrNull()?.trim().orEmpty()
        return value.copy(
            schemaVersion = ChatMemoryGlobalSettings.CURRENT_SCHEMA_VERSION,
            embeddingModelId = embeddingId.ifBlank { ChatMemoryGlobalSettings.DEFAULT_EMBEDDING_MODEL },
            summaryModelId = summaryId.ifBlank { ChatMemoryGlobalSettings.DEFAULT_SUMMARY_MODEL },
            defaultContextMode = defaultMode,
            autoThresholdTokens = autoThreshold,
            economyThresholdTokens = economyThreshold,
            autoRecentMessages = value.autoRecentMessages.coerceIn(4, 30),
            economyRecentMessages = value.economyRecentMessages.coerceIn(2, 20),
            topK = value.topK.coerceIn(1, 10),
            checkpointTokens = value.checkpointTokens.coerceIn(4_000, 30_000),
            chunkTokens = value.chunkTokens.coerceIn(128, 4_000),
            chunkOverlapTokens = overlap,
            neighborChunks = neighbors,
            embeddingContextTokens = value.embeddingContextTokens?.coerceIn(128, 1_000_000),
            minimumScore = value.minimumScore.coerceIn(-1.0, 1.0),
            stateCardMaxChars = value.stateCardMaxChars.coerceIn(1_000, 20_000)
        )
    }

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
    private fun modeKey(chatId: String): String = "mode::$chatId"
    private fun settingsKey(chatId: String): String = "settings::$chatId"

    companion object {
        private const val KEY_SETTINGS = "global_settings"
        private const val SNAPSHOT_FILE = "snapshot.json"
        private const val VECTOR_MAGIC = 0x554D4D45
    }
}
