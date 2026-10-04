package app.line

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegistrationHandshakeTest {
    private fun error(code: String) = JSONObject().put("type", "error").put("code", code)

    @Test fun strictV6RejectionRetriesOnceWithOriginalIdentity() {
        val bundle = JSONObject().put("identityKey", "public-identity")
        val handshake = RegistrationHandshake("installation-secret", bundle)
        val modern = handshake.packet()
        assertEquals(RegistrationHandshake.CURRENT, modern.getInt("protocolVersion"))
        val legacy = handshake.retryForLegacy(error("registration_required"))!!
        assertEquals(setOf("type", "token", "bundle"), legacy.keys().asSequence().toSet())
        assertEquals(modern.getString("token"), legacy.getString("token"))
        assertSame(bundle, legacy.getJSONObject("bundle"))
        assertEquals(6, handshake.protocolVersion)
        assertNull(handshake.retryForLegacy(error("registration_required")))
    }

    @Test fun authenticationErrorsAndRegisteredSessionsNeverDowngrade() {
        listOf("invalid_token", "invalid_bundle", "identity_mismatch", "blocked", "registration_disabled", "rate_limited").forEach { code ->
            val handshake = RegistrationHandshake("installation-secret", JSONObject())
            assertNull(handshake.retryForLegacy(error(code)))
            assertEquals(RegistrationHandshake.CURRENT, handshake.protocolVersion)
        }
        val handshake = RegistrationHandshake("installation-secret", JSONObject())
        assertNull(handshake.retryForLegacy(error("registration_required").put("requestId", "lookup")))
        handshake.accept()
        assertNull(handshake.retryForLegacy(error("registration_required")))
    }
}
