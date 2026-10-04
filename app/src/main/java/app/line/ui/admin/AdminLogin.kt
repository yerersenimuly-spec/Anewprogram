package app.line.ui.admin

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import app.line.CallService
import app.line.R
import app.line.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Message for an admin failure in the interface language; the service raises some of them in Russian only. */
fun Context.adminErrorText(error: Throwable): String = when (AdminErrors.classify(error.message)) {
    AdminErrors.Kind.NOT_CONFIGURED -> getString(R.string.cp_admin_err_disabled)
    AdminErrors.Kind.RATE_LIMITED -> getString(R.string.cp_admin_err_rate)
    AdminErrors.Kind.INVALID_CODE -> getString(R.string.cp_admin_err_code)
    AdminErrors.Kind.EXPIRED -> getString(R.string.cp_admin_err_expired)
    AdminErrors.Kind.REJECTED -> getString(R.string.cp_admin_err_rejected)
    AdminErrors.Kind.CANCELLED -> getString(R.string.cp_admin_err_cancelled)
    AdminErrors.Kind.OTHER -> errorText(error)
}

fun adminSessionExpired(error: Throwable): Boolean = AdminErrors.classify(error.message) == AdminErrors.Kind.EXPIRED

/** Secret code prompt. The code lives only in the field: it is cleared on every exit and never stored. */
class AdminLogin(private val host: Host) {
    private val context: Context get() = host.activity
    private var job: Job? = null
    private var signedIn = false

    fun show(service: CallService, onSuccess: () -> Unit) {
        val field = LineField(context, context.getString(R.string.cp_admin_code_hint)).apply {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.filters = arrayOf(InputFilter.LengthFilter(AdminRules.SECRET_MAX))
            edit.setSingleLine(true)
        }
        val body = context.column()
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING)
        })
        var busy = false
        val enter = context.primaryButton(context.getString(R.string.cp_admin_sign_in)) {}
        enter.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING); topMargin = context.dp(16)
        }
        body.addView(enter)
        fun refresh() {
            val ready = AdminRules.secretValid(field.text()) && !busy
            enter.isEnabled = ready; enter.alpha = if (ready) 1f else 0.4f
        }
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { field.setError(null); refresh() }
        })
        val sheet = host.sheet().title(context.getString(R.string.cp_admin_login_title)).content(body)
        sheet.onDismiss = {
            field.edit.setText("")
            job?.cancel()
            if (!signedIn) service.lockAdmin()
        }
        fun submit() {
            if (busy || !AdminRules.secretValid(field.text())) return
            busy = true; refresh()
            enter.text = context.getString(R.string.cp_admin_checking)
            val secret = field.text()
            job = host.uiScope.launch {
                try {
                    val result = service.adminLogin(secret)
                    if (!sheet.isShowing) { service.lockAdmin(); return@launch }
                    AdminSession.expiresAt = AdminRules.sessionEnd(result.optLong("expiresAt"), System.currentTimeMillis())
                    signedIn = true
                    field.edit.setText("")
                    host.hideKeyboard()
                    sheet.dismiss()
                    onSuccess()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (sheet.isShowing) {
                        UiSounds.play(context, UiCue.ERROR)
                        field.setError(context.adminErrorText(error))
                        busy = false
                        enter.text = context.getString(R.string.cp_admin_sign_in)
                        refresh()
                    }
                }
            }
        }
        enter.setOnClickListener { submit() }
        field.edit.setOnEditorActionListener { _, _, _ -> submit(); true }
        refresh()
        sheet.show()
        field.edit.requestFocus()
        field.edit.postDelayed({ context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field.edit, InputMethodManager.SHOW_IMPLICIT) }, 280)
    }
}
