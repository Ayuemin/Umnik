package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerRequestIdentityTest {
    @Test fun sameRequestAndStepReuseTheSameId() {
        val payload = payload("run-a", "1", "Привет")
        assertEquals(
            ServerRequestIdentity.build("request-a", payload),
            ServerRequestIdentity.build("request-a", payload)
        )
    }

    @Test fun samePayloadFromAnotherUserActionGetsAnotherId() {
        val payload = payload("run-a", "1", "Привет")
        assertNotEquals(
            ServerRequestIdentity.build("request-a", payload),
            ServerRequestIdentity.build("request-b", payload)
        )
    }

    @Test fun anotherToolStepInsideOneRequestGetsAnotherId() {
        assertNotEquals(
            ServerRequestIdentity.build("request-a", payload("run-a", "1", "Привет")),
            ServerRequestIdentity.build("request-a", payload("run-a", "2", "Привет"))
        )
    }

    @Test fun metadataKeepsCallsUniqueWhenRuntimeRequestIdIsUnavailable() {
        assertNotEquals(
            ServerRequestIdentity.build(null, payload("run-a", "1", "Привет")),
            ServerRequestIdentity.build(null, payload("run-b", "1", "Привет"))
        )
    }

    private fun payload(runId: String, step: String, text: String): String =
        """{"model":"test/model","messages":[{"role":"user","content":"$text"}],"metadata":{"umnik_request_id":"$runId","umnik_step":"$step"}}"""
}
