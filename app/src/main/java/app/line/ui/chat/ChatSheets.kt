package app.line.ui.chat

import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.line.R
import app.line.media.attachments.ImageProcessor
import app.line.ui.Dimens
import app.line.ui.Host
import app.line.ui.IconView
import app.line.ui.LineField
import app.line.ui.MATCH
import app.line.ui.SheetAction
import app.line.ui.TextStyle
import app.line.ui.WRAP
import app.line.ui.column
import app.line.ui.dp
import app.line.ui.dpf
import app.line.ui.icon
import app.line.ui.label
import app.line.ui.roundRect
import app.line.ui.row
import app.line.ui.tintedIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Safety code of a contact: compare it with theirs, then confirm. [onResult] gets true when the user confirmed a match. */
internal fun Host.safetySheet(peer: String, code: String, verified: Boolean, onResult: (Boolean) -> Unit) {
    val context = activity
    val sheet = sheet().title(context.getString(R.string.contact_security_title))
    if (!verified) sheet.message(context.getString(R.string.contact_security_body))
    val card = context.label(SafetyCode.format(code), TextStyle.TITLE).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.04f
        setLineSpacing(dp(8).toFloat(), 1f)
        background = context.roundRect(R.color.surface_raised, Dimens.RADIUS_L)
        setPadding(dp(16), dp(20), dp(16), dp(20))
        contentDescription = code.chunked(5).joinToString(", ")
    }
    sheet.content(context.column {
        setPadding(dp(Dimens.SCREEN_PADDING), 0, dp(Dimens.SCREEN_PADDING), dp(16))
        if (verified) addView(context.label(context.getString(R.string.contact_verified), TextStyle.CAPTION_STRONG, R.color.positive),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    })
    var answered = false
    fun answer(value: Boolean) { if (!answered) { answered = true; onResult(value) } }
    if (verified) sheet.buttons(context.getString(R.string.done), secondary = null) { answer(false) }
    else sheet.buttons(context.getString(R.string.contact_match), secondary = context.getString(R.string.contact_later)) { answer(true) }
    sheet.onDismiss = { answer(false) }
    sheet.show()
}

/** Local name of a contact; an empty field returns to the public profile name or the number. */
internal fun Host.renameSheet(peer: String, onSaved: () -> Unit) {
    val context = activity
    val field = LineField(context, context.getString(R.string.conv_rename_hint)).singleLine(ContactNames.MAX_CODE_POINTS)
    field.edit.setText(ContactNames.get(context, peer))
    field.edit.setSelection(field.edit.text.length)
    sheet().title(context.getString(R.string.conv_rename_title))
        .content(context.column {
            setPadding(dp(Dimens.SCREEN_PADDING), dp(4), dp(Dimens.SCREEN_PADDING), dp(12))
            addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        })
        .buttons(context.getString(R.string.save), secondary = context.getString(R.string.cancel)) {
            ContactNames.save(context, peer, field.text())
            onSaved()
        }.show()
    field.edit.requestFocus()
    field.postDelayed({ context.getSystemService(InputMethodManager::class.java).showSoftInput(field.edit, InputMethodManager.SHOW_IMPLICIT) }, 220)
}

/** Photo from the gallery, a camera shot or any file. */
internal class AttachFlow(private val host: Host, private val peer: String) {
    private val context get() = host.activity

    fun open() {
        host.sheet().actions(listOf(
            SheetAction("image", context.getString(R.string.attach_photo)) { host.pickImage { it?.let(::previewPhoto) } },
            SheetAction("camera", context.getString(R.string.attach_camera)) { host.takePhoto { it?.let(::previewPhoto) } },
            SheetAction("file", context.getString(R.string.attach_file)) { host.pickFile { it?.let(::confirmFile) } },
        )).show()
    }

    private fun previewPhoto(uri: Uri) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(context.getColor(R.color.surface_raised))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dpf(Dimens.RADIUS_L.toFloat())) }
            }
            clipToOutline = true
        }
        val caption = LineField(context, context.getString(R.string.cv_caption_hint))
        caption.edit.apply {
            setSingleLine(false); maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(Utf8LengthFilter(1_000))
        }
        host.uiScope.launch {
            val bitmap = decodeUri(context, uri, 1024)
            if (bitmap != null) image.setImageBitmap(bitmap) else image.setImageDrawable(null)
        }
        host.sheet().title(context.getString(R.string.attach_send_photo))
            .content(context.column {
                setPadding(dp(Dimens.SCREEN_PADDING), dp(4), dp(Dimens.SCREEN_PADDING), dp(12))
                addView(image, LinearLayout.LayoutParams(MATCH, dp(260)))
                addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            })
            .buttons(context.getString(R.string.attach_send), secondary = context.getString(R.string.cancel)) {
                val text = caption.text().trim().ifEmpty { null }
                send { it.sendImage(peer, uri, text) }
            }.show()
    }

    private fun confirmFile(uri: Uri) {
        host.uiScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { ImageProcessor.fileInfo(context, uri) }.getOrNull() }
            if (info != null && ImageProcessor.isTooLarge(info.size)) { host.toast(R.string.media_too_large); return@launch }
            val format = ChatFormat(context)
            val tile = context.tintedIcon(FileKinds.icon(info?.mime.orEmpty()))
            host.sheet().title(context.getString(R.string.attach_send_file))
                .content(context.row {
                    setPadding(dp(Dimens.SCREEN_PADDING), dp(4), dp(Dimens.SCREEN_PADDING), dp(16))
                    addView(tile, LinearLayout.LayoutParams(dp(36), dp(36)))
                    addView(context.column {
                        addView(context.label(info?.name ?: context.getString(R.string.preview_file), TextStyle.BODY_STRONG, maxLines = 1))
                        if (info != null && info.size >= 0) addView(context.label(format.size(info.size), TextStyle.CAPTION, R.color.text_secondary),
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
                })
                .buttons(context.getString(R.string.attach_send), secondary = context.getString(R.string.cancel)) {
                    send { it.sendFile(peer, uri) }
                }.show()
        }
    }

    /** Sends a recorded voice message; the temporary recording is deleted once the service took it over. */
    fun sendVoice(result: app.line.media.attachments.VoiceRecorder.Result) {
        send(cleanup = { result.file.delete() }) { it.sendVoice(peer, result) }
    }

    private fun send(cleanup: (() -> Unit)? = null, block: suspend (app.line.CallService) -> Unit) {
        host.run {
            try {
                val service = host.service ?: error("Offline")
                if (!service.peerSupportsMedia(peer)) { host.toast(R.string.media_peer_old); return@run }
                block(service)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                host.toast(context.chatErrorText(e))
            } finally {
                cleanup?.invoke()
            }
        }
    }
}

/** Looks the peer up, shows the safety code and records the user's decision. [onChanged] runs after a successful confirmation. */
internal fun Host.startVerification(peer: String, onChanged: () -> Unit = {}) {
    run {
        val service = service ?: error("Offline")
        val code = service.inspectPeer(peer)
        val verified = service.verified(peer)
        safetySheet(peer, code, verified) { confirmed ->
            if (confirmed) run { service.verifyPeer(peer); onChanged() }
        }
    }
}
