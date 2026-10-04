package app.line.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * End-to-end payload kinds layered on top of Signal ciphertext. Version 2 adds read receipts and media;
 * a peer is only sent receipts after it has shown (by a payload with "v" >= 2, or by registering with
 * protocol 8) that it understands them.
 */
object Receipts {
    const val VERSION = 2
    const val MAX_IDS = 100
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun chat(id: String, text: String): JSONObject =
        JSONObject().put("kind", "chat").put("id", id).put("text", text).put("v", VERSION)

    fun read(ids: List<String>): JSONObject =
        JSONObject().put("kind", "receipt").put("v", VERSION).put("status", "read").put("ids", JSONArray(ids))

    fun supported(payload: JSONObject): Boolean = payload.optInt("v", 1) >= VERSION

    /** Message ids confirmed as read by the peer, or null when the payload is not a valid read receipt. */
    fun parseRead(payload: JSONObject): List<String>? {
        if (payload.optString("kind") != "receipt" || payload.optString("status") != "read") return null
        val array = payload.optJSONArray("ids") ?: return null
        if (array.length() == 0 || array.length() > MAX_IDS) return null
        val ids = (0 until array.length()).map { array.optString(it) }
        return ids.takeIf { list -> list.all(uuid::matches) }
    }
}
