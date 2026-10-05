package app.line.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import app.line.CallService
import app.line.CallState
import kotlinx.coroutines.CoroutineScope

/** Everything a screen may ask of the activity that hosts it. */
interface Host {
    val activity: Activity
    val service: CallService?
    val state: CallState
    val uiScope: CoroutineScope

    /** Bottom system inset (navigation bar), for screens that draw edge to edge. */
    val bottomInset: Int

    fun push(screen: Screen)
    fun pop()
    fun selectTab(tab: String)
    fun toast(text: CharSequence)
    fun toast(res: Int)
    fun sheet(): Sheet

    /** Runs [block] on the UI scope; any failure is shown as a friendly message instead of crashing. */
    fun run(block: suspend () -> Unit)

    fun openConversation(peer: String, focusSequence: Long? = null)
    fun startCall(numbers: List<String>)
    fun acceptCall()
    fun hangUp()

    fun pickImage(onPicked: (Uri?) -> Unit)
    fun pickFile(onPicked: (Uri?) -> Unit)
    fun takePhoto(onTaken: (Uri?) -> Unit)
    fun withMicrophone(onGranted: () -> Unit)
    fun requestNotificationPermission()
    fun hideKeyboard()
    fun recreateWithLanguage()
    fun copyToClipboard(label: String, value: String)

    /** Leaves the call screen while the call continues; a return bar stays at the top. */
    fun minimizeCall()
}

/** One screen of the navigation stack. The view is built lazily, once. */
abstract class Screen {
    lateinit var host: Host
    private var built: View? = null

    val context get() = host.activity
    val view: View get() = built ?: createView().also { built = it }
    val isBuilt: Boolean get() = built != null

    protected abstract fun createView(): View

    /** Root tab id (`chats`, `calls`, `profile`) when this screen is a tab; the bottom navigation is shown only for those. */
    open val tab: String? = null

    /** True when the screen paints behind the status and navigation bars and handles insets itself. */
    open val edgeToEdge: Boolean = false

    /** Modal screens enter from the bottom instead of the side. */
    open val modal: Boolean = false

    /** True when the screen has a dark background in every theme, so system bar icons must be light. */
    open val darkChrome: Boolean = false

    open fun onState(old: CallState, new: CallState) {}
    open fun onServiceReady() {}
    open fun onShown() {}
    open fun onHidden() {}
    open fun onInsets(top: Int, bottom: Int) {}

    /** Return true when the back press was consumed. */
    open fun onBack(): Boolean = false
    open fun destroy() {}
}
