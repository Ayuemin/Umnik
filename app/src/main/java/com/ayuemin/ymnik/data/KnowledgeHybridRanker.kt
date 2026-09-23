package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.KnowledgeChunk
import java.util.Locale
import kotlin.math.ln

internal data class KnowledgeRankedChunk(
    val index: Int,
    val score: Double,
    val semanticScore: Double,
    val lexicalScore: Double
)

internal object KnowledgeHybridRanker {
    fun rank(
        query: String,
        chunks: List<KnowledgeChunk>,
        semanticScores: DoubleArray
    ): List<KnowledgeRankedChunk> {
        if (chunks.isEmpty() || semanticScores.size != chunks.size) return emptyList()

        val lexicalScores = bm25Scores(query, chunks)
        val semanticOrder = semanticScores.indices.sortedByDescending { semanticScores[it] }
        val semanticRank = IntArray(chunks.size)
        semanticOrder.forEachIndexed { rank, index -> semanticRank[index] = rank + 1 }

        val lexicalOrder = lexicalScores.indices
            .filter { lexicalScores[it] > 0.0 }
            .sortedByDescending { lexicalScores[it] }
        val lexicalRank = IntArray(chunks.size)
        lexicalOrder.forEachIndexed { rank, index -> lexicalRank[index] = rank + 1 }

        return chunks.indices.map { index ->
            val semanticRrf = 1.0 / (RRF_K + semanticRank[index])
            val lexicalRrf = lexicalRank[index]
                .takeIf { it > 0 }
                ?.let { 1.0 / (RRF_K + it) }
                ?: 0.0
            KnowledgeRankedChunk(
                index = index,
                score = semanticRrf + lexicalRrf,
                semanticScore = semanticScores[index],
                lexicalScore = lexicalScores[index]
            )
        }.sortedByDescending { it.score }
    }

    private fun bm25Scores(query: String, chunks: List<KnowledgeChunk>): DoubleArray {
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) return DoubleArray(chunks.size)

        val tokenSets = ArrayList<List<String>>(chunks.size)
        val documentFrequency = queryTokens.associateWith { 0 }.toMutableMap()
        var totalLength = 0

        chunks.forEach { chunk ->
            val tokens = tokenize(chunk.text)
            tokenSets += tokens
            totalLength += tokens.size
            val present = tokens.toHashSet()
            queryTokens.forEach { token ->
                if (token in present) documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
            }
        }

        val count = chunks.size.coerceAtLeast(1)
        val averageLength = (totalLength.toDouble() / count).coerceAtLeast(1.0)
        return DoubleArray(chunks.size) { index ->
            val tokens = tokenSets[index]
            if (tokens.isEmpty()) return@DoubleArray 0.0
            val frequencies = mutableMapOf<String, Int>()
            tokens.forEach { token ->
                if (token in documentFrequency) frequencies[token] = (frequencies[token] ?: 0) + 1
            }
            queryTokens.sumOf { token ->
                val tf = frequencies[token]?.toDouble() ?: 0.0
                if (tf == 0.0) {
                    0.0
                } else {
                    val df = (documentFrequency[token] ?: 0).toDouble()
                    val idf = ln(1.0 + (count - df + 0.5) / (df + 0.5))
                    val lengthNorm = 1.0 - BM25_B + BM25_B * tokens.size / averageLength
                    idf * (tf * (BM25_K1 + 1.0)) / (tf + BM25_K1 * lengthNorm)
                }
            }
        }
    }

    private fun tokenize(text: String): List<String> = TOKEN_SPLIT
        .split(text.lowercase(Locale.ROOT))
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 2 && it !in STOP_WORDS }
        .toList()

    private val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private val STOP_WORDS = setOf(
        "что", "как", "это", "есть", "ещё", "еще", "об", "этом", "эта", "этот", "эти",
        "в", "во", "на", "и", "или", "а", "но", "о", "по", "из", "для", "ли", "же",
        "мне", "про", "там", "тут", "the", "and", "or", "is", "are", "of", "to", "in"
    )
    private const val RRF_K = 60.0
    private const val BM25_K1 = 1.2
    private const val BM25_B = 0.75
}
