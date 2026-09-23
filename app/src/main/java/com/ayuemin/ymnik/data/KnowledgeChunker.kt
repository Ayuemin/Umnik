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

    internal fun isLikelyEncodedBlob(text: String): Boolean {
        val longestToken = text
            .split(Regex("\\s+"))
            .maxByOrNull { it.length }
            .orEmpty()
        if (longestToken.length >= 256) {
            val encodedChars = longestToken.count(::isBase64LikeChar)
            if (encodedChars.toDouble() / longestToken.length.toDouble() >= 0.97) return true
        }

        val compact = text.filterNot(Char::isWhitespace)
        if (compact.length < 512) return false
        val whitespaceRatio = text.count(Char::isWhitespace).toDouble() / text.length.coerceAtLeast(1)
        if (whitespaceRatio > 0.08) return false
        val encodedChars = compact.count(::isBase64LikeChar)
        return encodedChars.toDouble() / compact.length.toDouble() >= 0.98
    }

    private fun isBase64LikeChar(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '+' || ch == '/' || ch == '=' || ch == '_' || ch == '-'

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
