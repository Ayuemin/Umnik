package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.KnowledgeChunk

internal data class KnowledgeSourceSection(
    val text: String,
    val page: Int? = null
)

internal object KnowledgeChunker {
    fun chunk(
        sections: List<KnowledgeSourceSection>,
        targetChars: Int = 1900,
        overlapChars: Int = 260
    ): List<KnowledgeChunk> {
        require(targetChars >= 400)
        require(overlapChars in 0 until targetChars)
        val result = mutableListOf<KnowledgeChunk>()
        sections.forEach { section ->
            val clean = normalize(section.text)
            if (clean.isBlank()) return@forEach
            var start = 0
            while (start < clean.length) {
                var end = (start + targetChars).coerceAtMost(clean.length)
                if (end < clean.length) {
                    val floor = (start + targetChars * 2 / 3).coerceAtMost(end)
                    val boundary = findBoundary(clean, floor, end)
                    if (boundary > start) end = boundary
                }
                val text = clean.substring(start, end).trim()
                if (text.isNotBlank()) {
                    result += KnowledgeChunk(
                        ordinal = result.size,
                        text = text,
                        page = section.page
                    )
                }
                if (end >= clean.length) break
                start = (end - overlapChars).coerceAtLeast(start + 1)
            }
        }
        return result
    }

    private fun normalize(text: String): String = text
        .replace('\u0000', ' ')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun findBoundary(text: String, floor: Int, end: Int): Int {
        for (index in end - 1 downTo floor) {
            if (text[index] == '\n') return index + 1
        }
        for (index in end - 1 downTo floor) {
            if (text[index] in charArrayOf('.', '!', '?', ';', ':')) return index + 1
        }
        for (index in end - 1 downTo floor) {
            if (text[index].isWhitespace()) return index + 1
        }
        return end
    }
}
