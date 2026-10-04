package app.line.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import app.line.R
import app.line.ui.Host

/** Pieces of connection-code entry shared by first-run onboarding and the profile's "update code" sheet. */
object CodeEntry {
    fun problemText(problem: ConnectionCodes.Problem): Int = when (problem) {
        ConnectionCodes.Problem.EMPTY -> R.string.cp_ob_code_empty
        ConnectionCodes.Problem.NOT_A_CODE -> R.string.cp_ob_code_not_a_code
        ConnectionCodes.Problem.TOO_LONG, ConnectionCodes.Problem.DAMAGED -> R.string.cp_ob_code_damaged
        ConnectionCodes.Problem.UNSUPPORTED -> R.string.cp_ob_code_unsupported
        ConnectionCodes.Problem.INVALID -> R.string.cp_ob_code_invalid
    }

    /** The code found in the clipboard, or null when it holds no text. Read only on an explicit tap by the user. */
    fun clipboardCode(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return text.takeIf { it.isNotBlank() }?.let(ConnectionCodes::extract)
    }

    /** The server host is the one thing the user can verify, so it is the question. */
    fun confirm(host: Host, code: ConnectionCodes.Result.Valid, onConfirm: () -> Unit) {
        val context = host.activity
        host.hideKeyboard()
        host.sheet().title(context.getString(R.string.cp_confirm_title, code.host)).message(context.getString(R.string.cp_confirm_body))
            .buttons(context.getString(R.string.cp_connect), secondary = context.getString(R.string.cancel)) { onConfirm() }.show()
    }
}
