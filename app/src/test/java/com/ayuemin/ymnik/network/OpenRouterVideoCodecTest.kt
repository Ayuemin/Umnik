package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.VideoJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterVideoCodecTest {
    @Test
    fun buildsVideoPayloadWithCommonOptions() {
        val payload = OpenRouterVideoCodec.submitPayload(
            "google/veo-3.1",
            "Sunset",
            OpenRouterVideoClient.SubmitOptions(
                aspectRatio = "16:9",
                durationSeconds = 8,
                resolution = "720p",
                generateAudio = true
            )
        )

        assertEquals("google/veo-3.1", payload.get("model").asString)
        assertEquals("16:9", payload.get("aspect_ratio").asString)
        assertEquals(8, payload.get("duration").asInt)
        assertTrue(payload.get("generate_audio").asBoolean)
    }

    @Test
    fun parsesCompletedVideoJob() {
        val result = OpenRouterVideoCodec.parseSnapshot(
            """
            {
              "id":"job-1",
              "polling_url":"/api/v1/videos/job-1",
              "status":"completed",
              "generation_id":"gen-1",
              "unsigned_urls":["https://example.test/video.mp4"],
              "usage":{"cost":0.42}
            }
            """.trimIndent()
        )

        assertEquals(VideoJobStatus.COMPLETED, result.status)
        assertEquals("gen-1", result.generationId)
        assertEquals(0.42, result.costUsd!!, 0.0)
        assertEquals(1, result.urls.size)
    }

    @Test
    fun resolvesRelativePollingUrlAgainstOpenRouterOrigin() {
        assertEquals(
            "https://openrouter.ai/api/v1/videos/job-abc",
            OpenRouterVideoCodec.resolvePollingUrl(
                "https://openrouter.ai/api/v1",
                "job-abc",
                "/api/v1/videos/job-abc"
            )
        )
    }
}
