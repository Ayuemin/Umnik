package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.RequestCostBreakdown
import java.math.BigDecimal

enum class RequestCostKind {
    PRIMARY,
    SYSTEM,
    EMBEDDINGS
}

internal class RequestCostLedger {
    private val lock = Any()
    private var primary = BigDecimal.ZERO
    private var system = BigDecimal.ZERO
    private var embeddings = BigDecimal.ZERO
    private var primaryCalls = 0
    private var systemCalls = 0
    private var embeddingCalls = 0
    private var primaryKnown = false
    private var systemKnown = false
    private var embeddingsKnown = false
    private var incomplete = false

    fun record(kind: RequestCostKind, exactUsd: String?) {
        val amount = exactUsd
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?.takeIf { it >= BigDecimal.ZERO }

        synchronized(lock) {
            when (kind) {
                RequestCostKind.PRIMARY -> primaryCalls += 1
                RequestCostKind.SYSTEM -> systemCalls += 1
                RequestCostKind.EMBEDDINGS -> embeddingCalls += 1
            }
            if (amount == null) {
                incomplete = true
                return
            }
            when (kind) {
                RequestCostKind.PRIMARY -> {
                    primary = primary.add(amount)
                    primaryKnown = true
                }
                RequestCostKind.SYSTEM -> {
                    system = system.add(amount)
                    systemKnown = true
                }
                RequestCostKind.EMBEDDINGS -> {
                    embeddings = embeddings.add(amount)
                    embeddingsKnown = true
                }
            }
        }
    }

    fun snapshot(): RequestCostBreakdown? = synchronized(lock) {
        if (primaryCalls + systemCalls + embeddingCalls == 0) return@synchronized null
        val service = system.add(embeddings)
        val total = primary.add(service)
        RequestCostBreakdown(
            primaryUsd = primary.takeIf { primaryKnown }?.toExactUsd(),
            systemUsd = system.takeIf { systemKnown }?.toExactUsd(),
            embeddingsUsd = embeddings.takeIf { embeddingsKnown }?.toExactUsd(),
            serviceUsd = service.takeIf { systemKnown || embeddingsKnown }?.toExactUsd(),
            knownTotalUsd = total.takeIf { primaryKnown || systemKnown || embeddingsKnown }?.toExactUsd(),
            primaryCalls = primaryCalls,
            systemCalls = systemCalls,
            embeddingCalls = embeddingCalls,
            incomplete = incomplete
        )
    }

    private fun BigDecimal.toExactUsd(): String =
        stripTrailingZeros().let { value ->
            if (value.compareTo(BigDecimal.ZERO) == 0) "0" else value.toPlainString()
        }
}
