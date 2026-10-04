package app.line.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.CallState
import app.line.DifferentServerException
import app.line.R
import app.line.core.DisplayName
import app.line.ui.*
import app.line.ui.calls.CallFormat
import app.line.ui.onboarding.*
import app.line.ui.profile.NameCheck
import app.line.ui.profile.NameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** First run: connection code, server-assigned number, optional name, notification permission. */
class OnboardingScreen : Screen() {
    override val edgeToEdge = true

    private var step = Step.CODE
    private var codeText = ""
    private var pending: ConnectionCodes.Result.Valid? = null
    private var server: ConnectionCodes.Result.Valid? = null
    private var connectStartedAt = 0L
    private var connectView: ConnectView? = null
    private var scroll: ScrollView? = null
    private var codeField: LineField? = null
    private lateinit var container: FrameLayout
    private var insetTop = 0
    private var insetBottom = 0

    private val state: CallState get() = host.state

    override fun createView(): View {
        container = FrameLayout(context)
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        scroll = scrollView
        val root = FrameLayout(context).apply { setBackgroundColor(context.color(R.color.bg)) }
        root.addView(scrollView, FrameLayout.LayoutParams(MATCH, MATCH))
        applyInsets()
        showStep(Step.CODE, animate = false)
        return root
    }

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    override fun onInsets(top: Int, bottom: Int) { insetTop = top; insetBottom = bottom; applyInsets() }

    private fun applyInsets() {
        scroll?.setPadding(0, insetTop, 0, insetBottom)
    }

    // ---- steps ----------------------------------------------------------------------------------------

    private fun showStep(next: Step, animate: Boolean = true) {
        step = next
        host.hideKeyboard()
        connectView = null
        val view = when (next) {
            Step.CODE -> codeStep()
            Step.CONNECT -> connectStep()
            Step.NUMBER -> numberStep()
            Step.NAME -> nameStep()
            Step.NOTIFICATIONS -> notificationsStep()
        }
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        if (animate) Motion.enter(view, 180)
        scroll?.scrollTo(0, 0)
    }

    private fun page(block: LinearLayout.() -> Unit): LinearLayout = context.column {
        val side = context.dp(Dimens.SCREEN_PADDING + 4)
        setPadding(side, context.dp(48), side, context.dp(24))
        block()
    }

