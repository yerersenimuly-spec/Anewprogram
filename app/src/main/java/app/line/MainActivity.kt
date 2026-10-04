package app.line

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.line.admin.TripleTapGate
import app.line.core.NumberInput
import app.line.ui.*
import app.line.ui.screens.ActiveCallScreen
import app.line.ui.screens.AdminEntry
import app.line.ui.screens.CallSummaryScreen
import app.line.ui.screens.CallsScreen
import app.line.ui.screens.ChatsScreen
import app.line.ui.screens.ConversationScreen
import app.line.ui.screens.OnboardingScreen
import app.line.ui.screens.ProfileScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Single activity: owns the navigation stack, system insets, permissions and the binding to [CallService]. */
class MainActivity : ComponentActivity(), Host {
    override val activity: Activity get() = this
    override val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override var service: CallService? = null
        private set
    override var state = CallState()
        private set
    override var bottomInset = 0
        private set

    private val prefs by lazy { getSharedPreferences("line-ui", MODE_PRIVATE) }
    private val stack = ArrayList<Screen>()
    private val tabs = HashMap<String, Screen>()
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var banner: ConnectionBanner
    private lateinit var callBar: View
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNav
    private lateinit var toastLayer: FrameLayout
    private var bound = false
    private var topInset = 0
    private var navInset = 0
    private var imeInset = 0
    private var callDismissed = false
    private var shownSummary = ""
    private var pendingAccept = false
    private var unreadJob: kotlinx.coroutines.Job? = null
    private val adminTap = TripleTapGate()
    private val observer: (CallState) -> Unit = { render(it) }

