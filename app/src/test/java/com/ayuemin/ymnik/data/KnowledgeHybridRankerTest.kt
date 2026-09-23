package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.KnowledgeChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeHybridRankerTest {
    @Test
    fun exactTermsCanRescueLowerSemanticChunk() {
        val chunks = listOf(
            KnowledgeChunk(0, "Общий текст о развитии личности и поведении."),
            KnowledgeChunk(1, "Психология профессионализма изучает закономерности продвижения человека к профессионализму."),
            KnowledgeChunk(2, "Описание методов наблюдения и эксперимента.")
        )
        val semantic = doubleArrayOf(0.82, 0.64, 0.71)

        val ranked = KnowledgeHybridRanker.rank(
            "психология профессионализма",
            chunks,
            semantic
        )

        assertEquals(1, ranked.first().index)
        assertTrue(ranked.first().lexicalScore > 0.0)
    }

    @Test
    fun semanticRankingStillWorksWithoutExactTerms() {
        val chunks = listOf(
            KnowledgeChunk(0, "Текст про внимание и память."),
            KnowledgeChunk(1, "Материал о возрастных изменениях человека.")
        )
        val semantic = doubleArrayOf(0.45, 0.88)

        val ranked = KnowledgeHybridRanker.rank(
            "как меняется психика с возрастом",
            chunks,
            semantic
        )

        assertEquals(1, ranked.first().index)
    }
    @Test
    fun relevanceGateRejectsWeakNoiseButKeepsSupportedMatches() {
        assertTrue(!KnowledgeHybridRanker.passesRelevanceGate(0.25, 0.0))
        assertTrue(KnowledgeHybridRanker.passesRelevanceGate(0.45, 0.0))
        assertTrue(KnowledgeHybridRanker.passesRelevanceGate(0.36, 2.2))
        assertTrue(KnowledgeHybridRanker.passesRelevanceGate(0.30, 7.0))
    }
}
