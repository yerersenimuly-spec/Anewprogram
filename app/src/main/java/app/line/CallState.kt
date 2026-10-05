package app.line

import app.line.core.CallSummary
import app.line.core.ServerInfo

enum class Phase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED }

/** State of the API connection, independent of calls. */
enum class Link { NONE, WAITING_NETWORK, CONNECTING, ONLINE }

/** One-shot events for the user. The UI maps each to a localized string; the service never produces display text. */
enum class Notice {
    NONE, CALL_ENDED, CALL_DECLINED, CALL_NO_ANSWER, CALL_NETWORK_LOST, CALL_MEDIA_LOST, CALL_MEDIA_FAILED,
    CALL_SECURE_FAILED, CALL_PROTOCOL, CALL_UNAVAILABLE, CALLS_DISABLED, MIC_REQUIRED, REPLACED,
    MESSAGE_REJECTED, SERVER_MESSAGE_REJECTED, OPERATION_FAILED,
}

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
    val profileVersion: Long = 0,
    /** Bumped whenever an attachment changes stage or progress (throttled); screens re-read [CallService.attachment]. */
    val attachmentVersion: Long = 0,
    val configReady: Boolean = false,
    val mediaReady: Boolean = false,
    val serverProtocol: Int = 0,
    val callsEnabled: Boolean = true,
    val chatEnabled: Boolean = true,
    val maxParticipants: Int = 8,
    val highQuality: Boolean = true,
    val link: Link = Link.NONE,
    val pending: Int = 0,
    val ownName: String = "",
    val server: ServerInfo = ServerInfo(),
    val pushActive: Boolean = false,
    val heldSenders: Int = 0,
    val signalingLost: Boolean = false,
    val notice: Notice = Notice.NONE,
    val noticeVersion: Long = 0,
    val lastCall: CallSummary? = null,
)
