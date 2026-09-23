package com.ayuemin.ymnik.data

import java.util.Locale

internal enum class KnowledgeRequestMode {
    NORMAL,
    BASE_ONLY
}

internal object KnowledgeIntent {
    fun mode(text: String): KnowledgeRequestMode {
        val normalized = normalize(text)
        if (normalized.isBlank()) return KnowledgeRequestMode.NORMAL

        val directBaseOnly = DIRECT_BASE_ONLY.any(normalized::contains)
        val asksDocumentContent =
            DOCUMENT_QUESTION_PREFIXES.any(normalized::contains) &&
                DOCUMENT_WORDS.any(normalized::contains)
        val asksToFindInDocument =
            FIND_PREFIXES.any(normalized::contains) &&
                DOCUMENT_WORDS.any(normalized::contains)

        return if (directBaseOnly || asksDocumentContent || asksToFindInDocument) {
            KnowledgeRequestMode.BASE_ONLY
        } else {
            KnowledgeRequestMode.NORMAL
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private val DIRECT_BASE_ONLY = listOf(
        "только по базе",
        "только из базы",
        "только по книге",
        "только из книги",
        "только по учебнику",
        "только из учебника",
        "только по документу",
        "только из документа",
        "ответь по базе",
        "ответь по книге",
        "ответь по учебнику",
        "ответь по документу",
        "отвечай по базе",
        "отвечай по книге",
        "по материалам базы знаний"
    )

    private val DOCUMENT_QUESTION_PREFIXES = listOf(
        "что написано",
        "что сказано",
        "что говорится",
        "как описано",
        "как объясняется",
        "что автор пишет",
        "что автор говорит"
    )

    private val FIND_PREFIXES = listOf(
        "найди",
        "посмотри",
        "проверь",
        "процитируй",
        "цитируй"
    )

    private val DOCUMENT_WORDS = listOf(
        "в книге",
        "в учебнике",
        "в документе",
        "в базе",
        "по книге",
        "по учебнику",
        "по документу",
        "по базе"
    )
}