    private var imageCallback: ((Uri?) -> Unit)? = null
    private var fileCallback: ((Uri?) -> Unit)? = null
    private var cameraCallback: ((Uri?) -> Unit)? = null
    private var cameraUri: Uri? = null
    private var micCallback: (() -> Unit)? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        imageCallback?.invoke(uri); imageCallback = null
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.invoke(uri); fileCallback = null
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        cameraCallback?.invoke(if (taken) cameraUri else null); cameraCallback = null
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = micCallback; micCallback = null
        if (granted) callback?.invoke() else toast(R.string.err_mic_required)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as CallService.LocalBinder).service
            service?.observe(observer)
            stack.toList().forEach { it.onServiceReady() }
            refreshBadge()
        }

        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            hideKeyboard()
            val top = stack.lastOrNull()
            when {
                top?.onBack() == true -> Unit
                stack.size > 1 -> pop()
                top?.tab != null && top.tab != "chats" -> selectTab("chats")
                else -> finish()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        buildChrome()
        onBackPressedDispatcher.addCallback(this, back)
        if (configured()) selectTab(savedInstanceState?.getString("tab") ?: "chats") else push(OnboardingScreen())
        savedInstanceState?.getString("peer")?.takeIf { it.matches(Regex("[0-9]{8}")) }?.let { openConversation(it) }
        handleIntent(intent)
    }

    private fun configured() = getSharedPreferences("line", MODE_PRIVATE).getString("endpoint", "").orEmpty().isNotEmpty()

    @Suppress("DEPRECATION")
    private fun enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or lightBarFlags()
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun isNight() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun lightBarFlags(): Int = if (isNight()) 0 else
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

    /** Status-bar icons follow the theme, except on screens that are always dark. */
    @Suppress("DEPRECATION")
    private fun applySystemBars(dark: Boolean) {
        val lightIcons = !dark && !isNight()
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightIcons) mask else 0, mask)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if (lightIcons) lightBarFlags() else 0)
        }
    }

    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = ConnectionBanner(this)
        callBar = label(getString(R.string.return_to_call), TextStyle.CALLOUT_STRONG, R.color.on_accent).apply {
            gravity = Gravity.CENTER
            background = accentGradient(0)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { callDismissed = false; push(ActiveCallScreen()); updateCallBar() }
        }
        content = FrameLayout(this)
        nav = BottomNav(this, listOf(
            NavItem("chats", "chat", R.string.nav_chats),
            NavItem("calls", "phone", R.string.nav_calls),
            NavItem("profile", "person", R.string.nav_profile),
        )) { id -> onTab(id) }
        toastLayer = FrameLayout(this)
        column.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(callBar, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(toastLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                topInset = bars.top; navInset = bars.bottom
                imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                run { topInset = insets.systemWindowInsetTop; navInset = insets.stableInsetBottom; imeInset = insets.systemWindowInsetBottom }
            }
            applyInsets()
            insets
        }
        setContentView(root)
    }

    private fun applyInsets() {
        val top = stack.lastOrNull()
        val edge = top?.edgeToEdge == true
        val keyboard = imeInset > navInset + dp(80)
        val showNav = top?.tab != null && !keyboard
        bottomInset = navInset
        nav.visibility = if (showNav) View.VISIBLE else View.GONE
        nav.setPadding(dp(8), dp(8), dp(8), dp(6) + navInset)
        column.setPadding(0, if (edge) 0 else topInset, 0, 0)
        content.setPadding(0, 0, 0, if (showNav || edge) 0 else maxOf(navInset, imeInset))
        toastLayer.setPadding(0, 0, 0, (if (showNav) dp(Dimens.NAV_HEIGHT) + navInset else maxOf(navInset, imeInset)) + dp(20))
        banner.visibility = if (edge) View.GONE else banner.visibility
        top?.takeIf { it.edgeToEdge }?.onInsets(topInset, maxOf(navInset, imeInset))
        updateCallBar()
    }

    // ---- navigation -----------------------------------------------------------------------------------

    private fun tabScreen(id: String): Screen = tabs.getOrPut(id) {
        when (id) {
            "calls" -> CallsScreen()
            "profile" -> ProfileScreen()
            else -> ChatsScreen()
        }.also { it.host = this }
    }

    override fun selectTab(tab: String) {
        val target = tabScreen(tab)
        if (stack.size == 1 && stack[0] === target) return
        hideKeyboard()
        stack.toList().forEach { screen ->
            if (screen !== target) {
                screen.onHidden()
                content.removeView(screen.view)
                if (screen.tab == null) screen.destroy()
            }
        }
        stack.clear()
        stack.add(target)
        if (target.view.parent == null) content.addView(target.view, FrameLayout.LayoutParams(MATCH, MATCH))
        target.view.visibility = View.VISIBLE
        target.view.alpha = 0f
        target.view.animate().alpha(1f).setDuration(140).start()
        nav.select(tab)
        afterStackChange()
        target.onShown()
    }

    override fun push(screen: Screen) {
        screen.host = this
        hideKeyboard()
        val previous = stack.lastOrNull()
        stack.add(screen)
        val view = screen.view
        if (view.parent == null) content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        view.visibility = View.VISIBLE
        if (previous == null) view.alpha = 1f else enter(view, screen.modal)
        previous?.let {
            it.onHidden()
            view.postDelayed({ if (stack.lastOrNull() !== it) it.view.visibility = View.GONE }, 260)
        }
        afterStackChange()
        screen.onShown()
    }

    override fun pop() {
        if (stack.size <= 1) return
        hideKeyboard()
        val leaving = stack.removeAt(stack.lastIndex)
        leaving.onHidden()
        val next = stack.last()
        next.view.visibility = View.VISIBLE
        val view = leaving.view
        leave(view, leaving.modal) {
            content.removeView(view)
            if (leaving.tab == null) leaving.destroy()
        }
        afterStackChange()
        next.onShown()
    }

    private fun removeFromStack(predicate: (Screen) -> Boolean) {
        if (stack.lastOrNull()?.let(predicate) == true) { pop(); return }
        stack.filter(predicate).forEach { screen ->
            stack.remove(screen)
            content.removeView(screen.view)
            screen.destroy()
        }
        afterStackChange()
    }

    private fun enter(view: View, modal: Boolean) {
        view.alpha = 0f
        if (modal) view.translationY = dpf(56f) else view.translationX = dpf(36f)
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
    }

    private fun leave(view: View, modal: Boolean, end: () -> Unit) {
        view.animate().alpha(0f).translationX(if (modal) 0f else dpf(36f)).translationY(if (modal) dpf(56f) else 0f).setDuration(170)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { view.translationX = 0f; view.translationY = 0f; view.alpha = 1f; end() }.start()
    }

    private fun afterStackChange() {
        val top = stack.lastOrNull()
        top?.tab?.let { nav.select(it) }
        applySystemBars(top?.darkChrome == true)
        applyInsets()
    }

    private fun onTab(id: String) {
        selectTab(id)
        if (id == "profile" && adminTap.tap(SystemClock.elapsedRealtime())) AdminEntry.open(this) else if (id != "profile") adminTap.reset()
    }

    override fun openConversation(peer: String, focusSequence: Long?) {
        val top = stack.lastOrNull()
        if (top is ConversationScreen && top.peer == peer && focusSequence == null) return
        if (stack.firstOrNull()?.tab != "chats") selectTab("chats")
        removeFromStack { it is ActiveCallScreen || it is CallSummaryScreen }
        push(ConversationScreen(peer, focusSequence))
    }

    // ---- Host: actions ----------------------------------------------------------------------------------

    override fun startCall(numbers: List<String>) {
        val own = state.number
        val valid = NumberInput.list(numbers.joinToString(","), own, state.maxParticipants - 1)
        if (valid !is NumberInput.Result.Valid) {
            toast(if ((valid as NumberInput.Result.Invalid).problem == NumberInput.Problem.OWN) R.string.err_own_number else R.string.err_number_format)
            return
        }
        if (!state.callsEnabled) { toast(R.string.notice_calls_disabled); return }
        withMicrophone {
            requestNotificationPermission()
            startForegroundService(Intent(this, CallService::class.java).setAction("dial").putStringArrayListExtra("members", ArrayList(valid.numbers)))
        }
    }

    override fun acceptCall() {
        withMicrophone { startForegroundService(Intent(this, CallService::class.java).setAction("accept")) }
    }

    override fun hangUp() { service?.hangup() }

    override fun minimizeCall() {
        callDismissed = true
        removeFromStack { it is ActiveCallScreen }
        updateCallBar()
    }

    override fun run(block: suspend () -> Unit) {
        uiScope.launch {
            try { block() }
            catch (_: CancellationException) { }
            catch (error: Exception) { toast(errorText(error)) }
        }
    }

    override fun sheet(): Sheet = Sheet(this, navInset)

    override fun toast(res: Int) = toast(getString(res))

    override fun toast(text: CharSequence) {
        toastLayer.removeAllViews()
        val pill = label(text, TextStyle.CALLOUT_STRONG, R.color.bg).apply {
            gravity = Gravity.CENTER
            background = roundRect(R.color.text_primary, 14)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 3
        }
        toastLayer.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = dp(24); marginEnd = dp(24)
        })
        pill.alpha = 0f; pill.translationY = dpf(12f)
        pill.animate().alpha(1f).translationY(0f).setDuration(180).start()
        pill.postDelayed({
            pill.animate().alpha(0f).setDuration(220).withEndAction { toastLayer.removeView(pill) }.start()
        }, 2800)
    }

    override fun pickImage(onPicked: (Uri?) -> Unit) {
        imageCallback = onPicked
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun pickFile(onPicked: (Uri?) -> Unit) {
        fileCallback = onPicked
        filePicker.launch(arrayOf("*/*"))
    }

    override fun takePhoto(onTaken: (Uri?) -> Unit) {
        val directory = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraUri = uri
        cameraCallback = onTaken
        camera.launch(uri)
    }

    override fun withMicrophone(onGranted: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { onGranted(); return }
        micCallback = onGranted
        microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        root.requestFocus()
    }

    override fun recreateWithLanguage() = recreate()

    override fun copyToClipboard(label: String, value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        toast(R.string.copied)
    }

    // ---- state ----------------------------------------------------------------------------------------

    private fun render(new: CallState) {
        val old = state
        state = new
        banner.bind(new.link, new.pending, configured())
        if (stack.lastOrNull()?.edgeToEdge == true) banner.visibility = View.GONE
        updateCallBar()
        if (old.noticeVersion != new.noticeVersion) showNotice(new.notice)
        syncCallScreens(old, new)
        playCallSounds(old, new)
        stack.toList().forEach { it.onState(old, new) }
        if (old.chatVersion != new.chatVersion || old.heldSenders != new.heldSenders) refreshBadge()
        if (!old.online && new.online && new.number.isNotEmpty() && stack.firstOrNull()?.tab != null) requestNotificationPermission()
        applyLockScreenFlags(new.phase == Phase.INCOMING)
        if (pendingAccept && new.phase == Phase.INCOMING) { pendingAccept = false; acceptCall() }
    }

    private fun showNotice(notice: Notice) {
        val text = when (notice) {
            Notice.NONE, Notice.CALL_ENDED -> return
            Notice.CALL_DECLINED -> R.string.notice_call_declined
            Notice.CALL_NO_ANSWER -> R.string.notice_call_no_answer
            Notice.CALL_NETWORK_LOST -> R.string.notice_call_network_lost
            Notice.CALL_MEDIA_LOST -> R.string.notice_call_media_lost
            Notice.CALL_MEDIA_FAILED -> R.string.notice_call_media_failed
            Notice.CALL_SECURE_FAILED -> R.string.notice_call_secure_failed
            Notice.CALL_PROTOCOL -> R.string.notice_call_protocol
            Notice.CALL_UNAVAILABLE -> R.string.notice_call_unavailable
            Notice.CALLS_DISABLED -> R.string.notice_calls_disabled
            Notice.MIC_REQUIRED -> R.string.notice_mic_required
            Notice.REPLACED -> R.string.notice_replaced
            Notice.MESSAGE_REJECTED -> R.string.notice_message_rejected
            Notice.SERVER_MESSAGE_REJECTED -> R.string.notice_server_message_rejected
            Notice.OPERATION_FAILED -> R.string.notice_operation_failed
        }
        toast(text)
    }

    private fun syncCallScreens(old: CallState, new: CallState) {
        val inCall = new.phase != Phase.IDLE
        val onScreen = stack.any { it is ActiveCallScreen }
        if (inCall) {
            if (old.phase == Phase.IDLE) callDismissed = false
            if (!onScreen && !callDismissed) push(ActiveCallScreen())
        } else {
            callDismissed = false
            if (onScreen) removeFromStack { it is ActiveCallScreen }
            val summary = new.lastCall
            if (summary != null && summary.id != shownSummary && System.currentTimeMillis() - summary.endedAt < 120_000) {
                shownSummary = summary.id
                push(CallSummaryScreen(summary))
            }
        }
    }

    private fun playCallSounds(old: CallState, new: CallState) {
        if (old.phase != Phase.CONNECTED && new.phase == Phase.CONNECTED) UiSounds.play(this, UiCue.CALL_CONNECTED)
        else if (old.phase != Phase.IDLE && new.phase == Phase.IDLE && old.connectedAt > 0) UiSounds.play(this, UiCue.CALL_ENDED)
    }

    private fun updateCallBar() {
        val show = state.phase != Phase.IDLE && callDismissed && stack.lastOrNull()?.edgeToEdge != true
        callBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun applyLockScreenFlags(ringing: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(ringing); setTurnScreenOn(ringing) }
        else if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun refreshBadge() {
        val s = service ?: return
        unreadJob?.cancel()
        unreadJob = uiScope.launch {
            val unread = runCatching { s.unreadCounts().values.sum() }.getOrDefault(0)
            nav.badge("chats", unread + state.heldSenders)
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val peer = intent.getStringExtra(AppNotifications.EXTRA_PEER)
        val destination = intent.getStringExtra(AppNotifications.EXTRA_DESTINATION)
        when {
            peer != null && peer.matches(Regex("[0-9]{8}")) -> openConversation(peer)
            destination == "calls" -> {
                if (intent.getBooleanExtra(AppNotifications.EXTRA_ACCEPT_CALL, false)) {
                    if (state.phase == Phase.INCOMING) acceptCall() else pendingAccept = true
                } else if (state.phase == Phase.IDLE) selectTab("calls")
            }
        }
        intent.removeExtra(AppNotifications.EXTRA_PEER); intent.removeExtra(AppNotifications.EXTRA_DESTINATION)
        intent.removeExtra(AppNotifications.EXTRA_ACCEPT_CALL)
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onStart() {
        super.onStart()
        CallService.ensureRunning(this)
        bound = bindService(Intent(this, CallService::class.java), connection, BIND_AUTO_CREATE)
        stack.lastOrNull()?.onShown()
    }

    override fun onResume() { super.onResume(); RefreshPolicy.apply(this, root) }
    override fun onPause() { RefreshPolicy.clear(this); super.onPause() }

    override fun onStop() {
        stack.lastOrNull()?.onHidden()
        service?.removeObserver(observer)
        service?.lockAdmin()
        adminTap.reset()
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stack.firstOrNull()?.tab?.let { outState.putString("tab", it) }
        (stack.lastOrNull() as? ConversationScreen)?.let { outState.putString("peer", it.peer) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stack.forEach { it.destroy() }
        tabs.values.forEach { it.destroy() }
        uiScope.cancel()
        super.onDestroy()
    }
}
