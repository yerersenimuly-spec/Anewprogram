package app.line.ui.screens

import android.view.View
import app.line.ui.Screen

class ProfileScreen : Screen() {
    override val tab = "profile"
    override fun createView(): View = View(context)
}
