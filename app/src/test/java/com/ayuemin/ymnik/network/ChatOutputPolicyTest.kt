package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOutputPolicyTest {
    @Test
    fun detectsDirectRussianImageRequests() {
        assertTrue(ChatOutputPolicy.wantsGeneratedImage("Нарисуй обложку для статьи"))
        assertTrue(ChatOutputPolicy.wantsGeneratedImage("Создай изображение ночного города"))
        assertTrue(ChatOutputPolicy.wantsGeneratedImage("Сгенерируй картинку 16:9"))
    }

    @Test
    fun doesNotTreatPromptWritingAsImageGeneration() {
        assertFalse(ChatOutputPolicy.wantsGeneratedImage("Сделай промпт для изображения обложки"))
        assertFalse(ChatOutputPolicy.wantsGeneratedImage("Расскажи, какие модели умеют создавать изображения"))
    }

    @Test
    fun editingAttachedImageCountsAsGeneration() {
        assertTrue(ChatOutputPolicy.wantsGeneratedImage("Убери текст и добавь дерево", hasImageAttachment = true))
        assertFalse(ChatOutputPolicy.wantsGeneratedImage("Что изображено на фото?", hasImageAttachment = true))
    }
}
