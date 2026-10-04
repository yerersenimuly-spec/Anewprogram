package app.line

import java.net.URI
import java.security.MessageDigest

object Security {
    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "wss" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path == "/signal"
    }.getOrDefault(false)

    fun safetyCode(localSdp: String, remoteSdp: String): String {
        fun fingerprint(sdp: String): String? = sdp.lineSequence()
            .map { it.trim() }.firstOrNull { it.startsWith("a=fingerprint:sha-256 ") }
            ?.substringAfter("a=fingerprint:sha-256 ")?.uppercase()
            ?.takeIf { it.matches(Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")) }
        val local = fingerprint(localSdp) ?: return ""
        val remote = fingerprint(remoteSdp) ?: return ""
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(listOf(local, remote).sorted().joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02X".format(it.toInt() and 255) }
            .chunked(4).joinToString(" ")
    }
}
