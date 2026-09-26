package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSizeEstimatorTest {
    @Test
    fun `counts chars and utf8 bytes exactly`() {
        val ascii = ContextSizeEstimator.measure("abcd")
        assertEquals(4, ascii.chars)
        assertEquals(4, ascii.utf8Bytes)
        assertEquals(1, ascii.estimatedTokens)

        val russian = ContextSizeEstimator.measure("тест")
        assertEquals(4, russian.chars)
        assertEquals(8, russian.utf8Bytes)
        assertEquals(2, russian.estimatedTokens)
    }

    @Test
    fun `empty layer has zero estimate`() {
        val empty = ContextSizeEstimator.measure("")
        assertEquals(0, empty.chars)
        assertEquals(0, empty.utf8Bytes)
        assertEquals(0, empty.estimatedTokens)
    }

    @Test
    fun `estimate is stable and explicitly approximate`() {
        val text = "Системные инструкции Browser и Shell"
        val first = ContextSizeEstimator.measure(text)
        val second = ContextSizeEstimator.measure(text)
        assertEquals(first, second)
        assertTrue(first.estimatedTokens > 0)
    }
}
