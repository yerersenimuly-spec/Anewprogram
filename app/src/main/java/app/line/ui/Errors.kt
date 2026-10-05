package app.line.ui

import android.content.Context
import app.line.DifferentServerException
import app.line.R
import app.line.crypto.PeerIdentityChangedException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/** Maps technical failures to a short message the user can act on. */
fun Context.errorText(error: Throwable): String = getString(
    when {
        error is DifferentServerException -> R.string.err_different_server
        error is PeerIdentityChangedException -> R.string.err_identity_changed
        error is TimeoutCancellationException || error is IOException -> R.string.err_no_connection
        else -> when (error.message) {
            "not_found" -> R.string.err_number_not_found
            "blocked" -> R.string.err_blocked
            "chat_disabled" -> R.string.err_chat_disabled
            "prekeys_exhausted", "rate_limited", "storage_unavailable", "mailbox_full" -> R.string.err_try_later
            "SAS must be verified" -> R.string.err_verify_first
            "Offline", "Connection changed", "Connection settings changed", "Service destroyed" -> R.string.err_no_connection
            else -> R.string.err_generic
        }
    },
)
