package app.line

import org.json.JSONObject
import java.util.Base64

object ConnectionProfile {
    fun encode(config: EndpointConfig): String {
        config.validate()
        val json = JSONObject().put("version", 1).put("api", config.apiUrl).put("apiPins", config.apiPins)
            .put("media", config.mediaUrl).put("mediaPins", config.mediaPins)
        return "LINE1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun decode(code: String): EndpointConfig {
        val text = code.trim()
        require(text.startsWith("LINE1.") && text.length <= 4096) { "Проверьте код подключения" }
        val json = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(text.removePrefix("LINE1.")), Charsets.UTF_8))
        }.getOrElse { throw IllegalArgumentException("Код подключения повреждён") }
        require(json.optInt("version") == 1) { "Этот код подключения не поддерживается" }
        require(json.keys().asSequence().toSet() == setOf("version", "api", "apiPins", "media", "mediaPins")) { "Неверный формат кода" }
        return EndpointConfig(json.getString("api"), json.getString("apiPins"), json.getString("media"), json.getString("mediaPins"))
            .also { it.validate() }
    }
}
