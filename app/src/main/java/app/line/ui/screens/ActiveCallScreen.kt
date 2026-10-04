package app.line.ui.screens

import android.view.View
import app.line.ui.Screen

class ActiveCallScreen : Screen() {
    override val edgeToEdge = true
    override val darkChrome = true
    override fun createView(): View = View(context)
}
