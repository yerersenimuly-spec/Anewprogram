package app.line.ui.screens

import app.line.R
import app.line.ui.Host
import app.line.ui.admin.AdminLogin
import app.line.ui.admin.AdminPanelScreen

/** Hidden entry to server administration: the secret code is checked by the server, never stored on the device. */
object AdminEntry {
    fun open(host: Host) {
        val service = host.service
        when {
            service == null -> host.toast(R.string.cp_admin_starting)
            !host.state.online -> host.toast(R.string.cp_admin_offline)
            service.isAdmin() -> host.push(AdminPanelScreen())
            else -> AdminLogin(host).show(service) { host.push(AdminPanelScreen()) }
        }
    }
}
