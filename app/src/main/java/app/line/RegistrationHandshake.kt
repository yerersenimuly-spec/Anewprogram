package app.line

import org.json.JSONObject

/**
 * Registration packet negotiation. New clients announce [CURRENT]; a strict 0.6 server rejects the extra field with
 * `registration_required`, after which the same token/bundle is sent once in the original format.
 */
internal class RegistrationHandshake(private val token: String, private val bundle: JSONObject) {
    var protocolVersion = CURRENT
        private set
    private var registered = false

    fun packet(): JSONObject = JSONObject().put("type", "register").put("token", token).put("bundle", bundle).apply {
        if (protocolVersion > LEGACY) put("protocolVersion", protocolVersion)
    }

    fun retryForLegacy(response: JSONObject): JSONObject? {
        if (registered || protocolVersion == LEGACY || response.optString("type") != "error" ||
            response.optString("code") != "registration_required" || response.has("requestId") || response.has("id")) return null
        protocolVersion = LEGACY
        return packet()
    }

    fun accept() { registered = true }

    companion object {
        const val CURRENT = 8
        const val LEGACY = 6
    }
}
