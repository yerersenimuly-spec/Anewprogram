package app.line.core

import org.json.JSONObject

/** The only data a push carries: what happened and an opaque id. Never names, numbers or text. */
data class PushPayload(val kind: Kind, val id: String, val reason: String? = null) {
    enum class Kind(val wire: String) { MESSAGE("message"), CALL("call"), CALL_ENDED("call_ended") }

    companion object {
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val reasons = setOf("timeout", "declined", "left", "disconnected", "media_unavailable")

        fun parse(content: ByteArray): PushPayload? = runCatching {
            if (content.isEmpty() || content.size > 1_024) return null
            val json = JSONObject(String(content, Charsets.UTF_8))
            val keys = json.keys().asSequence().toSet()
            if (!setOf("kind", "id").all { it in keys } || !setOf("kind", "id", "reason").containsAll(keys)) return null
            val kind = Kind.entries.firstOrNull { it.wire == json.getString("kind") } ?: return null
            val id = json.getString("id").takeIf(uuid::matches) ?: return null
            val reason = if (json.has("reason")) json.getString("reason").takeIf { it in reasons } ?: return null else null
            PushPayload(kind, id, reason)
        }.getOrNull()
    }
}
