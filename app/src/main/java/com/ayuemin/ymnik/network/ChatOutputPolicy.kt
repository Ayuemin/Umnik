package com.ayuemin.ymnik.network

internal object ChatOutputPolicy {
    private val directImageVerb = Regex(
        """(?iuU)\b(нарисуй|изобрази|сгенерируй|сгенерировать|отрисуй|визуализируй|render|draw|paint)\b"""
    )
    private val imageNoun = Regex(
        """(?iuU)\b(изображени\w*|картинк\w*|иллюстраци\w*|обложк\w*|постер\w*|логотип\w*|баннер\w*|аватар\w*|фото\w*|image\w*|picture\w*|illustration\w*|cover\w*|poster\w*|logo\w*|banner\w*|avatar\w*|photo\w*)\b"""
    )
    private val createVerb = Regex(
        """(?iuU)\b(создай|сделай|подготовь|разработай|оформи|create|make|design|generate)\b"""
    )
    private val editVerb = Regex(
        """(?iuU)\b(измени|изменить|отредактируй|перерисуй|убери|удали|замени|добавь|перекрась|ретушир\w*|edit|modify|redraw|remove|replace|add|retouch)\b"""
    )
    private val promptOnly = Regex(
        """(?iuU)\b(промпт|prompt)\b.{0,40}\b(для|for)\b.{0,30}(изображ|картин|image|picture|illustration)"""
    )

    fun wantsGeneratedImage(prompt: String, hasImageAttachment: Boolean = false): Boolean {
        val text = prompt.trim()
        if (text.isBlank()) return hasImageAttachment && editVerb.containsMatchIn(text)
        if (promptOnly.containsMatchIn(text)) return false
        if (directImageVerb.containsMatchIn(text)) return true
        if (imageNoun.containsMatchIn(text) && createVerb.containsMatchIn(text)) return true
        if (hasImageAttachment && editVerb.containsMatchIn(text)) return true
        return false
    }
}
