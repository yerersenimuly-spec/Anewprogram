package app.line.ui.chat

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.R
import app.line.core.NumberInput
import app.line.ui.Dimens
import app.line.ui.LineField
import app.line.ui.MATCH
import app.line.ui.Screen
import app.line.ui.TopBar
import app.line.ui.WRAP
import app.line.ui.color
import app.line.ui.column
import app.line.ui.dp
import app.line.ui.errorText
import app.line.ui.primaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Start a dialog by number: validate, look the number up, compare safety codes, open the conversation. */
class NewChatScreen : Screen() {
    override val modal = true

    private lateinit var number: LineField
    private lateinit var name: LineField
    private lateinit var submit: TextView
    private var formatting = false
    private var busy = false

    override fun createView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(context.color(R.color.bg))
        root.addView(TopBar(context).back(context.getString(R.string.back)) { host.pop() }.setTitle(context.getString(R.string.newchat_title)))

        number = LineField(context, context.getString(R.string.newchat_number_hint))
        number.edit.apply {
            keyListener = DigitsKeyListener.getInstance("0123456789 ")
            setRawInputType(InputType.TYPE_CLASS_NUMBER)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(NumberEntry.LENGTH + 1))
            imeOptions = EditorInfo.IME_ACTION_NEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onNumberChanged(s)
            })
        }
        name = LineField(context, context.getString(R.string.newchat_name_hint)).singleLine(ContactNames.MAX_CODE_POINTS)
        name.edit.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        submit = context.primaryButton(context.getString(R.string.newchat_open)) { submit() }

        val form = context.column {
            setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(16), context.dp(Dimens.SCREEN_PADDING), context.dp(24))
            addView(number, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(12) })
            addView(submit, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(24) })
        }
        root.addView(ScrollView(context).apply { isFillViewport = true; addView(form) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() {
        number.edit.requestFocus()
        number.postDelayed({ context.getSystemService(InputMethodManager::class.java).showSoftInput(number.edit, InputMethodManager.SHOW_IMPLICIT) }, 260)
    }

    private fun onNumberChanged(editable: Editable?) {
        if (formatting || editable == null) return
        val text = editable.toString()
        val (formatted, caret) = NumberEntry.reformat(text, number.edit.selectionStart)
        if (formatted != text) {
            formatting = true
            number.edit.setText(formatted)
            number.edit.setSelection(caret.coerceIn(0, formatted.length))
            formatting = false
        }
        val digits = NumberEntry.digits(formatted)
        // The mistake worth flagging early is typing your own number; format errors wait for the button.
        number.setError(if (digits.length == NumberEntry.LENGTH && NumberEntry.feedback(digits, host.state.number) == NumberEntry.Feedback.OWN) context.getString(R.string.err_own_number) else null)
    }

    private fun submit() {
        if (busy) return
        when (val result = NumberInput.single(number.text(), host.state.number)) {
            is NumberInput.Result.Invalid -> number.setError(context.getString(
                if (result.problem == NumberInput.Problem.OWN) R.string.err_own_number else R.string.err_number_format))
            is NumberInput.Result.Valid -> proceed(result.numbers.single())
        }
    }

    private fun proceed(peer: String) {
        busy = true
        submit.alpha = 0.5f
        number.setError(null)
        host.hideKeyboard()
        host.uiScope.launch {
            try {
                val service = host.service ?: error("Offline")
                val code = service.inspectPeer(peer)
                ContactNames.save(context, peer, name.text())
                if (service.verified(peer)) { open(peer); return@launch }
                host.safetySheet(peer, code, verified = false) { confirmed ->
                    host.uiScope.launch {
                        if (confirmed) try { service.verifyPeer(peer) } catch (e: CancellationException) { throw e } catch (e: Exception) { host.toast(context.chatErrorText(e)) }
                        open(peer)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                number.setError(context.errorText(e))
            } finally {
                busy = false
                submit.alpha = 1f
            }
        }
    }

    private fun open(peer: String) {
        host.pop()
        host.openConversation(peer)
    }
}
