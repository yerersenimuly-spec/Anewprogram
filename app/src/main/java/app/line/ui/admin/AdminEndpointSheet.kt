package app.line.ui.admin

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import app.line.DifferentServerException
import app.line.EndpointConfig
import app.line.Phase
import app.line.R
import app.line.ui.*
import app.line.ui.profile.BoundedScrollView

/** Manual endpoint settings, for an administrator who cannot hand out a connection code. */
object AdminEndpointSheet {
    fun show(host: Host) {
        val context: Context = host.activity
        val service = host.service ?: return
        if (host.state.phase != Phase.IDLE) { host.toast(R.string.cp_finish_call_first); return }
        val current = service.config()
        val body = context.column()
        fun field(hint: Int, value: String): LineField = LineField(context, context.getString(hint)).apply {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            edit.setSingleLine(false)
            edit.setText(value)
            body.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING); bottomMargin = context.dp(12)
            })
        }
        val api = field(R.string.cp_endpoint_api, current?.apiUrl.orEmpty())
        val apiPins = field(R.string.cp_endpoint_api_pins, current?.apiPins.orEmpty())
        val media = field(R.string.cp_endpoint_media, current?.mediaUrl.orEmpty())
        val mediaPins = field(R.string.cp_endpoint_media_pins, current?.mediaPins.orEmpty())
        val sheet = host.sheet().title(context.getString(R.string.cp_admin_endpoint))
        val save = context.primaryButton(context.getString(R.string.save)) {
            if (!service.isAdmin()) { host.toast(R.string.cp_admin_err_expired); return@primaryButton }
            val config = EndpointConfig(api.text().trim(), apiPins.text().trim(), media.text().trim(), mediaPins.text().trim())
            try {
                config.validate()
                service.configure(config, host.state.highQuality)
                sheet.dismiss()
                host.hideKeyboard()
            } catch (error: DifferentServerException) {
                api.setError(context.getString(R.string.err_different_server))
            } catch (error: Exception) {
                api.setError(context.getString(R.string.cp_endpoint_invalid))
            }
        }
        body.addView(save, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING) })
        sheet.content(BoundedScrollView(context, (context.resources.displayMetrics.heightPixels * 0.6f).toInt()).apply { addView(body) }).show()
    }
}
