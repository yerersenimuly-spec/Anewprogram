package app.line.ui.screens

import android.view.View
import app.line.ui.Screen

class ConversationScreen(val peer: String, private val focusSequence: Long? = null) : Screen() {
    override fun createView(): View = View(context)
}
