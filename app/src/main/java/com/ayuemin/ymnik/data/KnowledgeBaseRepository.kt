package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import com.ayuemin.ymnik.model.KnowledgeBaseSettings
import com.ayuemin.ymnik.model.KnowledgeChunk
import com.ayuemin.ymnik.model.KnowledgeDocument
import com.ayuemin.ymnik.model.KnowledgeHit
import com.ayuemin.ymnik.model.KnowledgeIndexTask
import com.ayuemin.ymnik.model.KnowledgeIndexTaskStatus
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
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.sqrt

internal data class KnowledgeRetrievalResult(
    val hits: List<KnowledgeHit>,
    val candidateCount: Int,
    val thresholdDropped: Int
)

class KnowledgeBaseRepository(private val context: Context) {
    private val specialistsRoot = LegacyDomainStorageMigration.migrateDirectory(context, "agents", "specialists")
    private val root = File(context.filesDir, "knowledge_base").apply { mkdirs() }
    private val manifest = AtomicJsonFile(File(root, "manifest.json"))
    private val taskManifest = AtomicJsonFile(File(root, "index_tasks.json"))
    private val prefs = context.getSharedPreferences("knowledge_base", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val documentsType = object : TypeToken<List<KnowledgeDocument>>() {}.type
    private val chunksType = object : TypeToken<List<KnowledgeChunk>>() {}.type
    private val tasksType = object : TypeToken<List<KnowledgeIndexTask>>() {}.type

    @Volatile
    private var documents: List<KnowledgeDocument> = loadDocuments()

    @Volatile
    private var indexTasks: List<KnowledgeIndexTask> = loadTasks()

    fun documents(kind: KnowledgeOwnerKind, ownerId: String): List<KnowledgeDocument> =
        documents.filter { it.ownerKind == kind && it.ownerId == ownerId }.sortedByDescending { it.indexedAt }

    fun allDocuments(): List<KnowledgeDocument> = documents


    fun reloadFromDisk() {
        synchronized(this) {
            documents = loadDocuments()
            indexTasks = loadTasks()
        }
    }

    fun indexTask(taskId: String): KnowledgeIndexTask? =
        indexTasks.firstOrNull { it.id == taskId } ?: loadTasks().firstOrNull { it.id == taskId }

    fun activeIndexTask(kind: KnowledgeOwnerKind, ownerId: String): KnowledgeIndexTask? =
        loadTasks().firstOrNull {
            it.ownerKind == kind &&
                it.ownerId == ownerId &&
                it.status != KnowledgeIndexTaskStatus.FAILED
        }

    fun activeTaskLabels(): Map<String, String> = loadTasks()
        .filter { it.status != KnowledgeIndexTaskStatus.FAILED }
        .groupBy { "${it.ownerKind.name}::${it.ownerId}" }
        .mapValues { (_, values) ->
            val visible = values
                .filter { it.status == KnowledgeIndexTaskStatus.INDEXING || it.status == KnowledgeIndexTaskStatus.PREPARING }
                .maxByOrNull { it.updatedAt }
                ?: values.minByOrNull { it.createdAt }
            visible?.let(::taskLabel).orEmpty()
        }
        .filterValues { it.isNotBlank() }

    fun failedIndexTask(kind: KnowledgeOwnerKind, ownerId: String): KnowledgeIndexTask? = loadTasks()
        .filter {
            it.ownerKind == kind &&
                it.ownerId == ownerId &&
                it.status == KnowledgeIndexTaskStatus.FAILED
        }
        .maxByOrNull { it.updatedAt }

    fun failedTaskMessage(kind: KnowledgeOwnerKind, ownerId: String): String? =
        failedIndexTask(kind, ownerId)
            ?.let { task -> "Индексация «${task.name}» остановлена: ${task.error ?: "неизвестная ошибка"}" }

    suspend fun prepareIndexTask(
        kind: KnowledgeOwnerKind,
        ownerId: String,
        attachment: PendingAttachment,
        embeddingModelId: String,
        connectionProfileId: String,
        baseUrl: String
    ): KnowledgeIndexTask = withContext(Dispatchers.IO) {
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
            val task = KnowledgeIndexTask(
                id = id,
                ownerKind = kind,
                ownerId = ownerId,
                name = attachment.name,
                mimeType = attachment.mimeType,
                localPath = source.absolutePath,
                size = source.length(),
                embeddingModelId = embeddingModelId,
                connectionProfileId = connectionProfileId,
                baseUrl = baseUrl
            )
            upsertTask(task)
            task
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    suspend fun prepareReindexTask(
        documentId: String,
        embeddingModelId: String,
        connectionProfileId: String,
        baseUrl: String,
        allowQueuedForOwner: Boolean = false
    ): KnowledgeIndexTask = withContext(Dispatchers.IO) {
        val previous = (loadDocuments().firstOrNull { it.id == documentId }
            ?: documents.firstOrNull { it.id == documentId })
            ?: error("Документ базы знаний не найден")
        if (!allowQueuedForOwner) {
            require(activeIndexTask(previous.ownerKind, previous.ownerId) == null) {
                "Для этой базы знаний уже выполняется индексация"
            }
        }
        val oldSource = File(previous.localPath)
        require(oldSource.isFile) { "Исходный файл «${previous.name}» не найден" }
        val id = UUID.randomUUID().toString()
        val dir = documentDir(previous.ownerKind, previous.ownerId, id).apply { mkdirs() }
        try {
            val source = File(dir, "source${extensionFor(previous.name)}")
            oldSource.inputStream().buffered().use { input ->
                source.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            val task = KnowledgeIndexTask(
                id = id,
                ownerKind = previous.ownerKind,
                ownerId = previous.ownerId,
                name = previous.name,
                mimeType = previous.mimeType,
                localPath = source.absolutePath,
                size = source.length(),
                embeddingModelId = embeddingModelId,
                connectionProfileId = connectionProfileId,
                baseUrl = baseUrl,
                replaceDocumentId = previous.id
            )
            upsertTask(task)
            task
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    suspend fun resumeIndexTask(
        taskId: String,
        apiKey: String,
        embeddings: OpenRouterEmbeddingClient,
        onProgress: (KnowledgeIndexTask) -> Unit = {}
    ): KnowledgeDocument = withContext(Dispatchers.IO) {
        var task = indexTask(taskId) ?: error("Задача индексации не найдена")
        val source = File(task.localPath)
        require(source.isFile) { "Исходный файл «${task.name}» не найден" }
        val dir = source.parentFile ?: error("Не удалось открыть рабочую папку базы знаний")
        val chunksFile = File(dir, CHUNKS_FILE)
        val partialFile = File(dir, PARTIAL_VECTORS_FILE)

        var chunks = if (chunksFile.isFile) readChunks(chunksFile) else emptyList()
        if (chunks.isEmpty()) {
            task = updateTask(task.copy(
                status = KnowledgeIndexTaskStatus.PREPARING,
                error = null,
                updatedAt = System.currentTimeMillis()
            ))
            onProgress(task)
            val sections = KnowledgeTextExtractor.extract(context, source, task.name, task.mimeType)
            chunks = KnowledgeChunker.chunk(sections)
            require(chunks.isNotEmpty()) { "В документе не найден текст для индексации" }
            require(chunks.size <= MAX_CHUNKS_PER_DOCUMENT) {
                "Документ слишком велик: ${chunks.size} фрагментов. Максимум сейчас $MAX_CHUNKS_PER_DOCUMENT"
            }
            writeChunks(chunksFile, chunks)
        }

        val persisted = partialVectorState(partialFile)
        var done = persisted.first.coerceAtMost(chunks.size)
        var dimension = persisted.second
        task = updateTask(task.copy(
            totalChunks = chunks.size,
            completedChunks = done,
            vectorDimension = dimension,
            status = KnowledgeIndexTaskStatus.INDEXING,
            error = null,
            updatedAt = System.currentTimeMillis()
        ))
        onProgress(task)

        while (done < chunks.size) {
            val end = (done + EMBED_BATCH_SIZE).coerceAtMost(chunks.size)
            val batch = chunks.subList(done, end)
            val embedded = embeddings.embed(
                apiKey = apiKey,
                modelId = task.embeddingModelId,
                inputs = batch.map { it.text },
                inputType = "search_document",
                baseUrl = task.baseUrl
            )
            require(embedded.size == batch.size && embedded.all { it.isNotEmpty() }) {
                "Embedding-модель вернула неполный набор векторов"
            }
            val batchDimension = embedded.first().size
            require(embedded.all { it.size == batchDimension }) {
                "Embedding-модель вернула векторы разной размерности"
            }
            if (dimension != 0) require(dimension == batchDimension) {
                "Размерность embedding изменилась во время индексации"
            }
            dimension = if (dimension == 0) batchDimension else dimension
            appendPartialVectors(partialFile, embedded, dimension)
            done += embedded.size
            task = updateTask(task.copy(
                totalChunks = chunks.size,
                completedChunks = done,
                vectorDimension = dimension,
                status = KnowledgeIndexTaskStatus.INDEXING,
                error = null,
                updatedAt = System.currentTimeMillis()
            ))
            onProgress(task)
        }

        require(dimension > 0) { "Embedding-модель не создала индекс" }
        finalizePartialVectors(partialFile, File(dir, VECTORS_FILE), chunks.size, dimension)
        partialFile.delete()

        val document = KnowledgeDocument(
            id = task.id,
            ownerKind = task.ownerKind,
            ownerId = task.ownerId,
            name = task.name,
            mimeType = task.mimeType,
            localPath = source.absolutePath,
            size = source.length(),
            embeddingModelId = task.embeddingModelId,
            vectorDimension = dimension,
            chunkCount = chunks.size,
            charCount = chunks.sumOf { it.text.length },
            indexedAt = System.currentTimeMillis()
        )

        val replaced = task.replaceDocumentId
        val oldDocument = synchronized(DOCUMENT_LOCK) {
            val latest = loadDocuments()
            val old = replaced?.let { id -> latest.firstOrNull { it.id == id } }
            val next = latest.filterNot { it.id == replaced || it.id == document.id } + document
            saveDocuments(next)
            documents = next
            old
        }
        removeTask(task.id)
        if (oldDocument != null && oldDocument.id != document.id) {
            File(oldDocument.localPath).parentFile?.deleteRecursively()
        }
        document
    }

    fun markIndexTaskError(taskId: String, message: String, terminal: Boolean) {
        val task = indexTask(taskId) ?: return
        updateTask(task.copy(
            status = if (terminal) KnowledgeIndexTaskStatus.FAILED else KnowledgeIndexTaskStatus.QUEUED,
            error = message.take(700),
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun retryFailedTask(taskId: String): KnowledgeIndexTask? {
        val task = indexTask(taskId) ?: return null
        return updateTask(task.copy(
            status = KnowledgeIndexTaskStatus.QUEUED,
            error = null,
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun settings(kind: KnowledgeOwnerKind, ownerId: String): KnowledgeBaseSettings = runCatching {
        val currentKey = settingsKey(kind, ownerId)
        val raw = prefs.getString(currentKey, null) ?: legacySettingsKey(kind, ownerId)?.let { legacyKey ->
            prefs.getString(legacyKey, null)?.also { legacyValue ->
                prefs.edit().putString(currentKey, legacyValue).remove(legacyKey).apply()
            }
        }
        raw?.let { gson.fromJson(it, KnowledgeBaseSettings::class.java) }
    }.getOrNull()?.let(::sanitizeSettings) ?: KnowledgeBaseSettings()

    fun saveSettings(kind: KnowledgeOwnerKind, ownerId: String, value: KnowledgeBaseSettings) {
        val editor = prefs.edit().putString(settingsKey(kind, ownerId), gson.toJson(sanitizeSettings(value)))
        legacySettingsKey(kind, ownerId)?.let(editor::remove)
        editor.apply()
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
            synchronized(DOCUMENT_LOCK) {
                val latest = loadDocuments()
                documents = latest + document
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
        synchronized(DOCUMENT_LOCK) {
            val latest = loadDocuments()
            val next = latest.filterNot { it.id == documentId }
            saveDocuments(next)
            documents = next
        }
        File(document.localPath).parentFile?.deleteRecursively()
        return true
    }

    fun deleteOwner(kind: KnowledgeOwnerKind, ownerId: String) {
        val owned = documents(kind, ownerId)
        if (owned.isNotEmpty()) {
            synchronized(DOCUMENT_LOCK) {
                val ids = owned.map { it.id }.toSet()
                val latest = loadDocuments()
                val next = latest.filterNot { it.id in ids }
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
        embeddings: OpenRouterEmbeddingClient,
        embeddingModelId: String? = null
    ): List<KnowledgeHit> = retrieveDetailed(
        owners = owners,
        query = query,
        apiKey = apiKey,
        baseUrl = baseUrl,
        embeddings = embeddings,
        embeddingModelId = embeddingModelId
    ).hits

    internal suspend fun retrieveDetailed(
        owners: List<Pair<KnowledgeOwnerKind, String>>,
        query: String,
        apiKey: String,
        baseUrl: String,
        embeddings: OpenRouterEmbeddingClient,
        embeddingModelId: String? = null
    ): KnowledgeRetrievalResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext KnowledgeRetrievalResult(emptyList(), 0, 0)
        }

        val queryVectors = mutableMapOf<String, FloatArray>()
        val result = mutableListOf<KnowledgeHit>()
        var candidateCount = 0
        var thresholdDropped = 0

        owners.distinct().forEach { (kind, ownerId) ->
            val settings = settings(kind, ownerId)
            if (!settings.enabled) return@forEach
            val expectedModel = embeddingModelId?.trim().orEmpty()
            val ownerDocuments = documents(kind, ownerId)
                .filter { documentFilesValid(it) }
                .filter { expectedModel.isBlank() || it.embeddingModelId == expectedModel }
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
                    ownerHits += scoreDocument(document, queryVector, cleanQuery)
                }
            }

            candidateCount += ownerHits.size
            val relevantHits = ownerHits.filter { hit ->
                KnowledgeHybridRanker.passesRelevanceGate(
                    semanticScore = hit.semanticScore ?: 0.0,
                    lexicalScore = hit.lexicalScore ?: 0.0
                )
            }
            thresholdDropped += ownerHits.size - relevantHits.size

            result += selectDiverseHits(
                relevantHits.sortedByDescending { it.score },
                settings.topK
            )
        }

        val dedupedHits = result.sortedByDescending { it.score }
            .distinctBy { "${it.documentId}:${it.text.hashCode()}" }
        // Final invariant before anything can reach the main model. This intentionally
        // repeats the minimum semantic floor so a purely lexical coincidence can never
        // survive selection/diversification even if ranking rules change later.
        val finalRelevantHits = dedupedHits.filter { hit ->
            val semantic = hit.semanticScore ?: 0.0
            val lexical = hit.lexicalScore ?: 0.0
            semantic >= 0.45 || (semantic >= 0.34 && lexical >= 2.0)
        }
        thresholdDropped += dedupedHits.size - finalRelevantHits.size
        val finalHits = finalRelevantHits.take(MAX_TOTAL_HITS)

        KnowledgeRetrievalResult(
            hits = finalHits,
            candidateCount = candidateCount,
            thresholdDropped = thresholdDropped
        )
    }

    private fun scoreDocument(document: KnowledgeDocument, query: FloatArray, rawQuery: String): List<KnowledgeHit> {
        if (query.size != document.vectorDimension) return emptyList()
        val dir = File(document.localPath).parentFile ?: return emptyList()
        val chunks = readChunks(File(dir, CHUNKS_FILE))
        val vectorsFile = File(dir, VECTORS_FILE)
        if (!vectorsFile.isFile || chunks.isEmpty()) return emptyList()

        val queryNorm = sqrt(query.fold(0.0) { acc, value -> acc + value * value })
        if (queryNorm == 0.0) return emptyList()
        val semanticScores = DoubleArray(chunks.size)
        DataInputStream(FileInputStream(vectorsFile).buffered()).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            val count = input.readInt()
            val dimension = input.readInt()
            if (magic != VECTOR_MAGIC || version != VECTOR_VERSION || dimension != query.size || count != chunks.size) {
                return emptyList()
            }
            repeat(count) { index ->
                var dot = 0.0
                var norm = 0.0
                repeat(dimension) { d ->
                    val value = input.readFloat()
                    dot += query[d] * value
                    norm += value * value
                }
                semanticScores[index] = if (norm <= 0.0) 0.0 else dot / (queryNorm * sqrt(norm))
            }
        }

        return KnowledgeHybridRanker.rank(rawQuery, chunks, semanticScores)
            .mapNotNull { ranked ->
                val chunk = chunks[ranked.index]
                if (KnowledgeChunker.isLikelyEncodedBlob(chunk.text)) {
                    null
                } else {
                    KnowledgeHit(
                        documentId = document.id,
                        documentName = document.name,
                        text = chunk.text,
                        page = chunk.page,
                        score = ranked.score,
                        ordinal = chunk.ordinal,
                        semanticScore = ranked.semanticScore,
                        lexicalScore = ranked.lexicalScore
                    )
                }
            }
    }

    private fun selectDiverseHits(hits: List<KnowledgeHit>, limit: Int): List<KnowledgeHit> {
        if (limit <= 0 || hits.isEmpty()) return emptyList()
        val selected = mutableListOf<KnowledgeHit>()
        val diverseTarget = minOf(limit, 3)

        hits.forEach { hit ->
            if (selected.size >= diverseTarget) return@forEach
            val tooClose = selected.any { chosen ->
                chosen.documentId == hit.documentId &&
                    chosen.ordinal >= 0 &&
                    hit.ordinal >= 0 &&
                    kotlin.math.abs(chosen.ordinal - hit.ordinal) <= 1
            }
            if (!tooClose) selected += hit
        }

        hits.forEach { hit ->
            if (selected.size >= limit) return@forEach
            if (selected.none { chosen ->
                    chosen.documentId == hit.documentId &&
                        chosen.ordinal == hit.ordinal &&
                        chosen.text == hit.text
                }
            ) {
                selected += hit
            }
        }
        return selected
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


    private fun taskLabel(task: KnowledgeIndexTask): String {
        val total = task.totalChunks
        val done = task.completedChunks.coerceAtLeast(0)
        val percent = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
        return when (task.status) {
            KnowledgeIndexTaskStatus.QUEUED -> "В очереди: ${task.name}"
            KnowledgeIndexTaskStatus.PREPARING -> "Подготавливаю ${task.name}…"
            KnowledgeIndexTaskStatus.INDEXING ->
                if (total > 0) "Индексирую ${task.name}: $percent% ($done/$total)"
                else "Индексирую ${task.name}…"
            KnowledgeIndexTaskStatus.FAILED -> "Ошибка индексации: ${task.name}"
        }
    }

    private fun loadTasks(): List<KnowledgeIndexTask> = runCatching {
        taskManifest.read { raw -> runCatching { gson.fromJson<List<KnowledgeIndexTask>>(raw, tasksType) }.isSuccess }
            ?.let { gson.fromJson<List<KnowledgeIndexTask>>(it, tasksType) }
            .orEmpty()
            .map(::migrateLegacyTaskPath)
    }.getOrDefault(emptyList()).also { indexTasks = it }

    private fun saveTasks(value: List<KnowledgeIndexTask>) {
        taskManifest.write(gson.toJson(value)) { raw ->
            runCatching { gson.fromJson<List<KnowledgeIndexTask>>(raw, tasksType) }.isSuccess
        }
    }

    private fun upsertTask(task: KnowledgeIndexTask): KnowledgeIndexTask = synchronized(TASK_LOCK) {
        val latest = loadTasks()
        val next = latest.filterNot { it.id == task.id } + task
        saveTasks(next)
        indexTasks = next
        task
    }

    private fun updateTask(task: KnowledgeIndexTask): KnowledgeIndexTask = upsertTask(task)

    private fun removeTask(taskId: String) {
        synchronized(TASK_LOCK) {
            val latest = loadTasks()
            val next = latest.filterNot { it.id == taskId }
            saveTasks(next)
            indexTasks = next
        }
    }

    private fun partialVectorState(file: File): Pair<Int, Int> {
        if (!file.isFile || file.length() == 0L) return 0 to 0
        if (file.length() < PARTIAL_VECTOR_HEADER_BYTES) {
            file.delete()
            return 0 to 0
        }
        val dimension = runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                val savedDimension = input.readInt()
                require(magic == PARTIAL_VECTOR_MAGIC && version == PARTIAL_VECTOR_VERSION && savedDimension > 0)
                savedDimension
            }
        }.getOrElse {
            file.delete()
            return 0 to 0
        }

        val payload = file.length() - PARTIAL_VECTOR_HEADER_BYTES
        val vectorBytes = dimension.toLong() * 4L
        val completePayload = (payload / vectorBytes) * vectorBytes
        if (completePayload != payload) {
            // A process kill can interrupt the final vector write. Keep every complete
            // vector and discard only the incomplete tail instead of restarting the book.
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(PARTIAL_VECTOR_HEADER_BYTES + completePayload)
                raf.fd.sync()
            }
        }
        return (completePayload / vectorBytes).toInt() to dimension
    }

    private fun appendPartialVectors(file: File, vectors: List<FloatArray>, dimension: Int) {
        require(vectors.all { it.size == dimension }) { "Некорректная размерность embedding" }
        if (!file.exists()) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0L)
                raf.writeInt(PARTIAL_VECTOR_MAGIC)
                raf.writeInt(PARTIAL_VECTOR_VERSION)
                raf.writeInt(dimension)
                raf.fd.sync()
            }
        } else {
            val (_, savedDimension) = partialVectorState(file)
            require(savedDimension == dimension) { "Размерность checkpoint embeddings не совпадает" }
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            vectors.forEach { vector -> vector.forEach(raf::writeFloat) }
            // A checkpoint is considered committed only after the whole batch reaches storage.
            raf.fd.sync()
        }
    }

    private fun finalizePartialVectors(partial: File, target: File, count: Int, dimension: Int) {
        val (savedCount, savedDimension) = partialVectorState(partial)
        require(savedCount == count && savedDimension == dimension) {
            "Checkpoint embeddings не соответствует документу"
        }
        val temp = File(target.parentFile, "${target.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
            output.writeInt(VECTOR_MAGIC)
            output.writeInt(VECTOR_VERSION)
            output.writeInt(count)
            output.writeInt(dimension)
            FileInputStream(partial).buffered().use { input ->
                var remaining = PARTIAL_VECTOR_HEADER_BYTES
                while (remaining > 0L) {
                    val skipped = input.skip(remaining)
                    require(skipped > 0L) { "Не удалось прочитать checkpoint embeddings" }
                    remaining -= skipped
                }
                input.copyTo(output)
            }
        }
        if (target.exists() && !target.delete()) error("Не удалось заменить индекс embeddings")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

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
            .map(::migrateLegacyDocumentPath)
    }.getOrDefault(emptyList())

    private fun saveDocuments(value: List<KnowledgeDocument>) {
        manifest.write(gson.toJson(value)) { raw ->
            runCatching { gson.fromJson<List<KnowledgeDocument>>(raw, documentsType) }.isSuccess
        }
    }

    private fun sanitizeSettings(value: KnowledgeBaseSettings): KnowledgeBaseSettings = value.copy(
        topK = value.topK.coerceIn(2, 4),
        modelInstruction = value.modelInstruction.orEmpty().trim().take(4000)
    )

    private fun settingsKey(kind: KnowledgeOwnerKind, ownerId: String): String =
        "settings::${kind.name.lowercase()}::$ownerId"

    private fun legacySettingsKey(kind: KnowledgeOwnerKind, ownerId: String): String? = when (kind) {
        KnowledgeOwnerKind.TEAM -> "settings::project::$ownerId"
        KnowledgeOwnerKind.SPECIALIST -> "settings::agent::$ownerId"
        else -> null
    }

    private fun migrateLegacyDocumentPath(document: KnowledgeDocument): KnowledgeDocument {
        if (document.ownerKind != KnowledgeOwnerKind.SPECIALIST) return document
        val legacyPrefix = File(context.filesDir, "agents").absolutePath + File.separator
        if (!document.localPath.startsWith(legacyPrefix)) return document
        val relative = document.localPath.removePrefix(legacyPrefix)
        return document.copy(localPath = File(specialistsRoot, relative).absolutePath)
    }

    private fun migrateLegacyTaskPath(task: KnowledgeIndexTask): KnowledgeIndexTask {
        if (task.ownerKind != KnowledgeOwnerKind.SPECIALIST) return task
        val legacyPrefix = File(context.filesDir, "agents").absolutePath + File.separator
        if (!task.localPath.startsWith(legacyPrefix)) return task
        val relative = task.localPath.removePrefix(legacyPrefix)
        return task.copy(localPath = File(specialistsRoot, relative).absolutePath)
    }

    private fun documentDir(kind: KnowledgeOwnerKind, ownerId: String, documentId: String): File =
        if (kind == KnowledgeOwnerKind.SPECIALIST) {
            File(specialistsRoot, "${safe(ownerId)}/knowledge/${safe(documentId)}")
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
        private val TASK_LOCK = Any()
        private val DOCUMENT_LOCK = Any()
        private const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L
        private const val MAX_CHUNKS_PER_DOCUMENT = 6000
        private const val EMBED_BATCH_SIZE = 24
        private const val MAX_TOTAL_HITS = 10
        private const val CHUNKS_FILE = "chunks.json"
        private const val VECTORS_FILE = "vectors.bin"
        private const val PARTIAL_VECTORS_FILE = "vectors.partial"
        private const val VECTOR_MAGIC = 0x554D4B42
        private const val VECTOR_VERSION = 1
        private const val PARTIAL_VECTOR_MAGIC = 0x554D4B50
        private const val PARTIAL_VECTOR_VERSION = 1
        private const val PARTIAL_VECTOR_HEADER_BYTES = 12L
    }
}
