package com.ayuemin.ymnik.network

internal object ChatToolPolicy {
    private val fileOutputPatterns = listOf(
        Regex("""(?iu)\b(сделай|создай|подготовь|сформируй|сохрани|экспортируй|пришли|дай|верни|оформи)\b.{0,80}\b(файл|docx|xlsx|pdf|csv|txt|word|excel|ворд)\b"""),
        Regex("""(?iu)\b(файл|docx|xlsx|pdf|csv|txt|word|excel|ворд)\b.{0,80}\b(сделай|создай|подготовь|сформируй|сохрани|экспортируй|пришли|дай|верни|оформи)\b"""),
        Regex("""(?iu)\b(скачать|скачивание|для скачивания|вордом|экселем|экспортируй)\b"""),
        Regex("""(?iu)\bв\s+формате\s+(docx|xlsx|pdf|csv|txt|word|excel)\b"""),
        Regex("""(?iu)\b(make|create|prepare|save|export|send|give|return)\b.{0,80}\b(file|docx|xlsx|pdf|csv|txt|word|excel)\b"""),
        Regex("""(?iu)\b(file|docx|xlsx|pdf|csv|txt|word|excel)\b.{0,80}\b(make|create|prepare|save|export|send|give|return)\b""")
    )

    fun needsCreateFile(prompt: String, instructions: List<String> = emptyList()): Boolean {
        if (matches(prompt)) return true
        return instructions.asSequence()
            .filter { it.isNotBlank() }
            .any(::matches)
    }

    private fun matches(text: String): Boolean =
        fileOutputPatterns.any { pattern -> pattern.containsMatchIn(text) }
}
