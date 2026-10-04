package app.line.ui.calls

import app.line.CallState
import app.line.Link
import app.line.Phase

enum class CallMode { INCOMING, OUTGOING, CONNECTING, CONNECTED }

/** Shown while the signalling link is down but the call may still be alive. */
enum class LinkNote { NONE, NO_NETWORK, RECONNECTING }

enum class PeerPresence { PRESENT, PENDING }

/** What the active call screen draws, reduced from [CallState]; equal models need no redraw. */
data class CallUiModel(
    val mode: CallMode,
    /** The caller of an incoming call, otherwise null. */
    val caller: String?,
    val peers: List<String>,
    val groupSize: Int,
    val presence: Map<String, PeerPresence>,
    val muted: Boolean,
    val speaker: Boolean,
    val note: LinkNote,
    val connectedAt: Long,
) {
    val group: Boolean get() = peers.size > 1
}

object CallScreenModel {
    fun from(state: CallState): CallUiModel? {
        val mode = when (state.phase) {
            Phase.IDLE -> return null
            Phase.INCOMING -> CallMode.INCOMING
            Phase.OUTGOING -> CallMode.OUTGOING
            Phase.CONNECTING -> CallMode.CONNECTING
            Phase.CONNECTED -> CallMode.CONNECTED
        }
        val peers = CallFormat.peers(state.members, state.number, state.peer)
        val present = state.participants.toSet()
        return CallUiModel(
            mode = mode,
            caller = if (mode == CallMode.INCOMING) state.peer.takeIf { it.isNotEmpty() } ?: peers.firstOrNull() else null,
            peers = peers,
            groupSize = (peers.size + 1).coerceAtLeast(2),
            presence = peers.associateWith { if (it in present) PeerPresence.PRESENT else PeerPresence.PENDING },
            muted = state.muted,
            speaker = state.speaker,
            note = note(state),
            connectedAt = state.connectedAt,
        )
    }

    fun note(state: CallState): LinkNote = when {
        state.phase == Phase.IDLE || state.phase == Phase.INCOMING -> LinkNote.NONE
        state.link == Link.WAITING_NETWORK -> LinkNote.NO_NETWORK
        state.signalingLost || state.link != Link.ONLINE -> LinkNote.RECONNECTING
        else -> LinkNote.NONE
    }
}
