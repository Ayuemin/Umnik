package com.ayuemin.ymnik.network

import java.util.Locale

internal class KnowledgeToolBudget(rawLimit: Int) {
    val limit: Int = rawLimit.coerceIn(0, 10)
    private val cache = mutableMapOf<String, String>()
    var usedCalls: Int = 0
        private set

    suspend fun execute(query: String, loader: suspend (String) -> String): String {
        val clean = query.trim().take(12000)
        require(clean.isNotBlank()) { "Пустой поисковый запрос" }
        val key = clean.lowercase(Locale.ROOT)
        cache[key]?.let { return it }
        require(usedCalls < limit) {
            "Достигнут лимит самостоятельных обращений к базе знаний: $limit"
        }
        usedCalls += 1
        return loader(clean).also { cache[key] = it }
    }
}
