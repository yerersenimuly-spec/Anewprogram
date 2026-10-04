package app.line.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.CallState
import app.line.R
import app.line.Settings
import app.line.ui.*
import app.line.ui.calls.CallFormat
import app.line.ui.profile.*

/** Profile tab: identity, then one row per setting group; details open in sheets. */
class ProfileScreen : Screen() {
    override val tab = "profile"

    private lateinit var sheets: ProfileSheets
    private var shownKey: Any? = null
    private lateinit var avatar: AvatarView
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var copyButton: View
    private lateinit var notificationsRow: SettingRow
    private lateinit var connectionRow: SettingRow
    private lateinit var pushRow: SettingRow
    private lateinit var qualityRow: SettingRow
    private lateinit var languageRow: SettingRow
    private lateinit var aboutRow: SettingRow

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    override fun createView(): View {
        sheets = ProfileSheets(host) { bind() }
        val root = context.column()
        root.addView(LargeTitleBar(context, str(R.string.nav_profile)), LinearLayout.LayoutParams(MATCH, WRAP))

        val page = context.column { setPadding(0, context.dp(4), 0, context.dp(24)) }
        page.addView(identityCard())

        val soundsRow = context.switchRow("sound_wave", str(R.string.cp_row_ui_sounds), null, Settings.uiSounds(context)) { enabled ->
            Settings.set(context, Settings.UI_SOUNDS, enabled)
            if (enabled) UiSounds.play(context, UiCue.TOGGLE_ON)
        }.first
        val receiptsRow = context.switchRow("check_double", str(R.string.cp_row_read_receipts), str(R.string.cp_row_read_receipts_hint), Settings.readReceipts(context)) {
            Settings.set(context, Settings.READ_RECEIPTS, it)
        }.first
        notificationsRow = context.settingRow("bell", str(R.string.cp_row_notifications), null) { sheets.notifications() }
        group(page, notificationsRow.view, soundsRow, receiptsRow)

        connectionRow = context.settingRow("globe", str(R.string.cp_row_connection), null) { sheets.connection() }
        pushRow = context.settingRow("download", str(R.string.cp_row_push), null) { sheets.push() }
        qualityRow = context.settingRow("speaker", str(R.string.cp_row_quality), null) { sheets.quality() }
        group(page, connectionRow.view, pushRow.view, qualityRow.view)

        languageRow = context.settingRow("globe", str(R.string.cp_row_language), Languages.nativeName(Locales.language(context))) { sheets.language() }
        aboutRow = context.settingRow("info", str(R.string.cp_row_about), null) { sheets.about() }
        group(page, languageRow.view, aboutRow.view)

        root.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        bind()
        return root
    }

    private fun group(page: LinearLayout, vararg rows: View) {
        val card = context.card()
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(context.divider(Dimens.SCREEN_PADDING + 50))
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(16)
        page.addView(card)
    }

    private fun identityCard(): View {
        avatar = AvatarView(context)
        nameView = context.label("", TextStyle.TITLE, maxLines = 1)
        val top = context.row {
            minimumHeight = context.dp(80)
            setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(16), context.dp(8), context.dp(12))
            background = context.ripple(null)
            isClickable = true; isFocusable = true
            setOnClickListener { sheets.name() }
            addView(avatar, LinearLayout.LayoutParams(context.dp(64), context.dp(64)))
            addView(nameView, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(16) })
            addView(context.icon("edit", R.color.text_tertiary, 20), LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(12) })
            contentDescription = str(R.string.cp_name_title)
        }
        numberView = context.label("", TextStyle.HEADLINE).apply { fontFeatureSettings = "tnum" }
        copyButton = context.iconButton("copy", str(R.string.copy), tintRes = R.color.text_secondary) {
            host.state.number.takeIf { it.isNotEmpty() }?.let { host.copyToClipboard("Line", it) }
        }
        val bottom = context.row {
            setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(8), context.dp(8), context.dp(12))
            addView(context.column {
                addView(context.label(str(R.string.cp_own_number), TextStyle.CAPTION, R.color.text_tertiary))
                addView(numberView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(copyButton)
        }
        val card = context.card()
        card.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(context.divider(Dimens.SCREEN_PADDING))
        card.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    // ---- binding --------------------------------------------------------------------------------------

    private fun bind() {
        if (!isBuilt) return
        val state = host.state
        val name = state.ownName
        avatar.bind(state.number, name.takeIf { it.isNotEmpty() })
        nameView.text = if (name.isEmpty()) str(R.string.cp_name_add) else name
        nameView.setTextColor(context.color(if (name.isEmpty()) R.color.text_tertiary else R.color.text_primary))
        val hasNumber = state.number.isNotEmpty()
        numberView.text = if (hasNumber) CallFormat.number(state.number) else str(R.string.cp_number_pending)
        numberView.setTextColor(context.color(if (hasNumber) R.color.text_primary else R.color.text_tertiary))
        copyButton.visibility = if (hasNumber) View.VISIBLE else View.GONE

        val notifying = Settings.notifyMessages(context) || Settings.notifyCalls(context)
        notificationsRow.setValue(str(if (notifying) R.string.cp_on else R.string.cp_off))
        connectionRow.setValue(str(when (ProfileStatus.connection(state)) {
            ConnectionStatus.ONLINE -> R.string.cp_conn_online
            ConnectionStatus.CONNECTING -> R.string.cp_conn_connecting
            ConnectionStatus.WAITING_NETWORK -> R.string.cp_conn_waiting
            ConnectionStatus.NOT_CONFIGURED -> R.string.cp_conn_none
        }))
        pushRow.setValue(str(when (sheets.pushStatus()) {
            PushStatus.ACTIVE -> R.string.cp_push_value_active
            PushStatus.WAITING -> R.string.cp_push_value_waiting
            PushStatus.FAILED -> R.string.cp_push_value_failed
            PushStatus.SERVER_UNSUPPORTED -> R.string.cp_push_value_unsupported
            PushStatus.NO_DISTRIBUTOR, PushStatus.NOT_SELECTED -> R.string.cp_push_value_off
        }))
        qualityRow.setValue(str(if (state.highQuality) R.string.cp_quality_high else R.string.cp_quality_low))
        languageRow.setValue(Languages.nativeName(Locales.language(context)))
        aboutRow.setValue(sheets.versionName())
    }

    override fun onShown() { shownKey = keyOf(host.state); bind(); sheets.refresh() }

    /** Only what this screen shows; most state updates (message counters, presence) do not concern it. */
    private data class Key(
        val number: String, val name: String, val link: app.line.Link, val online: Boolean, val configured: Boolean,
        val pushActive: Boolean, val highQuality: Boolean, val protocol: Int, val phase: app.line.Phase,
    )

    private fun keyOf(state: CallState) = Key(state.number, state.ownName, state.link, state.online, state.configReady,
        state.pushActive, state.highQuality, state.serverProtocol, state.phase)

    override fun onState(old: CallState, new: CallState) {
        if (!isBuilt) return
        val key = keyOf(new)
        if (key == shownKey) return
        shownKey = key
        bind()
        sheets.refresh()
    }
}