    private fun LinearLayout.heading(title: Int, body: Int?) {
        addView(context.label(str(title), TextStyle.HEADLINE), LinearLayout.LayoutParams(MATCH, WRAP))
        if (body != null) addView(context.label(str(body), TextStyle.BODY, R.color.text_secondary),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(12) })
    }

    private fun LinearLayout.spacer() = addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))

    private fun LinearLayout.action(button: View, top: Int = 0) =
        addView(button, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(top) })

    private fun dim(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    // -- code

    private fun codeStep(): View = page {
        addView(FrameLayout(context).apply {
            background = context.accentGradient(14)
            addView(context.icon("chat", R.color.on_accent, 24), FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { bottomMargin = context.dp(28) })
        heading(R.string.cp_ob_code_title, R.string.cp_ob_code_body)

        val field = LineField(context, str(R.string.cp_ob_code_hint)).apply {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            edit.setSingleLine(false)
            edit.setLines(4)
            edit.gravity = Gravity.TOP or Gravity.START
            edit.setText(codeText)
        }
        addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(28) })
        codeField = field

        val next = context.primaryButton(str(R.string.cp_continue)) { submit(field) }
        dim(next, codeText.isNotBlank())
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                codeText = s?.toString().orEmpty()
                field.setError(null)
                dim(next, codeText.isNotBlank())
            }
        })
        field.edit.setOnEditorActionListener { _, _, _ -> if (next.isEnabled) submit(field); true }

        addView(context.textButton(str(R.string.cp_paste)) { paste(field) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8); gravity = Gravity.END })
        spacer()
        action(next, 24)
    }

    private fun paste(field: LineField) {
        val code = CodeEntry.clipboardCode(context)
        if (code == null) { host.toast(R.string.cp_paste_empty); return }
        field.edit.setText(code)
        field.edit.setSelection(field.edit.text.length)
        submit(field)
    }

    private fun submit(field: LineField) {
        when (val result = ConnectionCodes.parse(field.text())) {
            is ConnectionCodes.Result.Valid -> CodeEntry.confirm(host, result) { connect(result) }
            is ConnectionCodes.Result.Invalid -> {
                UiSounds.play(context, UiCue.ERROR)
                field.setError(str(CodeEntry.problemText(result.problem)))
            }
        }
    }

    // -- connect

    private fun connect(code: ConnectionCodes.Result.Valid) {
        pending = code
        applyPending()
    }

    private fun applyPending() {
        val code = pending ?: return
        val service = host.service ?: return
        pending = null
        try {
            service.configure(code.config, state.highQuality)
            service.applyPersistentSetting()
        } catch (error: Exception) {
            showStep(Step.CODE)
            codeField?.setError(str(if (error is DifferentServerException) R.string.err_different_server else R.string.cp_ob_code_invalid))
            return
        }
        server = code
        connectStartedAt = SystemClock.elapsedRealtime()
        showStep(Step.CONNECT)
        evaluate()
    }

    private class ConnectView(val root: LinearLayout, val spinner: ArcSpinner, val glyph: FrameLayout, val glyphIcon: IconView,
                              val title: TextView, val body: TextView, val retry: View, val other: View)

    private fun connectStep(): View {
        val spinner = ArcSpinner(context)
        val glyphIcon = context.icon("wifi_off", R.color.text_secondary, 28)
        val glyph = FrameLayout(context).apply {
            background = context.circle(R.color.surface_raised)
            addView(glyphIcon, FrameLayout.LayoutParams(context.dp(28), context.dp(28), Gravity.CENTER))
            visibility = View.GONE
        }
        val title = context.label("", TextStyle.TITLE).apply { gravity = Gravity.CENTER }
        val body = context.label("", TextStyle.CALLOUT, R.color.text_secondary).apply { gravity = Gravity.CENTER }
        val retry = context.primaryButton(str(R.string.cp_retry)) {
            host.service?.reconnectNow()
            connectStartedAt = SystemClock.elapsedRealtime()
            evaluate()
        }
        val other = context.textButton(str(R.string.cp_ob_other_code), R.color.text_secondary) { showStep(Step.CODE) }
        val root = context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            val side = context.dp(Dimens.SCREEN_PADDING + 4)
            setPadding(side, context.dp(48), side, context.dp(24))
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(spinner, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            addView(glyph, LinearLayout.LayoutParams(context.dp(72), context.dp(72)))
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(24) })
            addView(body, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1.4f))
            addView(retry, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(other, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(4) })
        }
        connectView = ConnectView(root, spinner, glyph, glyphIcon, title, body, retry, other)
        render(ConnectPhase.CONNECTING)
        return root
    }

    private fun render(phase: ConnectPhase) {
        val view = connectView ?: return
        val hostName = server?.host.orEmpty()
        val working = phase == ConnectPhase.CONNECTING
        view.spinner.visibility = if (working) View.VISIBLE else View.GONE
        view.glyph.visibility = if (working) View.GONE else View.VISIBLE
        view.glyphIcon.setIcon(if (phase == ConnectPhase.OFFLINE) "wifi_off" else "alert")
        view.title.text = str(when (phase) {
            ConnectPhase.OFFLINE -> R.string.cp_ob_offline_title
            ConnectPhase.FAILED -> R.string.cp_ob_failed_title
            else -> R.string.cp_ob_connecting_title
        })
        view.body.text = when (phase) {
            ConnectPhase.OFFLINE -> str(R.string.cp_ob_offline_body)
            ConnectPhase.FAILED -> str(R.string.cp_ob_failed_body)
            else -> hostName
        }
        view.retry.visibility = if (phase == ConnectPhase.FAILED) View.VISIBLE else View.GONE
        view.other.visibility = if (phase == ConnectPhase.FAILED) View.VISIBLE else View.GONE
    }

    private fun evaluate() {
        if (step != Step.CONNECT) return
        val elapsed = SystemClock.elapsedRealtime() - connectStartedAt
        val phase = OnboardingFlow.phase(state, elapsed)
        if (phase == ConnectPhase.READY) { advance(); return }
        render(phase)
        connectView?.root?.removeCallbacks(recheck)
        if (phase == ConnectPhase.CONNECTING) connectView?.root?.postDelayed(recheck, OnboardingFlow.FAIL_AFTER_MS - elapsed + 50)
    }

    private val recheck = Runnable { evaluate() }

    // -- number

    private fun numberStep(): View = page {
        heading(R.string.cp_ob_number_title, R.string.cp_ob_number_body)
        val number = CallFormat.number(state.number)
        val card = context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            background = context.roundRect(R.color.surface, Dimens.RADIUS_XL)
            setPadding(context.dp(20), context.dp(32), context.dp(20), context.dp(24))
            addView(context.label(number, TextStyle.DISPLAY).apply {
                textSize = 44f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum"
                contentDescription = state.number.toCharArray().joinToString(" ")
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(context.secondaryButton(str(R.string.copy)) { host.copyToClipboard("Line", state.number) },
                LinearLayout.LayoutParams(WRAP, context.dp(48)).apply { topMargin = context.dp(24) })
        }
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(32) })
        spacer()
        action(context.primaryButton(str(R.string.cp_next)) { advance() }, 24)
    }

    // -- name

    private fun nameStep(): View = page {
        heading(R.string.cp_ob_name_title, R.string.cp_ob_name_body)
        val field = LineField(context, str(R.string.cp_name_hint)).singleLine(NameRules.MAX)
        addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(28) })
        val save = context.primaryButton(str(R.string.save)) { saveName(field.text()) }
        dim(save, false)
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val check = NameRules.check(s?.toString().orEmpty(), state.ownName)
                field.setError(when {
                    check is NameCheck.Invalid && check.tooLong -> str(R.string.cp_name_too_long, NameRules.MAX)
                    check is NameCheck.Invalid -> str(R.string.cp_name_invalid)
                    else -> null
                })
                dim(save, check is NameCheck.Valid && check.name.isNotEmpty())
            }
        })
        field.edit.setOnEditorActionListener { _, _, _ -> if (save.isEnabled) saveName(field.text()); true }
        field.edit.requestFocus()
        field.edit.postDelayed({ context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field.edit, InputMethodManager.SHOW_IMPLICIT) }, 300)
        spacer()
        action(save, 24)
        action(context.textButton(str(R.string.cp_skip), R.color.text_secondary) { advance() }, 4)
    }

    private fun saveName(raw: String) {
        val name = DisplayName.normalize(raw)?.takeIf { it.isNotEmpty() } ?: return
        val service = host.service
        if (service == null) { advance(); return }
        host.uiScope.launch {
            try { service.setOwnName(name) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            advance()
        }
    }

    // -- notifications

    private fun notificationsStep(): View = page {
        addView(FrameLayout(context).apply {
            background = context.circle(R.color.accent_soft)
            addView(context.icon("bell", R.color.accent, 28), FrameLayout.LayoutParams(context.dp(28), context.dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(context.dp(64), context.dp(64)).apply { bottomMargin = context.dp(28) })
        heading(R.string.cp_ob_notif_title, R.string.cp_ob_notif_body)
        spacer()
        action(context.primaryButton(str(R.string.cp_allow)) { host.requestNotificationPermission(); finish() }, 24)
        action(context.textButton(str(R.string.cp_not_now), R.color.text_secondary) { finish() }, 4)
    }

    private fun needsNotificationStep(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        return !context.getSharedPreferences("line-ui", android.content.Context.MODE_PRIVATE).getBoolean("asked_notifications", false)
    }

    private fun advance() {
        val next = OnboardingFlow.next(step, needsNotificationStep())
        if (next == null) finish() else showStep(next)
    }

    private fun finish() = host.selectTab("chats")

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onServiceReady() { if (isBuilt) applyPending() }

    override fun onState(old: CallState, new: CallState) {
        if (isBuilt && step == Step.CONNECT) evaluate()
    }

    override fun onBack(): Boolean {
        val back = OnboardingFlow.back(step)
        if (back != null) { showStep(back); return true }
        return step != Step.CODE
    }

    override fun destroy() { connectView?.root?.removeCallbacks(recheck) }
}
