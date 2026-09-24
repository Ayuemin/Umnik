package com.ayuemin.ymnik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCostLedgerTest {
    @Test
    fun sumsExactDecimalsWithoutDisplayRounding() {
        val ledger = RequestCostLedger()
        ledger.record(RequestCostKind.PRIMARY, "0.007848")
        ledger.record(RequestCostKind.SYSTEM, "0.000102")
        ledger.record(RequestCostKind.EMBEDDINGS, "0.0000500")

        val snapshot = requireNotNull(ledger.snapshot())
        assertEquals("0.007848", snapshot.primaryUsd)
        assertEquals("0.000102", snapshot.systemUsd)
        assertEquals("0.00005", snapshot.embeddingsUsd)
        assertEquals("0.000152", snapshot.serviceUsd)
        assertEquals("0.008", snapshot.knownTotalUsd)
        assertFalse(snapshot.incomplete)
    }

    @Test
    fun acceptsScientificNotationAndReturnsPlainDecimal() {
        val ledger = RequestCostLedger()
        ledger.record(RequestCostKind.PRIMARY, "7.848E-3")
        assertEquals("0.007848", requireNotNull(ledger.snapshot()).knownTotalUsd)
    }

    @Test
    fun missingProviderCostMarksBreakdownIncomplete() {
        val ledger = RequestCostLedger()
        ledger.record(RequestCostKind.PRIMARY, "0.001")
        ledger.record(RequestCostKind.EMBEDDINGS, null)
        val snapshot = requireNotNull(ledger.snapshot())
        assertTrue(snapshot.incomplete)
        assertEquals(1, snapshot.embeddingCalls)
        assertNull(snapshot.embeddingsUsd)
        assertEquals("0.001", snapshot.knownTotalUsd)
    }

    @Test
    fun countsEveryUnderlyingCall() {
        val ledger = RequestCostLedger()
        repeat(3) { ledger.record(RequestCostKind.PRIMARY, "0.001") }
        repeat(2) { ledger.record(RequestCostKind.SYSTEM, "0.0001") }
        val snapshot = requireNotNull(ledger.snapshot())
        assertEquals(3, snapshot.primaryCalls)
        assertEquals(2, snapshot.systemCalls)
        assertEquals("0.0032", snapshot.knownTotalUsd)
    }
    @Test
    fun unknownPrimaryCostIsNeverDisplayedAsZero() {
        val ledger = RequestCostLedger()
        ledger.record(RequestCostKind.PRIMARY, null)
        ledger.record(RequestCostKind.EMBEDDINGS, "0.0000004")

        val snapshot = requireNotNull(ledger.snapshot())
        assertNull(snapshot.primaryUsd)
        assertEquals("0.0000004", snapshot.embeddingsUsd)
        assertEquals("0.0000004", snapshot.knownTotalUsd)
        assertTrue(snapshot.incomplete)
    }
}
