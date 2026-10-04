package app.line.ui.calls

import app.line.Notice
import app.line.core.CallSummary

/** How a call attempt ended, from the user's point of view. */
enum class OutcomeKind { COMPLETED, MISSED, DECLINED, CANCELLED, NO_ANSWER, FAILED }

enum class Tone { POSITIVE, NEUTRAL, NEGATIVE }

enum class PrimaryAction { CALL_BACK, CALL_AGAIN, TRY_AGAIN }

/** Everything the post-call screen decides before drawing. */
data class SummaryModel(
    val kind: OutcomeKind,
    val tone: Tone,
    val showDuration: Boolean,
    val secure: Boolean,
    /** Call related notice that explains a failure or a dropped call; null when there is nothing to add. */
    val reason: Notice?,
    val action: PrimaryAction,
    val canMessage: Boolean,
)

object CallOutcomes {
    private val reasons = setOf(
        Notice.CALL_NETWORK_LOST, Notice.CALL_MEDIA_LOST, Notice.CALL_MEDIA_FAILED, Notice.CALL_SECURE_FAILED,
        Notice.CALL_PROTOCOL, Notice.CALL_UNAVAILABLE, Notice.CALLS_DISABLED, Notice.MIC_REQUIRED, Notice.REPLACED,
    )

    fun kind(outcome: String, durationSeconds: Long, notice: Notice = Notice.NONE): OutcomeKind = when {
        durationSeconds > 0 || outcome == "completed" -> OutcomeKind.COMPLETED
        outcome == "missed" -> OutcomeKind.MISSED
        outcome == "declined" -> OutcomeKind.DECLINED
        outcome == "cancelled" -> OutcomeKind.CANCELLED
        notice == Notice.CALL_NO_ANSWER -> OutcomeKind.NO_ANSWER
        else -> OutcomeKind.FAILED
    }

    /** True for outcomes the user did not choose: shown in the negative colour in lists. */
    fun isNegative(kind: OutcomeKind): Boolean = kind == OutcomeKind.MISSED || kind == OutcomeKind.FAILED

    fun summary(summary: CallSummary, notice: Notice): SummaryModel {
        val kind = kind(summary.outcome, summary.durationSeconds, notice)
        val reason = when (kind) {
            OutcomeKind.COMPLETED -> notice.takeIf { it == Notice.CALL_NETWORK_LOST || it == Notice.CALL_MEDIA_LOST }
            OutcomeKind.FAILED -> notice.takeIf { it in reasons }
            else -> null
        }
        val tone = when (kind) {
            OutcomeKind.COMPLETED -> Tone.POSITIVE
            OutcomeKind.MISSED, OutcomeKind.FAILED -> Tone.NEGATIVE
            else -> Tone.NEUTRAL
        }
        val action = when {
            summary.incoming -> PrimaryAction.CALL_BACK
            kind == OutcomeKind.NO_ANSWER || kind == OutcomeKind.FAILED -> PrimaryAction.TRY_AGAIN
            else -> PrimaryAction.CALL_AGAIN
        }
        return SummaryModel(
            kind = kind,
            tone = tone,
            showDuration = kind == OutcomeKind.COMPLETED && summary.durationSeconds > 0,
            secure = kind == OutcomeKind.COMPLETED,
            reason = reason,
            action = action,
            canMessage = !summary.group,
        )
    }
}
