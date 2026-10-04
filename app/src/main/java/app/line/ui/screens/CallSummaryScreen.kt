package app.line.ui.screens

import android.view.View
import app.line.core.CallSummary
import app.line.ui.Screen

class CallSummaryScreen(private val summary: CallSummary) : Screen() {
    override val edgeToEdge = true
    override val modal = true
    override val darkChrome = true
    override fun createView(): View = View(context)
}
