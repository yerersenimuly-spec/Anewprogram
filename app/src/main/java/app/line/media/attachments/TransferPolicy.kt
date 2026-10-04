package app.line.media.attachments

import app.line.core.AttachmentCorruptException
import java.io.FileNotFoundException
import java.io.IOException

/** The API server refused a `blob_create` / `blob_get` with an `error` carrying [code]. */
class TicketRefused(val code: String) : IOException("Blob ticket refused: $code")

enum class TransferDirection { UPLOAD, DOWNLOAD }

/** What to do after a transfer attempt failed. Pure: the engine only executes the decision. */
object TransferPolicy {
    const val MAX_ATTEMPTS = 5
    const val MAX_TICKET_RENEWALS = 3
    const val IDLE_RETRY_MS = 30_000L
    const val CHAT_DISABLED_RETRY_MS = 60_000L
    private val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)
    private val transientCodes = setOf("rate_limited", "storage_unavailable")
    private val permanentUploadCodes = setOf("blob_too_large", "blocked", "not_found", "id_conflict", "blob_quota", "invalid_message")
    private val permanentDownloadCodes = setOf("unauthorized", "blocked", "invalid_message")
    private val goneCodes = setOf("not_found", "expired")

    sealed interface Decision {
        /** Try again after [delayMs]; [newTicket] when the old ticket is no good (counted against [MAX_TICKET_RENEWALS]). */
        data class Retry(val delayMs: Long, val newTicket: Boolean) : Decision

        /** Give up for now and try again later, keeping the stage; a returning connection retries at once. */
        data class Wait(val delayMs: Long) : Decision

        /** Mark the attachment failed (or expired when the server no longer has the blob). */
        data class Fail(val expired: Boolean) : Decision
    }

    /** [attempt] counts retries already made without a new ticket, [renewals] the tickets already renewed. */
    fun decide(direction: TransferDirection, error: Throwable, attempt: Int, renewals: Int): Decision {
        fun transient(): Decision = if (attempt < MAX_ATTEMPTS) Decision.Retry(backoff[attempt], false) else Decision.Wait(IDLE_RETRY_MS)
        fun renew(): Decision = if (renewals < MAX_TICKET_RENEWALS) Decision.Retry(0, true) else Decision.Fail(false)
        val download = direction == TransferDirection.DOWNLOAD
        return when (error) {
            is BlobException.NotFound -> if (download) Decision.Fail(true) else renew()
            // The server may still be draining the broken connection of the previous attempt (it answers 409 with the
            // offset it holds until its idle timeout): give it time instead of burning the renewals.
            is BlobException.Conflict -> when {
                error.serverOffset == null -> renew()
                attempt < MAX_ATTEMPTS -> Decision.Retry(backoff[attempt], true)
                else -> Decision.Wait(IDLE_RETRY_MS)
            }
            is BlobException.Unauthorized -> renew()
            is BlobException.TooLarge, is BlobException.Quota -> Decision.Fail(false)
            is BlobException.RateLimited -> {
                val wait = (error.retryAfterSeconds ?: 5).coerceIn(1, 60) * 1_000
                if (attempt < MAX_ATTEMPTS) Decision.Retry(wait, false) else Decision.Wait(IDLE_RETRY_MS)
            }
            is BlobException.Network -> transient()
            is BlobException.Server -> when {
                error.code == 410 && download -> Decision.Fail(true)
                error.code >= 500 -> transient()
                else -> Decision.Fail(false)
            }
            is TicketRefused -> when {
                error.code in transientCodes -> transient()
                error.code == "chat_disabled" -> Decision.Wait(CHAT_DISABLED_RETRY_MS)
                download && error.code in goneCodes -> Decision.Fail(true)
                download && error.code in permanentDownloadCodes -> Decision.Fail(false)
                !download && error.code in permanentUploadCodes -> Decision.Fail(false)
                else -> Decision.Wait(IDLE_RETRY_MS)
            }
            is AttachmentCorruptException, is FileNotFoundException -> Decision.Fail(false)
            is IOException -> transient()
            else -> Decision.Fail(false)
        }
    }
}
