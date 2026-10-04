package app.line.ui.screens

import android.view.View
import app.line.ui.Screen

class CallsScreen : Screen() {
    override val tab = "calls"
    override fun createView(): View = View(context)
}
