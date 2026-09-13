package com.ayuemin.ymnik

/** Deterministic safeguards for formatting rules explicitly stated in a project master prompt. */
internal object ProjectOutputPolicy {
    fun apply(text: String, masterPrompt: String?): String {
        if (text.isBlank() || masterPrompt.isNullOrBlank()) return text
        return if (forbidsLongDash(masterPrompt)) {
            text.replace('—', '-').replace('–', '-')
        } else {
            text
        }
    }

    internal fun forbidsLongDash(masterPrompt: String): Boolean {
        val prompt = masterPrompt.lowercase().replace('ё', 'е')
        return listOf(
            "запрещено применять длинное тире",
            "запрещено использовать длинное тире",
            "не используй длинное тире",
            "не использовать длинное тире",
            "без длинного тире",
            "длинное тире запрещено"
        ).any(prompt::contains)
    }
}
