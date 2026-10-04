package app.line

import org.json.JSONObject

internal class RegistrationHandshake(private val token: String, private val bundle: JSONObject) {
    var protocolVersion = 7
        private set
    private var registered = false

    fun packet(): JSONObject = JSONObject().put("type", "register").put("token", token).put("bundle", bundle).apply {
        if (protocolVersion >= 7) put("protocolVersion", protocolVersion)
    }

    fun retryForLegacy(response: JSONObject): JSONObject? {
        if (registered || protocolVersion != 7 || response.optString("type") != "error" ||
            response.optString("code") != "registration_required" || response.has("requestId") || response.has("id")) return null
        protocolVersion = 6
        return packet()
    }

    fun accept() { registered = true }
}
