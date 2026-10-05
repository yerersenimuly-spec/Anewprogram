package app.line.core

import org.json.JSONObject

/** What the connected API server announced in `registered`. Servers older than 0.8 announce nothing extra. */
data class ServerInfo(
    val protocol: Int = 0,
    val features: Set<String> = emptySet(),
    val pushProviders: Set<String> = emptySet(),
    val serverTimeSkewMs: Long = 0,
    val name: String = "",
) {
    val supportsProfiles: Boolean get() = "profile" in features
    val supportsAttachments: Boolean get() = "attachments" in features
    val supportsSilentReceipts: Boolean get() = protocol >= 8
    val supportsUnifiedPush: Boolean get() = "unifiedpush" in pushProviders

    companion object {
        fun fromRegistered(message: JSONObject, sentProtocol: Int, nowMs: Long): ServerInfo {
            val announced = message.optInt("protocol", 0)
            val protocol = when {
                announced > 0 -> minOf(announced, sentProtocol)
                message.has("pushEnabled") -> minOf(7, sentProtocol)
                else -> 6
            }
            fun strings(key: String): Set<String> {
                val array = message.optJSONArray(key) ?: return emptySet()
                return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }.toSet()
            }
            val serverTime = message.optLong("serverTime", 0)
            return ServerInfo(
                protocol = protocol,
                features = strings("features"),
                pushProviders = strings("pushProviders"),
                serverTimeSkewMs = if (serverTime > 0) serverTime - nowMs else 0,
                name = message.optString("name", ""),
            )
        }
    }
}
