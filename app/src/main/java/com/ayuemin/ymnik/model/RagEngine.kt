package com.ayuemin.ymnik.model

import kotlin.math.sqrt

data class RagChunk(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val text: String,
    val embedding: List<Float> = emptyList(),
    val order: Int = 0
)

data class RagMatch(
    val chunk: RagChunk,
    val score: Double
)

object RagEngine {
    /**
     * Paragraph-aware chunking with a small overlap. Character limits are used
     * deliberately: the embedding model does the final token accounting and
     * this keeps the Android side dependency-free.
     */
    fun chunkText(
        sourceId: String,
        sourceName: String,
        text: String,
        targetChars: Int = 2_400,
        overlapChars: Int = 320
    ): List<RagChunk> {
        if (text.isBlank()) return emptyList()
        val target = targetChars.coerceAtLeast(400)
        val overlap = overlapChars.coerceIn(0, target / 2)
        val normalized = text.replace("\r\n", "\n").trim()
        val paragraphs = normalized.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return emptyList()

        val raw = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val value = current.toString().trim()
            if (value.isNotBlank()) raw += value
            current.clear()
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > target) {
                if (current.isNotEmpty()) flush()
                var start = 0
                while (start < paragraph.length) {
                    val end = (start + target).coerceAtMost(paragraph.length)
                    raw += paragraph.substring(start, end).trim()
                    if (end == paragraph.length) break
                    start = (end - overlap).coerceAtLeast(start + 1)
                }
            } else if (current.isEmpty() || current.length + 2 + paragraph.length <= target) {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            } else {
                val previousTail = current.takeLast(overlap)
                flush()
                if (previousTail.isNotBlank()) {
                    current.append(previousTail.trim()).append("\n\n")
                }
                current.append(paragraph)
            }
        }
        if (current.isNotEmpty()) flush()

        return raw.filter { it.isNotBlank() }.mapIndexed { index, chunk ->
            RagChunk(
                id = "$sourceId:$index",
                sourceId = sourceId,
                sourceName = sourceName,
                text = chunk,
                order = index
            )
        }
    }

    fun retrieve(
        queryEmbedding: List<Float>,
        chunks: List<RagChunk>,
        topK: Int = 20
    ): List<RagMatch> {
        if (queryEmbedding.isEmpty() || chunks.isEmpty() || topK <= 0) return emptyList()
        return chunks.asSequence()
            .filter { it.embedding.size == queryEmbedding.size && it.embedding.isNotEmpty() }
            .map { chunk -> RagMatch(chunk, cosineSimilarity(queryEmbedding, chunk.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    fun cosineSimilarity(first: List<Float>, second: List<Float>): Double {
        if (first.isEmpty() || first.size != second.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in first.indices) {
            val a = first[index].toDouble()
            val b = second[index].toDouble()
            dot += a * b
            normA += a * a
            normB += b * b
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
