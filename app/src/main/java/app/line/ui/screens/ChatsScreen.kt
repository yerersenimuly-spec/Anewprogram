package app.line.ui.screens

import android.view.View
import app.line.ui.Screen

class ChatsScreen : Screen() {
    override val tab = "chats"
    override fun createView(): View = View(context)
}
