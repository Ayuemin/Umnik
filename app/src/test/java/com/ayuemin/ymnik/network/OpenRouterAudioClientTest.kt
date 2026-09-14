package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAudioClientTest {
    @Test
    fun autoFormatUsesKnownSingleFormatProviders() {
        assertEquals(
            "pcm",
            OpenRouterAudioClient.resolveSpeechResponseFormat("google/gemini-3.1-flash-tts-preview", null)
        )
        assertEquals(
            "mp3",
            OpenRouterAudioClient.resolveSpeechResponseFormat("mistralai/voxtral-mini-tts-2603", null)
        )
        assertNull(OpenRouterAudioClient.resolveSpeechResponseFormat("x-ai/grok-voice-tts-1.0", null))
    }

    @Test
    fun explicitFormatWinsOverAuto() {
        assertEquals(
            "mp3",
            OpenRouterAudioClient.resolveSpeechResponseFormat("google/gemini-3.1-flash-tts-preview", "mp3")
        )
        assertEquals(
            "pcm",
            OpenRouterAudioClient.resolveSpeechResponseFormat("mistralai/voxtral-mini-tts-2603", "pcm")
        )
    }

    @Test
    fun pcmWrapperCreatesWaveContainer() {
        val wav = OpenRouterAudioClient.pcmToWav(byteArrayOf(0, 0, 1, 0), sampleRateHz = 24_000, channels = 1)
        assertTrue(wav.size >= 48)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }
}
