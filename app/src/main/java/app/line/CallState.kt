package app.line

enum class Phase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED }

data class CallState(
    val number: String = "",
    val online: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val peer: String = "",
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val connectedAt: Long = 0,
    val safetyCode: String = "",
    val members: List<String> = emptyList(),
    val participants: List<String> = emptyList(),
    val chatVersion: Long = 0,
    val eventVersion: Long = 0,
    val configReady: Boolean = false,
    val mediaReady: Boolean = false,
    val serverProtocol: Int = 0,
    val callsEnabled: Boolean = true,
    val chatEnabled: Boolean = true,
    val maxParticipants: Int = 8,
    val highQuality: Boolean = true,
    val message: String = "Укажите сервер, чтобы получить номер"
)
