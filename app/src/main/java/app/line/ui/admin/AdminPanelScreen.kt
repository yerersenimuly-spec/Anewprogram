package app.line.ui.admin

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.CallService
import app.line.CallState
import app.line.ConnectionProfile
import app.line.R
import app.line.core.NumberInput
import app.line.ui.*
import app.line.ui.calls.CallFormat
import app.line.ui.profile.switchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Server administration: rules, accounts, calls, diagnostics and connection. Every action goes through the signed-in admin session. */
class AdminPanelScreen : Screen() {
    private var status: AdminStatus? = null
    private var edited: AdminSettings? = null
    private var loadFailed = false
    private var busy = false
    private var tick: Job? = null
    private var numberText = ""
    private var applyButton: View? = null
    private var maxLabel: TextView? = null

    private lateinit var bar: TopBar
    private lateinit var body: LinearLayout

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    override fun createView(): View {
        val root = context.column()
        bar = TopBar(context).back(str(R.string.back)) { leave() }.setTitle(str(R.string.cp_admin_title))
        bar.action("refresh", str(R.string.cp_refresh)) { load() }
        bar.action("lock", str(R.string.cp_admin_sign_out)) { host.service?.lockAdmin(); AdminSession.expiresAt = 0; leave() }
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        body = context.column { setPadding(0, 0, 0, context.dp(32)) }
        root.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        renderLoading()
        load()
        return root
    }

    private fun leave() { tick?.cancel(); host.pop() }

    // ---- data -----------------------------------------------------------------------------------------

    private fun load() {
        if (busy) return
        loadFailed = false
        if (status == null) renderLoading()
        val first = status == null
        exec { service ->
            val parsed = AdminStatus.parse(service.adminCommand("status"))
            status = parsed
            edited = parsed.settings
            render()
            if (first) Motion.enter(body, 140)
        }
    }

    /** One command at a time; failures are shown in the interface language and an expired session closes the panel. */
    private fun exec(block: suspend (CallService) -> Unit) {
        val service = host.service ?: return
        if (busy) return
        busy = true
        host.uiScope.launch {
            try {
                if (!service.isAdmin()) throw IllegalStateException("Войдите в админ-панель заново")
                block(service)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (adminSessionExpired(error)) { host.toast(R.string.cp_admin_err_expired); leave() }
                else {
                    UiSounds.play(context, UiCue.ERROR)
                    host.toast(context.adminErrorText(error))
                    if (status == null) { loadFailed = true; renderError() }
                }
            } finally {
                busy = false
            }
        }
    }

    private fun command(action: String, extra: JSONObject = JSONObject()) = exec { service ->
        service.adminCommand(action, extra)
        val parsed = AdminStatus.parse(service.adminCommand("status"))
        status = parsed; edited = parsed.settings
        render()
    }

    // ---- rendering ------------------------------------------------------------------------------------

