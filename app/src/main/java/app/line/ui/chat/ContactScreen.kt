package app.line.ui.chat

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.R
import app.line.ui.AvatarView
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.ListRow
import app.line.ui.MATCH
import app.line.ui.Screen
import app.line.ui.TextStyle
import app.line.ui.TopBar
import app.line.ui.WRAP
import app.line.ui.card
import app.line.ui.color
import app.line.ui.column
import app.line.ui.divider
import app.line.ui.dp
import app.line.ui.icon
import app.line.ui.label
import app.line.ui.primaryButton
import app.line.ui.row
import app.line.ui.secondaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Details of a contact: name, number, safety code status and the actions that belong to a person rather than a message. */
class ContactScreen(private val peer: String, private val fromConversation: Boolean = false) : Screen() {
    private lateinit var content: LinearLayout
    private var verified: Boolean? = null

    override fun createView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(context.color(R.color.bg))
        root.addView(TopBar(context).back(context.getString(R.string.back)) { host.pop() }.setTitle(context.getString(R.string.contact_title)))
        content = context.column { setPadding(0, context.dp(8), 0, context.dp(32)) }
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        render()
        return root
    }

    override fun onShown() { loadTrust() }
    override fun onServiceReady() { loadTrust() }
    override fun onState(old: app.line.CallState, new: app.line.CallState) { if (isBuilt && old.profileVersion != new.profileVersion) render() }

    private fun loadTrust() {
        val service = host.service ?: return
        host.uiScope.launch {
            try { verified = service.verified(peer) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            render()
        }
    }

    private fun render() {
        if (!isBuilt) return
        val service = host.service
        val name = service.knownName(peer)
        val local = ContactNames.get(context, peer)
        val profile = service?.profileName(peer)
        content.removeAllViews()

        content.addView(context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(AvatarView(context).also { it.bind(peer, name) }, LinearLayout.LayoutParams(context.dp(96), context.dp(96)))
            addView(context.label(service?.displayName(peer) ?: peer.spacedNumber(), TextStyle.HEADLINE, maxLines = 2).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(16); marginStart = context.dp(24); marginEnd = context.dp(24) })
            if (name != null) addView(context.label(peer.spacedNumber(), TextStyle.CALLOUT, R.color.text_secondary),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
            addView(trustChip(), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(context.row {
            addView(context.primaryButton(context.getString(R.string.conv_call)) { host.startCall(listOf(peer)) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!fromConversation) addView(context.secondaryButton(context.getString(R.string.chat_open)) { host.pop(); host.openConversation(peer) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(24); marginStart = context.dp(16); marginEnd = context.dp(16) })

        val card = context.card()
        fun add(row: ListRow, last: Boolean = false) { card.addView(row); if (!last) card.addView(context.divider(60)) }
        add(ListRow(context).apply {
            title.text = context.getString(R.string.contact_number)
            subtitle(peer.spacedNumber())
            leading(context.icon("phone", R.color.text_secondary, 24))
            trailing(context.icon("copy", R.color.text_tertiary, 20))
            onClick { host.copyToClipboard(context.getString(R.string.contact_number), peer) }
        })
        if (profile != null && profile != local) add(ListRow(context).apply {
            title.text = context.getString(R.string.cv_contact_profile_name)
            subtitle(profile)
            leading(context.icon("person", R.color.text_secondary, 24))
            isClickable = false
        })
        add(ListRow(context).apply {
            title.text = context.getString(R.string.contact_security_title)
            subtitle(context.getString(if (verified == true) R.string.contact_verified else R.string.contact_not_verified))
            leading(context.icon("shield_check", if (verified == true) R.color.positive else R.color.text_secondary, 24))
            onClick { host.startVerification(peer) { loadTrust() } }
        })
        add(ListRow(context).apply {
            title.text = context.getString(R.string.conv_menu_rename)
            leading(context.icon("edit", R.color.text_secondary, 24))
            onClick { host.renameSheet(peer) { render() } }
        })
        add(ListRow(context).apply {
            title.text = context.getString(R.string.conv_menu_search)
            leading(context.icon("search", R.color.text_secondary, 24))
            onClick { host.push(SearchScreen(peer)) }
        })
        add(ListRow(context).apply {
            title.text = context.getString(R.string.conv_menu_clear)
            title.setTextColor(context.color(R.color.negative))
            leading(context.icon("trash", R.color.negative, 24))
            onClick { confirmClear() }
        }, last = true)
        content.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(24) })
    }

    private fun trustChip(): View {
        val ok = verified == true
        val text = context.getString(if (ok) R.string.contact_verified else R.string.contact_not_verified)
        val color = if (ok) R.color.positive else R.color.text_secondary
        return context.row {
            addView(context.icon(if (ok) "shield_check" else "alert", color, 16))
            addView(context.label(text, TextStyle.CAPTION_STRONG, color), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) })
            visibility = if (verified == null) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun confirmClear() {
        host.sheet().title(context.getString(R.string.conv_clear_title)).message(context.getString(R.string.conv_clear_body))
            .buttons(context.getString(R.string.conv_clear_confirm), destructive = true, secondary = context.getString(R.string.cancel)) {
                host.run { host.service?.clearConversation(peer) }
            }.show()
    }
}
