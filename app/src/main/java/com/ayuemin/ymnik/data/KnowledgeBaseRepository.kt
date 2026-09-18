package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import com.ayuemin.ymnik.model.KnowledgeBaseSettings
import com.ayuemin.ymnik.model.KnowledgeChunk
import com.ayuemin.ymnik.model.KnowledgeDocument
import com.ayuemin.ymnik.model.KnowledgeHit
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.PendingAttachment
import com.ayuemin.ymnik.network.OpenRouterEmbeddingClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.sqrt

class KnowledgeBaseRepository(private val context: Context) {
    private val root = File(context.filesDir, "knowledge_base").apply { mkdirs() }
    private val manifest = AtomicJsonFile(File(root, "manifest.json"))
    private val prefs = context.getSharedPreferences("knowledge_base", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val documentsType = object : TypeToken<List<KnowledgeDocument>>() {}.type
    private val chunksType = object : TypeToken<List<KnowledgeChunk>>() {}.type

    @Volatile
    private var documents: List<KnowledgeDocument> = loadDocuments()

    fun documents(kind: KnowledgeOwnerKind, ownerId: String): List<KnowledgeDocument> =
        documents.filter { it.ownerKind == kind && it.ownerId == ownerId }.sortedByDescending { it.indexedAt }

    fun allDocuments(): List<KnowledgeDocument> = documents

    fun settings(kind: KnowledgeOwnerKind, ownerId: String): KnowledgeBaseSettings = runCatching {
        prefs.getString(settingsKey(kind, ownerId), null)
            ?.let { gson.fromJson(it, KnowledgeBaseSettings::class.java) }
    }.getOrNull()?.let(::sanitizeSettings) ?: KnowledgeBaseSettings()

    fun saveSettings(kind: KnowledgeOwnerKind, ownerId: String, value: KnowledgeBaseSettings) {
        prefs.edit().putString(settingsKey(kind, ownerId), gson.toJson(sanitizeSettings(value))).apply()
    }

    fun hasEnabledKnowledge(owners: List<Pair<KnowledgeOwnerKind, String>>): Boolean = owners.any { (kind, id) ->
        settings(kind, id).enabled && documents(kind, id).isNotEmpty()
    }

    suspend fun index(
        kind: KnowledgeOwnerKind,
        ownerId: String,
        attachment: PendingAttachment,
        embeddingModelId: String,
        apiKey: String,
        baseUrl: String,
        embeddings: OpenRouterEmbeddingClient,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): KnowledgeDocument = withContext(Dispatchers.IO) {
        require(ownerId.isNotBlank()) { "Не выбран владелец базы знаний" }
        require(embeddingModelId.isNotBlank()) { "Не выбрана embedding-модель" }
        require(attachment.size <= MAX_SOURCE_BYTES || attachment.size <= 0L) {
            "Файл для базы знаний должен быть не больше ${MAX_SOURCE_BYTES / 1024 / 1024} МБ"
        }

        val id = UUID.randomUUID().toString()
        val dir = documentDir(kind, ownerId, id).apply { mkdirs() }
        try {
            val source = File(dir, "source${extensionFor(attachment.name)}")
            copyAttachment(attachment, source)
            require(source.length() <= MAX_SOURCE_BYTES) {
                "Файл для базы знаний должен быть не больше ${MAX_SOURCE_BYTES / 1024 / 1024} МБ"
            }
            val sections = KnowledgeTextExtractor.extract(context, source, attachment.name, attachment.mimeType)
            val chunks = KnowledgeChunker.chunk(sections)
            require(chunks.isNotEmpty()) { "В документе не найден текст для индексации" }
            require(chunks.size <= MAX_CHUNKS_PER_DOCUMENT) {
                "Документ слишком велик: ${chunks.size} фрагментов. Максимум сейчас $MAX_CHUNKS_PER_DOCUMENT"
            }

            val vectors = mutableListOf<FloatArray>()
            chunks.chunked(EMBED_BATCH_SIZE).forEach { batch ->
                val embedded = embeddings.embed(
                    apiKey = apiKey,
                    modelId = embeddingModelId,
                    inputs = batch.map { it.text },
                    inputType = "search_document",
                    baseUrl = baseUrl
                )
                require(embedded.all { it.isNotEmpty() }) { "Embedding-модель вернула пустой вектор" }
                vectors += embedded
                onProgress(vectors.size.coerceAtMost(chunks.size), chunks.size)
            }
            val dimension = vectors.first().size
            require(vectors.all { it.size == dimension }) { "Embedding-модель вернула векторы разной размерности" }

            writeChunks(File(dir, CHUNKS_FILE), chunks)
            writeVectors(File(dir, VECTORS_FILE), vectors, dimension)

            val document = KnowledgeDocument(
                id = id,
                ownerKind = kind,
                ownerId = ownerId,
                name = attachment.name,
                mimeType = attachment.mimeType,
                localPath = source.absolutePath,
                size = source.length(),
                embeddingModelId = embeddingModelId,
                vectorDimension = dimension,
                chunkCount = chunks.size,
                charCount = chunks.sumOf { it.text.length },
                indexedAt = System.currentTimeMillis()
            )
            synchronized(this@KnowledgeBaseRepository) {
                documents = documents + document
                saveDocuments(documents)
            }
            document
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    suspend fun reindex(
        documentId: String,
        embeddingModelId: String,
        apiKey: String,
        baseUrl: String,
        embeddings: OpenRouterEmbeddingClient,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): KnowledgeDocument {
        val previous = documents.firstOrNull { it.id == documentId } ?: error("Документ базы знаний не найден")
        val source = File(previous.localPath)
        require(source.isFile) { "Исходный файл «${previous.name}» не найден" }
        val replacement = index(
            kind = previous.ownerKind,
            ownerId = previous.ownerId,
            attachment = PendingAttachment(
                uri = "knowledge://${previous.id}",
                name = previous.name,
                mimeType = previous.mimeType,
                size = source.length(),
                localPath = source.absolutePath
            ),
            embeddingModelId = embeddingModelId,
            apiKey = apiKey,
            baseUrl = baseUrl,
            embeddings = embeddings,
            onProgress = onProgress
        )
        deleteDocument(documentId)
        return replacement
    }

    fun deleteDocument(documentId: String): Boolean {
        val document = documents.firstOrNull { it.id == documentId } ?: return false
        synchronized(this) {
            val next = documents.filterNot { it.id == documentId }
            saveDocuments(next)
            documents = next
        }
        File(document.localPath).parentFile?.deleteRecursively()
        return true
    }

    fun deleteOwner(kind: KnowledgeOwnerKind, ownerId: String) {
        val owned = documents(kind, ownerId)
        if (owned.isNotEmpty()) {
            synchronized(this) {
                val ids = owned.map { it.id }.toSet()
                val next = documents.filterNot { it.id in ids }
                saveDocuments(next)
                documents = next
            }
            owned.forEach { File(it.localPath).parentFile?.deleteRecursively() }
        }
        prefs.edit().remove(settingsKey(kind, ownerId)).apply()
    }

    suspend fun retrieve(
        owners: List<Pair<KnowledgeOwnerKind, String>>,
        query: String,
        apiKey: String,
        baseUrl: String,
        embeddings: OpenRouterEmbeddingClient
    ): List<KnowledgeHit> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val queryVectors = mutableMapOf<String, FloatArray>()
        val result = mutableListOf<KnowledgeHit>()
        owners.distinct().forEach { (kind, ownerId) ->
            val settings = settings(kind, ownerId)
            if (!settings.enabled) return@forEach
            val ownerDocuments = documents(kind, ownerId).filter { documentFilesValid(it) }
            if (ownerDocuments.isEmpty()) return@forEach

            val ownerHits = mutableListOf<KnowledgeHit>()
            ownerDocuments.groupBy { it.embeddingModelId }.forEach { (modelId, modelDocuments) ->
                val queryVector = queryVectors.getOrPut(modelId) {
                    embeddings.embed(
                        apiKey = apiKey,
                        modelId = modelId,
                        inputs = listOf(cleanQuery),
                        inputType = "search_query",
                        baseUrl = baseUrl
                    ).first()
                }
                modelDocuments.forEach { document ->
                    ownerHits += scoreDocument(document, queryVector)
                }
            }
            result += ownerHits.sortedByDescending { it.score }.take(settings.topK)
        }
        result.sortedByDescending { it.score }
            .distinctBy { "${it.documentId}:${it.text.hashCode()}" }
            .take(MAX_TOTAL_HITS)
    }

    private fun scoreDocument(document: KnowledgeDocument, query: FloatArray): List<KnowledgeHit> {
        if (query.size != document.vectorDimension) return emptyList()
        val dir = File(document.localPath).parentFile ?: return emptyList()
        val chunks = readChunks(File(dir, CHUNKS_FILE))
        val vectorsFile = File(dir, VECTORS_FILE)
        if (!vectorsFile.isFile || chunks.isEmpty()) return emptyList()

        val queryNorm = sqrt(query.fold(0.0) { acc, value -> acc + value * value })
        if (queryNorm == 0.0) return emptyList()
        return DataInputStream(FileInputStream(vectorsFile).buffered()).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            val count = input.readInt()
            val dimension = input.readInt()
            if (magic != VECTOR_MAGIC || version != VECTOR_VERSION || dimension != query.size || count != chunks.size) {
                return@use emptyList()
            }
            buildList {
                repeat(count) { index ->
                    var dot = 0.0
                    var norm = 0.0
                    repeat(dimension) { d ->
                        val value = input.readFloat()
                        dot += query[d] * value
                        norm += value * value
                    }
                    val score = if (norm <= 0.0) 0.0 else dot / (queryNorm * sqrt(norm))
                    val chunk = chunks[index]
                    add(
                        KnowledgeHit(
                            documentId = document.id,
                            documentName = document.name,
                            text = chunk.text,
                            page = chunk.page,
                            score = score
                        )
                    )
                }
            }
        }
    }

    private fun documentFilesValid(document: KnowledgeDocument): Boolean {
        val dir = File(document.localPath).parentFile ?: return false
        return File(document.localPath).isFile && File(dir, CHUNKS_FILE).isFile && File(dir, VECTORS_FILE).isFile
    }

    private fun copyAttachment(attachment: PendingAttachment, target: File) {
        val input = attachment.localPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).inputStream() }
            ?: context.contentResolver.openInputStream(Uri.parse(attachment.uri))
            ?: error("Не удалось открыть ${attachment.name}")
        input.use { source -> target.outputStream().buffered().use { output -> source.copyTo(output) } }
    }

    private fun writeChunks(file: File, chunks: List<KnowledgeChunk>) {
        AtomicJsonFile(file).write(gson.toJson(chunks)) { raw ->
            runCatching { gson.fromJson<List<KnowledgeChunk>>(raw, chunksType) }.isSuccess
        }
    }

    private fun readChunks(file: File): List<KnowledgeChunk> = runCatching {
        AtomicJsonFile(file).read { raw -> runCatching { gson.fromJson<List<KnowledgeChunk>>(raw, chunksType) }.isSuccess }
            ?.let { gson.fromJson<List<KnowledgeChunk>>(it, chunksType) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun writeVectors(file: File, vectors: List<FloatArray>, dimension: Int) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
            output.writeInt(VECTOR_MAGIC)
            output.writeInt(VECTOR_VERSION)
            output.writeInt(vectors.size)
            output.writeInt(dimension)
            vectors.forEach { vector -> vector.forEach(output::writeFloat) }
        }
        if (file.exists() && !file.delete()) error("Не удалось заменить индекс embeddings")
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun loadDocuments(): List<KnowledgeDocument> = runCatching {
        manifest.read { raw -> runCatching { gson.fromJson<List<KnowledgeDocument>>(raw, documentsType) }.isSuccess }
            ?.let { gson.fromJson<List<KnowledgeDocument>>(it, documentsType) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun saveDocuments(value: List<KnowledgeDocument>) {
        manifest.write(gson.toJson(value)) { raw ->
            runCatching { gson.fromJson<List<KnowledgeDocument>>(raw, documentsType) }.isSuccess
        }
    }

    private fun sanitizeSettings(value: KnowledgeBaseSettings): KnowledgeBaseSettings = value.copy(
        embeddingModelId = value.embeddingModelId.trim().ifBlank { KnowledgeBaseSettings.DEFAULT_EMBEDDING_MODEL },
        topK = value.topK.coerceIn(1, 10)
    )

    private fun settingsKey(kind: KnowledgeOwnerKind, ownerId: String): String =
        "settings::${kind.name.lowercase()}::$ownerId"

    private fun documentDir(kind: KnowledgeOwnerKind, ownerId: String, documentId: String): File =
        if (kind == KnowledgeOwnerKind.AGENT) {
            File(context.filesDir, "agents/${safe(ownerId)}/knowledge/${safe(documentId)}")
        } else {
            File(root, safe(documentId))
        }

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)

    private fun extensionFor(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase().replace(Regex("[^a-z0-9]"), "")
        return if (suffix.isBlank()) "" else ".${suffix.take(12)}"
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L
        private const val MAX_CHUNKS_PER_DOCUMENT = 6000
        private const val EMBED_BATCH_SIZE = 24
        private const val MAX_TOTAL_HITS = 10
        private const val CHUNKS_FILE = "chunks.json"
        private const val VECTORS_FILE = "vectors.bin"
        private const val VECTOR_MAGIC = 0x554D4B42
        private const val VECTOR_VERSION = 1
    }
}