    private fun renderLoading() {
        body.removeAllViews()
        body.addView(SkeletonList(context, 5), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun renderError() {
        body.removeAllViews()
        body.addView(context.emptyState("alert", str(R.string.cp_admin_load_failed), null, str(R.string.cp_retry)) { load() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(48) })
    }

    private fun render() {
        val data = status ?: return
        val settings = edited ?: data.settings
        body.removeAllViews()
        statTiles(data)
        rules(data, settings)
        accounts(data)
        calls(data)
        diagnostics(data)
        connection()
    }

    private fun section(title: Int) = body.addView(context.sectionHeader(str(title)))

    private fun cardOf(vararg rows: View): LinearLayout {
        val card = context.card()
        rows.forEachIndexed { i, row ->
            if (i > 0) card.addView(context.divider(Dimens.SCREEN_PADDING))
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        body.addView(card)
        return card
    }

    private fun statTiles(data: AdminStatus) {
        fun tile(value: String, label: Int, tone: Int = R.color.text_primary): View = context.column {
            background = context.roundRect(R.color.surface, Dimens.RADIUS_M)
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            addView(context.label(value, TextStyle.TITLE, tone, maxLines = 1))
            addView(context.label(str(label), TextStyle.CAPTION, R.color.text_secondary, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(2) })
        }
        fun pair(a: View, b: View) = context.row {
            weightSum = 2f
            addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = context.dp(4) })
            addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(4) })
        }
        val metrics = data.metrics
        val params = { top: Int -> LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(16); marginEnd = context.dp(16); topMargin = context.dp(top) } }
        body.addView(pair(tile(metrics.online.toString(), R.string.cp_admin_online), tile(metrics.registered.toString(), R.string.cp_admin_registered)), params(8))
        val media = tile(str(if (metrics.mediaConfigured) R.string.cp_admin_ready else R.string.cp_admin_not_ready), R.string.cp_admin_media,
            if (metrics.mediaConfigured) R.color.positive else R.color.negative)
        body.addView(pair(tile(metrics.activeCalls.toString(), R.string.cp_admin_active_calls), media), params(8))
    }

    private fun rules(data: AdminStatus, settings: AdminSettings) {
        section(R.string.cp_admin_rules)
        fun toggle(title: Int, value: Boolean, change: (AdminSettings, Boolean) -> AdminSettings) =
            context.switchRow(null, str(title), null, value) { edited = change(edited ?: data.settings, it); updateApply(data) }.first
        val count = context.label(settings.maxParticipants.toString(), TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; minWidth = context.dp(28) }
        maxLabel = count
        val stepper = ListRow(context).apply {
            title.text = str(R.string.cp_admin_max_participants)
            leading(null)
            trailing(context.row {
                addView(context.iconButton("chevron_left", str(R.string.cp_admin_fewer), sizeDp = 40, iconDp = 22) { adjust(data, -1) })
                addView(count)
                addView(context.iconButton("chevron_right", str(R.string.cp_admin_more), sizeDp = 40, iconDp = 22) { adjust(data, +1) })
            })
        }
        cardOf(
            toggle(R.string.cp_admin_calls, settings.callsEnabled) { s, v -> s.copy(callsEnabled = v) },
            toggle(R.string.cp_admin_chat, settings.chatEnabled) { s, v -> s.copy(chatEnabled = v) },
            toggle(R.string.cp_admin_registration, settings.registrationEnabled) { s, v -> s.copy(registrationEnabled = v) },
            stepper,
        )
        val apply = context.primaryButton(str(R.string.cp_admin_apply)) { confirmRules(data, edited ?: data.settings) }
        applyButton = apply
        body.addView(apply, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(16); marginEnd = context.dp(16); topMargin = context.dp(12) })
        updateApply(data)
    }

    private fun updateApply(data: AdminStatus) {
        val dirty = AdminRules.dirty(data.settings, edited ?: data.settings)
        applyButton?.let { it.isEnabled = dirty; it.alpha = if (dirty) 1f else 0.4f }
    }

    private fun adjust(data: AdminStatus, delta: Int) {
        val current = edited ?: data.settings
        val next = (current.maxParticipants + delta).coerceIn(AdminSettings.MIN_PARTICIPANTS, AdminSettings.MAX_PARTICIPANTS)
        if (next == current.maxParticipants) return
        edited = current.copy(maxParticipants = next)
        maxLabel?.text = next.toString()
        updateApply(data)
    }

    private fun confirmRules(data: AdminStatus, settings: AdminSettings) {
        val ends = AdminRules.endsCalls(data.settings, settings, data.metrics.activeCalls)
        host.sheet().title(str(R.string.cp_admin_apply_title))
            .message(str(if (ends) R.string.cp_admin_apply_ends_calls else R.string.cp_admin_apply_body))
            .buttons(str(R.string.cp_admin_apply), secondary = str(R.string.cancel)) {
                command("update_settings", JSONObject().put("settings", settings.toJson()))
            }.show()
    }

    private fun accounts(data: AdminStatus) {
        section(R.string.cp_admin_accounts)
        val field = LineField(context, str(R.string.cp_admin_number_hint)).digits(8).apply { edit.setText(numberText) }
        field.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { numberText = s?.toString().orEmpty(); field.setError(null) }
        })
        fun target(): String? = when (val result = AdminRules.number(field.text())) {
            is NumberInput.Result.Valid -> result.numbers.first()
            is NumberInput.Result.Invalid -> { field.setError(str(R.string.err_number_format)); null }
        }
        val actions = context.row {
            addView(context.destructiveButton(str(R.string.cp_admin_block)) {
                val number = target() ?: return@destructiveButton
                host.sheet().title(str(R.string.cp_admin_block_title, CallFormat.number(number))).message(str(R.string.cp_admin_block_body))
                    .buttons(str(R.string.cp_admin_block), destructive = true, secondary = str(R.string.cancel)) {
                        numberText = ""; command("block", JSONObject().put("number", number))
                    }.show()
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(context.secondaryButton(str(R.string.cp_admin_unblock)) {
                val number = target() ?: return@secondaryButton
                numberText = ""; command("unblock", JSONObject().put("number", number))
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        }
        val form = context.column {
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(12) })
        }
        val rows = ArrayList<View>()
        rows += form
        if (data.blocked.isEmpty()) rows += context.sheetNote(str(R.string.cp_admin_no_blocked))
        else data.blocked.forEach { number ->
            rows += ListRow(context).apply {
                title.text = CallFormat.number(number)
                title.fontFeatureSettings = "tnum"
                subtitle(str(R.string.cp_admin_blocked))
                leading(context.tintedIcon("lock", R.color.negative, R.color.surface_raised))
                onClick { numberText = number; field.edit.setText(number); field.edit.setSelection(number.length) }
            }
        }
        cardOf(*rows.toTypedArray())
    }

    private fun Context.sheetNote(text: String): TextView =
        label(text, TextStyle.CALLOUT, R.color.text_secondary).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }

    private fun calls(data: AdminStatus) {
        section(R.string.cp_admin_calls_section)
        if (data.calls.isEmpty()) { cardOf(context.sheetNote(str(R.string.cp_admin_no_calls))); return }
        cardOf(*data.calls.mapIndexed { index, call ->
            ListRow(context).apply {
                title.text = str(R.string.cp_admin_call_row, index + 1, call.participants)
                leading(context.tintedIcon("users", R.color.accent, R.color.accent_soft))
                trailing(context.textButton(str(R.string.cp_admin_end), R.color.negative) {
                    host.sheet().title(str(R.string.cp_admin_end_title)).message(str(R.string.cp_admin_end_body))
                        .buttons(str(R.string.cp_admin_end), destructive = true, secondary = str(R.string.cancel)) {
                            command("end_call", JSONObject().put("callId", call.id))
                        }.show()
                })
                isClickable = false
            } as View
        }.toTypedArray())
    }

    private fun diagnostics(data: AdminStatus) {
        section(R.string.cp_admin_diagnostics)
        val events = AdminRules.lastEvents(data.events)
        val log = context.column {
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            addView(context.label(str(R.string.cp_admin_log_note), TextStyle.CAPTION, R.color.text_tertiary))
            if (events.isEmpty()) addView(context.label(str(R.string.cp_admin_log_empty), TextStyle.CALLOUT, R.color.text_secondary),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) })
            else addView(context.label(events.joinToString("\n") { listOf(it.at, it.event, it.outcome).filter(String::isNotEmpty).joinToString(" · ") },
                TextStyle.CAPTION, R.color.text_primary).apply { setTextIsSelectable(true); fontFeatureSettings = "tnum" },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) })
        }
        cardOf(
            log,
            action("copy", R.string.cp_admin_copy_report) { copyReport(data) },
            action("trash", R.string.cp_admin_clear_log, destructive = true) { command("clear_events") },
        )
    }

    private fun connection() {
        section(R.string.cp_admin_connection)
        cardOf(
            action("settings", R.string.cp_admin_endpoint) { AdminEndpointSheet.show(host) },
            action("link", R.string.cp_admin_copy_code) { copyCode() },
            action("refresh", R.string.cp_reconnect) { host.service?.reconnectNow(); host.toast(R.string.cp_reconnecting) },
        )
    }

    private fun action(icon: String, title: Int, destructive: Boolean = false, onClick: () -> Unit): View = ListRow(context).apply {
        this.title.text = str(title)
        this.title.setTextColor(context.color(if (destructive) R.color.negative else R.color.text_primary))
        leading(context.tintedIcon(icon, if (destructive) R.color.negative else R.color.text_secondary, R.color.surface_raised))
        onClick { onClick() }
    }

    private fun copyReport(data: AdminStatus) {
        val report = JSONObject().put("appVersion", versionName()).put("online", host.state.online)
            .put("metrics", data.raw.optJSONObject("metrics")).put("settings", data.raw.optJSONObject("settings"))
            .put("events", data.raw.optJSONArray("events")).toString(2)
        host.copyToClipboard("Line diagnostics", report)
    }

    private fun copyCode() {
        val service = host.service ?: return
        val config = service.config()
        if (!service.isAdmin()) { host.toast(R.string.cp_admin_err_expired); return }
        if (config == null) { host.toast(R.string.cp_conn_none); return }
        host.copyToClipboard("Line connection", ConnectionProfile.encode(config))
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    // ---- session --------------------------------------------------------------------------------------

    private fun startSessionClock() {
        tick?.cancel()
        tick = host.uiScope.launch {
            while (isActive) {
                val service = host.service
                if (service != null && !service.isAdmin()) {
                    host.toast(R.string.cp_admin_err_expired); leave(); return@launch
                }
                val remaining = AdminRules.remainingSeconds(AdminSession.expiresAt, System.currentTimeMillis())
                bar.setSubtitle(if (AdminSession.expiresAt > 0) str(R.string.cp_admin_session, AdminRules.clock(remaining)) else null)
                delay(1_000L - SystemClock.elapsedRealtime() % 1_000L)
            }
        }
    }

    override fun onShown() { startSessionClock() }
    override fun onHidden() { tick?.cancel() }
    override fun onState(old: CallState, new: CallState) {
        if (isBuilt && old.online && !new.online) bar.setSubtitle(null)
    }
    override fun destroy() { tick?.cancel() }
}
