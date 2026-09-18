package com.ayuemin.ymnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointPolicyTest {
    @Test fun addsHttpsWhenSchemeIsOmittedAndKeepsCustomPort() {
        assertEquals(
            "https://example.com:8443",
            ServerEndpointPolicy.normalize("example.com:8443")
        )
    }

    @Test fun trimsTrailingSlash() {
        assertEquals(
            "https://example.com:9443/api",
            ServerEndpointPolicy.normalize(" https://example.com:9443/api/ ")
        )
    }

    @Test fun rejectsPlainHttpBecauseServerTokenWouldBeExposed() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpointPolicy.normalize("http://example.com:8787")
        }
    }

    @Test fun rejectsCredentialsAndQueryParametersInAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpointPolicy.normalize("https://user:pass@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpointPolicy.normalize("https://example.com?token=secret")
        }
    }
}
