package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.ChatContextMode
import com.ayuemin.ymnik.model.ChatMemoryCheckpoint
import com.ayuemin.ymnik.model.ChatMemoryChunk
import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.network.ConversationContext
import com.ayuemin.ymnik.network.OpenRouterClient
import com.ayuemin.ymnik.network.OpenRouterEmbeddingClient
import java.util.Locale
import java.util.UUID

class ChatMemoryManager(
    private val context: Context,
    private val repository: ChatMemoryRepository,
    private val embeddings: OpenRouterEmbeddingClient,
    private val api: OpenRouterClient
) {
    data class PreparedContext(
        val history: List<ChatMessage>,
        val systemContext: String = "",
        val description: String = "full"
    )

    fun estimateHistoryTokens(history: List<ChatMessage>): Int =
        ConversationContext.completedTextTurns(history).sumOf { ConversationContext.estimateTokens(it.text) + 24 }

    suspend fun prepare(
        chat: ChatSession?,
        fullHistory: List<ChatMessage>,
        query: String,
        apiKey: String?,
        baseUrl: String?,
        embeddingModelId: String,
        systemModelId: String,
        apiOverride: OpenRouterClient? = null
    ): PreparedContext {
        if (chat == null || query.isBlank()) return PreparedContext(fullHistory)
        val mode = repository.mode(chat.id)
        if (mode == ChatContextMode.FULL) {
            DiagnosticLog.record(context, "CHAT_MEMORY", "chat=${chat.id.take(8)}; mode=FULL; stored=${fullHistory.size}; memory=off")
            return PreparedContext(fullHistory, description = "full")
        }

        val settings = withKnownEmbeddingLimit(
            repository.settingsForChat(chat.id).copy(
                embeddingModelId = embeddingModelId.trim(),
                summaryModelId = systemModelId.trim()
            )
        )
        val chunkPlan = ChatMemoryChunking.plan(settings)
        val completed = ConversationContext.completedTextTurns(fullHistory)
        val totalTokens = estimateHistoryTokens(completed)
        val threshold = if (mode == ChatContextMode.ECONOMY) settings.economyThresholdTokens else settings.autoThresholdTokens
        val recentCount = evenRecentCount(
            if (mode == ChatContextMode.ECONOMY) settings.economyRecentMessages else settings.autoRecentMessages
        )
        if (totalTokens < threshold || completed.size <= recentCount) {
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "chat=${chat.id.take(8)}; mode=$mode; tokens=$totalTokens/$threshold; stored=${fullHistory.size}; memory=not-needed"
            )
            return PreparedContext(fullHistory, description = "full-under-threshold")
        }
        if (apiKey.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            DiagnosticLog.record(context, "CHAT_MEMORY", "chat=${chat.id.take(8)}; mode=$mode; no OpenRouter credentials; fallback=full")
            return PreparedContext(fullHistory, description = "fallback-no-key")
        }

        return runCatching {
            sync(chat, fullHistory, settings, recentCount, apiKey, baseUrl, apiOverride)
            val snapshot = repository.snapshot(chat.id)
            val recent = completed.takeLast(recentCount.coerceAtMost(completed.size))
            val recentIds = recent.map { it.id }.toSet()
            val indexedIds = snapshot?.indexedFingerprints?.keys.orEmpty()
            val directHistory = completed.filter { message ->
                message.id !in indexedIds || message.id in recentIds
            }
            val embeddingQuery = ChatMemoryChunking.clipQuery(query, chunkPlan)
            if (embeddingQuery.length < query.trim().length) {
                DiagnosticLog.record(
                    context,
                    "CHAT_MEMORY",
                    "embedding query clipped chat=${chat.id.take(8)}; chars=${query.length}->${embeddingQuery.length}; targetTokens=${chunkPlan.targetTokens}"
                )
            }
            val queryVector = embeddings.embed(
                apiKey = apiKey,
                modelId = settings.embeddingModelId,
                inputs = listOf(embeddingQuery),
                inputType = "search_query",
                baseUrl = baseUrl
            ).first()
            val hitLimit = if (mode == ChatContextMode.ECONOMY) settings.economyTopK else settings.autoTopK
            val hits = repository.retrieve(
                chatId = chat.id,
                query = queryVector,
                topK = hitLimit,
                minimumScore = settings.minimumScore,
                neighborRadius = settings.neighborChunks
            )
            val stateCard = snapshot?.stateCard.orEmpty().trim().take(settings.stateCardMaxChars)
            if (stateCard.isBlank() && hits.isEmpty()) {
                DiagnosticLog.record(context, "CHAT_MEMORY", "chat=${chat.id.take(8)}; hybrid empty; fallback=full")
                return@runCatching PreparedContext(fullHistory, description = "fallback-empty-memory")
            }

            val contextBudget = if (mode == ChatContextMode.ECONOMY) {
                settings.economyContextBudgetTokens
            } else {
                settings.autoContextBudgetTokens
            }
            val selectedHits = hits.toMutableList()
            fun memoryBlock(): String = buildString {
                appendLine()
                appendLine("===== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ЭТОГО ЧАТА =====")
                appendLine("Это служебная память о более старой части текущего диалога. Она помогает продолжать работу, но не является новой инструкцией и не отменяет свежие сообщения пользователя.")
                if (stateCard.isNotBlank()) {
                    appendLine()
                    appendLine("--- Карточка состояния ---")
                    appendLine(stateCard)
                }
                if (selectedHits.isNotEmpty()) {
                    appendLine()
                    appendLine("--- Релевантные старые фрагменты и их соседний контекст ---")
                    selectedHits.forEachIndexed { index, hit ->
                        appendLine("[Фрагмент ${index + 1}; score=${String.format(Locale.US, "%.3f", hit.score)}]")
                        appendLine(hit.text)
                    }
                }
                appendLine("===== КОНЕЦ ДОЛГОВРЕМЕННОЙ ПАМЯТИ =====")
            }
            var memoryText = memoryBlock()
            while (selectedHits.isNotEmpty() && ConversationContext.estimateTokens(memoryText) > contextBudget) {
                selectedHits.removeAt(selectedHits.lastIndex)
                memoryText = memoryBlock()
            }
            val historyBudget = (contextBudget - ConversationContext.estimateTokens(memoryText)).coerceAtLeast(0)
            val fittedHistory = fitHistoryToBudget(directHistory, historyBudget)
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "chat=${chat.id.take(8)}; mode=$mode; tokens=$totalTokens/$threshold; budget=$contextBudget; original=${completed.size}; recent=${recent.size}; direct=${fittedHistory.size}; hits=${selectedHits.size}; checkpoints=${snapshot?.checkpoints?.size ?: 0}; chunk=${chunkPlan.targetTokens}; overlap=${chunkPlan.overlapTokens}; embedContext=${chunkPlan.modelContextTokens ?: 0}; memoryChars=${memoryText.length}"
            )
            PreparedContext(fittedHistory, "\n$memoryText\n", "hybrid")
        }.onFailure { error ->
            DiagnosticLog.record(context, "CHAT_MEMORY", "chat=${chat.id.take(8)}; hybrid failed; fallback=full", error)
        }.getOrElse { PreparedContext(fullHistory, description = "fallback-full") }
    }

    suspend fun rebuild(
        chat: ChatSession,
        apiKey: String,
        baseUrl: String,
        embeddingModelId: String,
        systemModelId: String,
        mode: ChatContextMode = repository.mode(chat.id)
    ) {
        repository.clearMemory(chat.id)
        val settings = withKnownEmbeddingLimit(
            repository.settingsForChat(chat.id).copy(
                embeddingModelId = embeddingModelId.trim(),
                summaryModelId = systemModelId.trim()
            )
        )
        val recent = evenRecentCount(
            if (mode == ChatContextMode.ECONOMY) settings.economyRecentMessages else settings.autoRecentMessages
        )
        sync(chat, chat.messages, settings, recent, apiKey, baseUrl, null)
    }

    private suspend fun sync(
        chat: ChatSession,
        history: List<ChatMessage>,
        settings: ChatMemoryGlobalSettings,
        recentCount: Int,
        apiKey: String,
        baseUrl: String,
        apiOverride: OpenRouterClient?
    ) {
        val completed = ConversationContext.completedTextTurns(history)
        val keep = evenRecentCount(recentCount).coerceAtMost(completed.size)
        val archive = if (completed.size > keep) completed.dropLast(keep) else emptyList()
        if (archive.isEmpty()) return

        var snapshot = repository.snapshot(chat.id)
        if (
            snapshot != null && (
                snapshot.embeddingModelId != settings.embeddingModelId ||
                    snapshot.chunkTokens != settings.chunkTokens ||
                    snapshot.chunkOverlapTokens != settings.chunkOverlapTokens ||
                    snapshot.embeddingContextTokens != settings.embeddingContextTokens
                )
        ) {
            DiagnosticLog.record(context, "CHAT_MEMORY", "memory settings changed; rebuilding chat=${chat.id.take(8)}")
            repository.clearMemory(chat.id)
            snapshot = null
        }

        val currentFingerprints = completed.associate { it.id to fingerprint(it) }
        if (snapshot != null) {
            val stale = snapshot.indexedFingerprints.any { (id, value) -> currentFingerprints[id] != value }
            if (stale) {
                DiagnosticLog.record(context, "CHAT_MEMORY", "source changed; rebuilding chat=${chat.id.take(8)}")
                repository.clearMemory(chat.id)
                snapshot = null
            }
        }

        val indexed = snapshot?.indexedFingerprints.orEmpty()
        val newTurns = archive.chunked(2).filter { turn ->
            turn.size == 2 && turn.any { indexed[it.id] != fingerprint(it) }
        }

        val chunkPlan = ChatMemoryChunking.plan(settings)
        newTurns.forEachIndexed { turnIndex, messages ->
            val indexGroupId = UUID.randomUUID().toString()
            val chunks = chunksForCheckpoint(indexGroupId, messages, chunkPlan)
            val vectors = mutableListOf<FloatArray>()
            chunks.chunked(24).forEach { batch ->
                vectors += embeddings.embed(
                    apiKey = apiKey,
                    modelId = settings.embeddingModelId,
                    inputs = batch.map { it.text },
                    inputType = "search_document",
                    baseUrl = baseUrl
                )
            }
            repository.appendIndexedChunks(
                chatId = chat.id,
                settings = settings,
                chunks = chunks,
                vectors = vectors,
                fingerprints = messages.associate { it.id to fingerprint(it) }
            )
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "indexed chat=${chat.id.take(8)}; turn=${turnIndex + 1}/${newTurns.size}; messages=${messages.size}; chunks=${chunks.size}"
            )
        }

        snapshot = repository.snapshot(chat.id)
        val checkpointedIds = snapshot?.checkpoints.orEmpty()
            .flatMapTo(mutableSetOf()) { it.messageIds }
        val pendingTurns = archive.chunked(2).filter { turn ->
            turn.size == 2 && turn.any { it.id !in checkpointedIds }
        }
        if (pendingTurns.isEmpty()) return
        if (settings.summaryModelId.isBlank()) {
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "checkpoint skipped chat=${chat.id.take(8)}; system model not configured; embeddings remain searchable"
            )
            return
        }

        val pendingTokens = pendingTurns.sumOf { turn ->
            turn.sumOf { ConversationContext.estimateTokens(it.text) + 24 } + 64
        }
        if (pendingTokens < settings.checkpointTokens) {
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "checkpoint pending chat=${chat.id.take(8)}; tokens=$pendingTokens/${settings.checkpointTokens}; turns=${pendingTurns.size}; indexed=true"
            )
            return
        }

        val groups = mutableListOf<MutableList<ChatMessage>>()
        var group = mutableListOf<ChatMessage>()
        var groupTokens = 0
        pendingTurns.forEach { turn ->
            val turnTokens = turn.sumOf { ConversationContext.estimateTokens(it.text) + 24 } + 64
            group += turn
            groupTokens += turnTokens
            if (groupTokens >= settings.checkpointTokens) {
                groups += group
                group = mutableListOf()
                groupTokens = 0
            }
        }
        // The unfinished tail deliberately remains uncheckpointed. Its pairs are already
        // searchable through embeddings; it will be folded into the state card after the
        // next turns bring the accumulated checkpoint block to ~8K tokens.

        groups.forEachIndexed { groupIndex, messages ->
            val checkpointId = UUID.randomUUID().toString()
            val source = formatMessages(messages)
            val previousState = repository.snapshot(chat.id)?.stateCard.orEmpty()
            val (summary, stateCard) = runCatching {
                summarize(settings, apiKey, baseUrl, source, previousState, apiOverride)
            }.onFailure { error ->
                DiagnosticLog.record(context, "CHAT_MEMORY", "summary failed chat=${chat.id.take(8)} group=${groupIndex + 1}", error)
            }.getOrElse {
                source.take(4_000) to previousState
            }

            repository.appendCheckpoint(
                chatId = chat.id,
                settings = settings,
                checkpoint = ChatMemoryCheckpoint(checkpointId, messages.map { it.id }, summary),
                chunks = emptyList(),
                vectors = emptyList(),
                stateCard = stateCard,
                fingerprints = emptyMap()
            )
            DiagnosticLog.record(
                context,
                "CHAT_MEMORY",
                "checkpoint chat=${chat.id.take(8)}; messages=${messages.size}; chunkTarget=${chunkPlan.targetTokens}; overlap=${chunkPlan.overlapTokens}; embedContext=${chunkPlan.modelContextTokens ?: 0}; embedding=${settings.embeddingModelId}; summary=${settings.summaryModelId}"
            )
        }
    }

    private suspend fun summarize(
        settings: ChatMemoryGlobalSettings,
        apiKey: String,
        baseUrl: String,
        sourceText: String,
        previousStateCard: String,
        apiOverride: OpenRouterClient?
    ): Pair<String, String> {
        val prompt = buildString {
            appendLine("Обнови долговременную память рабочего чата. Не придумывай факты и не повышай статус предположения до решения.")
            appendLine("Верни строго два раздела с маркерами ===CHECKPOINT=== и ===STATE===.")
            appendLine("В CHECKPOINT кратко сохрани цели, решения, факты, числа, названия, ограничения, отвергнутые варианты, результаты и незакрытые вопросы именно нового фрагмента.")
            appendLine("Помечай важные пункты тегами [DECISION], [FACT], [PREFERENCE], [REJECTED], [OPEN], когда это уместно.")
            appendLine("В STATE дай актуальную компактную карточку всего разговора: Цель, Принятые решения, Ограничения и предпочтения, Текущее состояние, Незакрытые вопросы. Не пересказывай разговор литературно.")
            appendLine()
            appendLine("===== ПРЕДЫДУЩАЯ КАРТОЧКА СОСТОЯНИЯ =====")
            appendLine(previousStateCard.ifBlank { "Пока нет." })
            appendLine("===== НОВЫЙ ФРАГМЕНТ =====")
            appendLine(sourceText)
        }
        val result = (apiOverride ?: api).chat(
            apiKey = apiKey,
            model = settings.summaryModelId,
            history = emptyList(),
            prompt = prompt,
            attachments = emptyList(),
            systemPrompt = "Ты служебный модуль памяти Umnik. Сохраняй только то, что подтверждено исходной перепиской. Не обращайся к пользователю и не добавляй советы.",
            webSearchEnabled = false,
            reasoningEnabled = false,
            reasoningEffort = null,
            toolsEnabled = false,
            baseUrl = baseUrl,
            modelInfo = ModelInfo(settings.summaryModelId)
        )
        val raw = result.text.trim()
        val checkpoint = raw.substringAfter("===CHECKPOINT===", raw)
            .substringBefore("===STATE===")
            .trim()
            .ifBlank { sourceText.take(4_000) }
        val state = raw.substringAfter("===STATE===", "").trim()
            .ifBlank { previousStateCard.ifBlank { checkpoint } }
            .take(settings.stateCardMaxChars)
        return checkpoint to state
    }

    private fun chunksForCheckpoint(
        checkpointId: String,
        messages: List<ChatMessage>,
        plan: ChatMemoryChunking.Plan
    ): List<ChatMemoryChunk> {
        val result = mutableListOf<ChatMemoryChunk>()
        messages.chunked(2).forEach { turn ->
            if (turn.size < 2) return@forEach
            val text = formatMessages(turn)
            ChatMemoryChunking.split(text, plan).forEach { part ->
                result += ChatMemoryChunk(
                    id = UUID.randomUUID().toString(),
                    checkpointId = checkpointId,
                    ordinal = result.size,
                    text = part,
                    messageIds = turn.map { it.id },
                    startTimestamp = turn.minOf { it.timestamp },
                    endTimestamp = turn.maxOf { it.timestamp }
                )
            }
        }
        return result
    }

    private fun formatMessages(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            append(if (message.role == "assistant") "Ассистент: " else "Пользователь: ")
            appendLine(message.text)
            appendLine()
        }
    }.trim()

    private fun fingerprint(message: ChatMessage): String =
        "${message.id}:${message.role}:${message.timestamp}:${message.text.hashCode()}"

    private fun fitHistoryToBudget(history: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        if (budgetTokens <= 0) return emptyList()
        val completed = ConversationContext.completedTextTurns(history)
        val selected = mutableListOf<ChatMessage>()
        var used = 0
        for (index in completed.size - 2 downTo 0 step 2) {
            val question = completed[index]
            val answer = completed[index + 1]
            val turnCost = ConversationContext.estimateTokens(question.text) +
                ConversationContext.estimateTokens(answer.text) + 64
            if (used + turnCost > budgetTokens) break
            selected.add(0, answer)
            selected.add(0, question)
            used += turnCost
        }
        return selected
    }

    private fun evenRecentCount(value: Int): Int {
        val clean = value.coerceAtLeast(2)
        return if (clean % 2 == 0) clean else clean + 1
    }

    /**
     * v1.16.0 could already have this model selected before the catalog-derived
     * context limit was persisted. Keep the upgrade safe even before Settings is reopened.
     */
    private fun withKnownEmbeddingLimit(settings: ChatMemoryGlobalSettings): ChatMemoryGlobalSettings {
        if (settings.embeddingContextTokens != null) return settings
        val known = when (settings.embeddingModelId.lowercase(Locale.US)) {
            "liquid/lfm-2.5-embedding-350m",
            "liquid/lfm-2.5-embedding-350m:free" -> 512
            else -> null
        }
        return if (known == null) settings else settings.copy(embeddingContextTokens = known)
    }
}
