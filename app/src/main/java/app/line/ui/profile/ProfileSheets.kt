package app.line.ui.profile

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import app.line.DifferentServerException
import app.line.Phase
import app.line.R
import app.line.Settings
import app.line.admin.TripleTapGate
import app.line.core.DisplayName
import app.line.push.PushRegistrar
import app.line.ui.*
import app.line.ui.onboarding.CodeEntry
import app.line.ui.onboarding.ConnectionCodes
import app.line.ui.screens.AdminEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Detail sheets of the profile. Each is short: a few rows, one decision. */
class ProfileSheets(private val host: Host, private val onChanged: () -> Unit) {
    private val context: Context get() = host.activity
    private val adminGate = TripleTapGate()
    private var live: (() -> Unit)? = null

    /** Called when state or the screen changes underneath an open sheet. */
    fun refresh() { live?.invoke() }

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    private fun scroll(content: View): View =
        BoundedScrollView(context, (context.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply { addView(content) }

    private fun open(title: CharSequence, content: View, refresh: (() -> Unit)? = null): Sheet {
        val sheet = host.sheet().title(title).content(scroll(content))
        live = refresh
        sheet.onDismiss = { if (live === refresh) live = null }
        return sheet.also { it.show() }
    }

    // ---- name -----------------------------------------------------------------------------------------

    fun name() {
        val current = host.state.ownName
        val body = context.column { setPadding(0, 0, 0, context.dp(4)) }
        val field = LineField(context, str(R.string.cp_name_hint)).singleLine(NameRules.MAX).apply { edit.setText(current); edit.setSelection(current.length) }
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING) })
        val unsupported = host.state.online && !host.state.server.supportsProfiles
        body.addView(context.label(str(if (unsupported) R.string.cp_name_unsupported else R.string.cp_name_visible), TextStyle.CAPTION, R.color.text_secondary),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(Dimens.SCREEN_PADDING + 4); marginEnd = context.dp(Dimens.SCREEN_PADDING); topMargin = context.dp(4) })
        var sheet: Sheet? = null
        val save = context.primaryButton(str(R.string.save)) {
            val normalized = DisplayName.normalize(field.text()) ?: return@primaryButton
            sheet?.dismiss()
            host.run {
                host.service?.setOwnName(normalized)
                onChanged()
            }
        }
        save.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING); topMargin = context.dp(16) }
        body.addView(save)
        fun check() {
            val result = NameRules.check(field.text(), host.state.ownName)
            field.setError(when {
                result is NameCheck.Invalid && result.tooLong -> str(R.string.cp_name_too_long, NameRules.MAX)
                result is NameCheck.Invalid -> str(R.string.cp_name_invalid)
                else -> null
            })
            val ok = result is NameCheck.Valid
            save.isEnabled = ok; save.alpha = if (ok) 1f else 0.4f
        }
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = check()
        })
        field.edit.setOnEditorActionListener { _, _, _ -> if (save.isEnabled) save.performClick(); true }
        check()
        sheet = open(str(R.string.cp_name_title), body)
        field.edit.requestFocus()
        field.edit.postDelayed({ context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field.edit, InputMethodManager.SHOW_IMPLICIT) }, 280)
    }

    // ---- notifications --------------------------------------------------------------------------------

    fun notifications() {
        val body = context.column()
        fun render() {
            body.removeAllViews()
            fun toggle(title: Int, key: String, value: Boolean, subtitle: Int? = null) {
                body.addView(context.switchRow(null, str(title), subtitle?.let { str(it) }, value) { Settings.set(context, key, it); onChanged() }.first, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            toggle(R.string.cp_notify_messages, Settings.NOTIFY_MESSAGES, Settings.notifyMessages(context))
            toggle(R.string.cp_notify_calls, Settings.NOTIFY_CALLS, Settings.notifyCalls(context))
            toggle(R.string.cp_notify_preview, Settings.NOTIFY_PREVIEW, Settings.notifyPreview(context), R.string.cp_notify_preview_hint)
            val enabled = systemNotificationsEnabled()
            body.addView(ListRow(context).apply {
                title.text = str(R.string.cp_notify_system)
                subtitle(if (enabled) null else str(R.string.cp_notify_system_off))
                leading(null)
                trailing(context.icon("chevron_right", R.color.text_tertiary, 20))
                onClick { openNotificationSettings() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        render()
        open(str(R.string.cp_row_notifications), body) { render() }
    }

    private fun systemNotificationsEnabled(): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(true)

    private fun openNotificationSettings() {
        val intent = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
        launch(intent)
    }

    private fun launch(intent: Intent): Boolean = try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    // ---- connection -----------------------------------------------------------------------------------

    fun connection() {
        val body = context.column()
        fun render() {
            body.removeAllViews()
            val state = host.state
            val status = ProfileStatus.connection(state)
            val service = host.service
            val hostName = service?.config()?.let { ConnectionCodes.hostOf(it) }
            body.addView(ListRow(context).apply {
                title.text = str(statusText(status))
                subtitle(hostName)
                leading(context.statusDot(when (status) {
                    ConnectionStatus.ONLINE -> R.color.positive
                    ConnectionStatus.NOT_CONFIGURED -> R.color.text_tertiary
                    else -> R.color.warning
                }, 12).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = context.dp(12); marginEnd = context.dp(12) } })
                isClickable = false
                background = null
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            body.addView(context.switchRow(null, str(R.string.cp_persistent), str(R.string.cp_persistent_hint), Settings.persistent(context)) { value ->
                Settings.set(context, Settings.PERSISTENT, value)
                host.service?.applyPersistentSetting()
            }.first, LinearLayout.LayoutParams(MATCH, WRAP))

            val exempt = ignoringBatteryOptimizations()
            body.addView(ListRow(context).apply {
                title.text = str(R.string.cp_battery)
                subtitle(str(if (exempt) R.string.cp_battery_free else R.string.cp_battery_limited))
                leading(null)
                trailing(context.icon("chevron_right", R.color.text_tertiary, 20))
                onClick { openBatterySettings(exempt) }
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            if (status != ConnectionStatus.NOT_CONFIGURED) body.addView(actionRow("refresh", R.string.cp_reconnect) { host.service?.reconnectNow(); host.toast(R.string.cp_reconnecting) })
            body.addView(actionRow("link", R.string.cp_update_code) { importCode() })
        }
        render()
        open(str(R.string.cp_row_connection), body) { render() }
    }

    private fun actionRow(icon: String, title: Int, onClick: () -> Unit): View = ListRow(context).apply {
        this.title.text = str(title)
        leading(context.tintedIcon(icon, R.color.text_secondary, R.color.surface_raised))
        onClick { onClick() }
    }

    private fun statusText(status: ConnectionStatus): Int = when (status) {
        ConnectionStatus.ONLINE -> R.string.cp_conn_online
        ConnectionStatus.CONNECTING -> R.string.cp_conn_connecting
        ConnectionStatus.WAITING_NETWORK -> R.string.cp_conn_waiting
        ConnectionStatus.NOT_CONFIGURED -> R.string.cp_conn_none
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)

    private fun openBatterySettings(exempt: Boolean) {
        val request = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (exempt || !launch(request)) launch(list)
    }

    private fun importCode() {
        if (host.state.phase != Phase.IDLE) { host.toast(R.string.cp_finish_call_first); return }
        val body = context.column()
        val field = LineField(context, str(R.string.cp_ob_code_hint)).apply {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            edit.setSingleLine(false); edit.setLines(3); edit.gravity = Gravity.TOP or Gravity.START
        }
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING) })
        var sheet: Sheet? = null
        fun submit() {
            when (val result = ConnectionCodes.parse(field.text())) {
                is ConnectionCodes.Result.Invalid -> { UiSounds.play(context, UiCue.ERROR); field.setError(str(CodeEntry.problemText(result.problem))) }
                is ConnectionCodes.Result.Valid -> {
                    sheet?.dismiss()
                    CodeEntry.confirm(host, result) { apply(result) }
                }
            }
        }
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = field.setError(null)
        })
        body.addView(context.sheetButton(str(R.string.cp_paste)) {
            val code = CodeEntry.clipboardCode(context)
            if (code == null) host.toast(R.string.cp_paste_empty) else { field.edit.setText(code); submit() }
        })
        body.addView(context.sheetButton(str(R.string.cp_continue), primary = true) { submit() })
        sheet = open(str(R.string.cp_update_code), body)
    }

    private fun apply(code: ConnectionCodes.Result.Valid) {
        val service = host.service ?: return
        try {
            service.configure(code.config, host.state.highQuality)
            host.toast(R.string.cp_code_applied)
        } catch (error: DifferentServerException) {
            host.toast(R.string.err_different_server)
        } catch (error: Exception) {
            host.toast(R.string.cp_ob_code_invalid)
        }
    }

    // ---- push -----------------------------------------------------------------------------------------

    fun push() {
        val body = context.column()
        fun render() {
            body.removeAllViews()
            val distributors = PushRegistrar.distributors(context)
            val selected = PushRegistrar.selectedDistributor(context)
            val status = pushStatus()
            body.addView(context.sheetText(str(pushMessage(status)), if (status == PushStatus.FAILED) R.color.negative else R.color.text_secondary))
            distributors.forEach { id ->
                body.addView(context.optionRow(distributorLabel(id), null, id == selected) {
                    if (id != selected) {
                        PushRegistrar.select(context, id)
                        host.service?.pushEndpointChanged()
                        onChanged(); render()
                    }
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            if (distributors.isEmpty()) body.addView(context.sheetButton(str(R.string.cp_push_install), primary = true) { openStore() })
            else if (selected != null && selected in distributors) body.addView(actionRow("bell_off", R.string.cp_push_disable) {
                PushRegistrar.disable(context)
                host.service?.unregisterPush()
                onChanged(); render()
            })
        }
        render()
        open(str(R.string.cp_row_push), body) { render() }
    }

    fun pushStatus(): PushStatus {
        val state = host.state
        val distributors = PushRegistrar.distributors(context)
        return PushStates.of(
            distributors = distributors,
            selected = PushRegistrar.selectedDistributor(context),
            hasEndpoint = PushRegistrar.hasEndpoint(context),
            failure = PushRegistrar.failure(context),
            active = state.pushActive,
            online = state.online,
            serverSupports = state.server.supportsUnifiedPush,
        )
    }

    private fun pushMessage(status: PushStatus): Int = when (status) {
        PushStatus.NO_DISTRIBUTOR -> R.string.cp_push_msg_none
        PushStatus.NOT_SELECTED -> R.string.cp_push_msg_choose
        PushStatus.WAITING -> R.string.cp_push_msg_waiting
        PushStatus.ACTIVE -> R.string.cp_push_msg_active
        PushStatus.FAILED -> R.string.cp_push_msg_failed
        PushStatus.SERVER_UNSUPPORTED -> R.string.cp_push_msg_unsupported
    }

    private fun distributorLabel(id: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(id, 0)).toString()
    }.getOrDefault(id)

    private fun openStore() {
        val id = "io.heckel.ntfy"
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))) launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$id/")))
    }

    // ---- call quality and language ---------------------------------------------------------------------

    fun quality() {
        val body = context.column()
        fun render() {
            body.removeAllViews()
            val high = host.state.highQuality
            body.addView(context.optionRow(str(R.string.cp_quality_high), str(R.string.cp_quality_high_hint), high) { choose(true) }, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(context.optionRow(str(R.string.cp_quality_low), str(R.string.cp_quality_low_hint), !high) { choose(false) }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        render()
        open(str(R.string.cp_row_quality), body) { render() }
    }

    private fun choose(high: Boolean) {
        if (host.state.phase != Phase.IDLE) { host.toast(R.string.cp_finish_call_first); return }
        host.service?.setQuality(high)
        onChanged()
        live?.invoke()
    }

    fun language() {
        val body = context.column()
        val current = Locales.language(context)
        var sheet: Sheet? = null
        Locales.supported.forEach { code ->
            body.addView(context.optionRow(Languages.nativeName(code), null, code == current) {
                sheet?.dismiss()
                if (code != current) { Locales.set(context, code); host.recreateWithLanguage() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        sheet = open(str(R.string.cp_row_language), body)
    }

    // ---- about ----------------------------------------------------------------------------------------

    fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifEmpty { "0.8.0" }

    fun about() {
        val body = context.column()
        var sheet: Sheet? = null
        body.addView(ListRow(context).apply {
            title.text = str(R.string.cp_version)
            leading(null)
            trailing(context.label(versionName(), TextStyle.CALLOUT, R.color.text_secondary))
            onClick {
                if (adminGate.tap(android.os.SystemClock.elapsedRealtime())) { sheet?.dismiss(); AdminEntry.open(host) }
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(context.sheetText(str(R.string.cp_licenses)))
        sheet = open(str(R.string.app_name) + " " + versionName(), body)
    }
}
