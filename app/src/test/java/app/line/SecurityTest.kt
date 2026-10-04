package app.line

import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    @Test fun onlySecureEndpointsWithoutCredentials() {
        assertTrue(Security.validEndpoint("wss://line.example.org/signal"))
        assertTrue(Security.validEndpoint("wss://localhost:8443/signal"))
        listOf("ws://host/signal", "https://host/signal", "wss://user:secret@host/signal",
            "wss://host/signal?token=secret", "wss://host/", "not a url").forEach {
            assertFalse(it, Security.validEndpoint(it))
        }
    }

    @Test fun codeMatchesBothPeersAndChangesWhenCertificateChanges() {
        fun sdp(byte: String) = "v=0\r\na=fingerprint:sha-256 " + List(32) { byte }.joinToString(":") + "\r\n"
        val a = sdp("AB")
        val b = sdp("CD")
        assertEquals(Security.safetyCode(a, b), Security.safetyCode(b, a))
        assertNotEquals(Security.safetyCode(a, b), Security.safetyCode(a, sdp("EF")))
        assertEquals(29, Security.safetyCode(a, b).length)
        assertEquals("", Security.safetyCode("invalid", b))
    }
}
