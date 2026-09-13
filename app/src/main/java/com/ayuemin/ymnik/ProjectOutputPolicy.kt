package com.ayuemin.ymnik

/**
 * Kept as a neutral compatibility boundary for response post-processing.
 * Umnik must not infer or hard-code task-specific rules from project prompts.
 */
internal object ProjectOutputPolicy {
    fun apply(text: String, masterPrompt: String?): String = text
}
