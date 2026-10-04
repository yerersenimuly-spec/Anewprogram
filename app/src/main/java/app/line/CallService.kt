package app.line

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import app.line.crypto.ChatMessage
import app.line.crypto.SecureStore
import app.line.media.CallMediaEngine
import app.line.media.LiveCallEngine
import app.line.core.CallSummary
import app.line.core.DisplayName
import app.line.core.MessageStatus
import app.line.core.ReconnectPolicy
import app.line.core.Receipts
import app.line.core.SendFailure
import app.line.core.ServerInfo
import app.line.crypto.Conversation
import app.line.push.PushRegistrar
import app.line.media.MediaEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** Identity, number and trust are bound to one server; switching servers needs a fresh installation. */
class DifferentServerException : IllegalStateException()

class CallService : Service() {
    private data class CallAttempt(
        val id: String,
        val peer: String,
        val incoming: Boolean,
        val startedAt: Long,
        val connectedAt: Long = 0,
        val eventRecorded: Boolean = false,
    )

    inner class LocalBinder : Binder() { val service get() = this@CallService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableSetOf<(CallState) -> Unit>()
    private val prefs by lazy { getSharedPreferences("line", MODE_PRIVATE) }
    private val secure by lazy { scope.async(Dispatchers.IO) { SecureStore(this@CallService) } }
    internal var publicBundleProvider: suspend () -> JSONObject = { db { it.publicBundle() } }
    internal var httpClientFactory: (EndpointConfig) -> OkHttpClient = { it.http() }
    private var http: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var generation = 0
    private var inboxReady = false
    private var registrationHandshake: RegistrationHandshake? = null
    private var retryJob: Job? = null
    private val lookups = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val adminRequests = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var adminExpiresAt = 0L
    private var adminEpoch = 0
    private val incoming = Channel<Pair<Int, JSONObject>>(64)
    private val chatEnvelopeIds = mutableSetOf<String>()
    private val sessionPreparation = Mutex()
    private val chatSending = Mutex()
    private var callId = ""
    private var callRoom = ""
    private var owner = ""
    private var callAttempt: CallAttempt? = null
    private var roomKey: ByteArray? = null
    private val pendingKeys = mutableMapOf<String, JSONObject>()
    internal var mediaEngineFactory: (Context, CoroutineScope, (MediaEvent) -> Unit) -> CallMediaEngine =
        { context, engineScope, onEvent -> LiveCallEngine(context, engineScope, onEvent) }
    private var engine: CallMediaEngine? = null
    private var callOperation: Job? = null
    private var callOperationEpoch = 0
    private var foreground = false
    private val reconnect = ReconnectPolicy()
    private var httpConfig: EndpointConfig? = null
    private var connectedNetwork: Network? = null
    private var socketOpening = false
    private val inFlight = mutableSetOf<String>()
    private var ackWatchdog: Job? = null
    private var flushRetry: Job? = null
    private var networkGrace: Job? = null
    private var resumeWatch: Job? = null
    private var viewingPeer: String? = null
    private var profiles: Map<String, String> = emptyMap()
    private val profileRequests = mutableMapOf<String, List<String>>()
    private val outboxFlush = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var callTimeout: Job? = null
    private var registrationTimeout: Job? = null
    private val network by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            scope.launch {
                networkGrace?.cancel(); networkGrace = null
                if (config() == null) return@launch
                if (!state.online) {
                    if (!socketOpening) { retryJob?.cancel(); reconnect.reset(); connect() }
                } else if (connectedNetwork != null && connectedNetwork != available) {
                    connect()
                }
            }
        }

        override fun onLost(lost: Network) {
            scope.launch {
                delay(400)
                if (hasInternet()) return@launch
                if (config() != null) update(state.copy(link = Link.WAITING_NETWORK))
                if (state.phase != Phase.IDLE && networkGrace == null) {
                    networkGrace = launch {
                        delay(CALL_NETWORK_GRACE_MS)
                        networkGrace = null
                        if (!hasInternet() && state.phase != Phase.IDLE) finish(Notice.CALL_NETWORK_LOST, outcome = "failed")
                    }
                }
            }
        }
    }
    var state = CallState()
        private set
    internal val callOperationActive: Boolean get() = callOperation?.isActive == true

    override fun onCreate() {
        super.onCreate()
        AppNotifications.createChannels(this)
        state = state.copy(number = prefs.getString("number", "") ?: "", configReady = config() != null,
            highQuality = prefs.getBoolean("high_quality", true))
        network.registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            for ((epoch, message) in incoming) {
                if (epoch != generation) continue
                try { receive(message) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (message.optString("type") == "envelope") {
                        update(state.copy(notice = Notice.MESSAGE_REJECTED, noticeVersion = state.noticeVersion + 1))
                    } else if (state.phase != Phase.IDLE || foreground) finish(Notice.CALL_PROTOCOL)
                    else update(state.copy(notice = Notice.SERVER_MESSAGE_REJECTED, noticeVersion = state.noticeVersion + 1))
                }
            }
        }
        val initialGeneration = generation
        work {
            secure.await()
            loadProfileCache()
            update(state.copy(pending = db { it.pendingMessageCount() }))
            if (generation == initialGeneration) connect()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    fun observe(listener: (CallState) -> Unit) { listeners.add(listener); listener(state) }
    fun removeObserver(listener: (CallState) -> Unit) { listeners.remove(listener) }

    private var lastNotificationKey = ""

    private fun update(value: CallState) {
        state = value
        listeners.toList().forEach { it(value) }
        if (foreground) {
            val key = "${value.phase}|${value.muted}|${value.link}|${value.pending}|${value.members.size}|${value.connectedAt > 0}"
            if (key != lastNotificationKey) {
                lastNotificationKey = key
                getSystemService(NotificationManager::class.java).notify(AppNotifications.ACTIVE_CALL_ID, notification())
            }
        }
    }

    private fun work(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (callAttempt != null) finish(Notice.CALL_SECURE_FAILED, outcome = "failed")
            else if (state.phase != Phase.IDLE || foreground) finish(Notice.CALL_SECURE_FAILED)
            else update(state.copy(notice = Notice.OPERATION_FAILED, noticeVersion = state.noticeVersion + 1))
        }
    }

    private suspend fun <T> db(block: (SecureStore) -> T): T {
        val store = secure.await()
        return withContext(Dispatchers.IO) { block(store) }
    }

    fun config(): EndpointConfig? = runCatching {
        EndpointConfig(prefs.getString("endpoint", "") ?: "", prefs.getString("api_pins", "") ?: "",
            prefs.getString("media_endpoint", "") ?: "", prefs.getString("media_pins", "") ?: "").also { it.validate() }
    }.getOrNull()

    fun configure(value: EndpointConfig, highQuality: Boolean) {
        if (state.phase != Phase.IDLE) return
        value.validate()
        val old = config()
        if (old != null && old.apiUrl != value.apiUrl) throw DifferentServerException()
        generation++
        cancelLookups("Connection settings changed")
        prefs.edit().putString("endpoint", value.apiUrl).putString("api_pins", value.apiPins)
            .putString("media_endpoint", value.mediaUrl).putString("media_pins", value.mediaPins)
            .putBoolean("high_quality", highQuality).apply()
        update(state.copy(configReady = true, highQuality = highQuality))
        work { connect() }
    }

    fun reconnectNow() { if (state.phase == Phase.IDLE || !state.online) work { retryJob?.cancel(); reconnect.reset(); connect() } }
    fun inboxSynchronized(): Boolean = state.online && inboxReady

    fun setQuality(highQuality: Boolean) {
        if (state.phase != Phase.IDLE) return
        prefs.edit().putBoolean("high_quality", highQuality).apply()
        update(state.copy(highQuality = highQuality))
    }

    fun isAdmin(): Boolean = state.online && System.currentTimeMillis() < adminExpiresAt

    suspend fun adminLogin(code: String): JSONObject {
        require(code.length in 12..128 && state.online) { "Подключитесь и введите секретный код" }
        val epoch = adminEpoch
        val result = adminRequest("admin_login", JSONObject().put("code", code))
        if (epoch != adminEpoch) {
            send("admin", JSONObject().put("requestId", UUID.randomUUID().toString()).put("action", "logout"))
            error("Вход отменён")
        }
        val expires = result.optLong("expiresAt")
        check(expires > System.currentTimeMillis()) { "Срок сессии истёк" }
        adminExpiresAt = minOf(expires, System.currentTimeMillis() + 300_000)
        return result
    }

    suspend fun adminCommand(action: String, extra: JSONObject = JSONObject()): JSONObject {
        check(isAdmin()) { "Войдите в админ-панель заново" }
        val response = adminRequest("admin", extra.put("action", action))
        return response.optJSONObject("result") ?: JSONObject()
    }

    fun lockAdmin() {
        adminEpoch++
        if (adminExpiresAt > 0 || adminRequests.isNotEmpty()) send("admin", JSONObject().put("requestId", UUID.randomUUID().toString()).put("action", "logout"))
        adminExpiresAt = 0
        adminRequests.values.forEach { it.completeExceptionally(IllegalStateException("Админ-сессия закрыта")) }
        adminRequests.clear()
    }

    private suspend fun adminRequest(type: String, payload: JSONObject): JSONObject {
        require(state.online)
        val id = UUID.randomUUID().toString()
        val request = CompletableDeferred<JSONObject>()
        adminRequests[id] = request
        try {
            check(send(type, payload.put("requestId", id)))
            val response = withTimeout(15_000) { request.await() }
            if (!response.optBoolean("ok")) {
                if (response.optString("error") in setOf("unauthorized", "expired", "invalid_credentials", "admin_disabled")) adminExpiresAt = 0
                error(when (response.optString("error")) {
                    "admin_disabled" -> "Админ-доступ не настроен на сервере"
                    "rate_limited" -> "Слишком много попыток. Подождите минуту"
                    "invalid_credentials" -> "Неверный секретный код"
                    "unauthorized", "expired" -> "Войдите в админ-панель заново"
                    else -> "Сервер отклонил действие"
                })
            }
            return response
        } finally { adminRequests.remove(id) }
    }

    private fun token(): String = prefs.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }.also { prefs.edit().putString("token", it).commit() }

    private fun cancelLookups(reason: String) {
        lookups.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        lookups.clear()
    }

    private fun launchCallOperation(phase: Phase, expectedCallId: String, block: suspend (Int, String) -> Unit) {
        callOperation?.cancel()
        val epoch = ++callOperationEpoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = currentCoroutineContext()[Job]
            try {
                checkCallOperation(epoch, phase, expectedCallId)
                block(epoch, expectedCallId)
            } catch (timeout: TimeoutCancellationException) {
                if (isCallOperationCurrent(epoch, phase, expectedCallId)) {
                    finish(Notice.CALL_NO_ANSWER)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (callOperationEpoch == epoch && callId == expectedCallId && state.phase != Phase.IDLE) {
                    finish(Notice.CALL_SECURE_FAILED)
                }
            } finally {
                if (callOperation === current) callOperation = null
            }
        }
        callOperation = job
        job.start()
    }

    private fun isCallOperationCurrent(epoch: Int, phase: Phase, expectedCallId: String): Boolean =
        callOperationEpoch == epoch && callId == expectedCallId && state.phase == phase

    private suspend fun checkCallOperation(epoch: Int, phase: Phase, expectedCallId: String) {
        currentCoroutineContext().ensureActive()
        check(isCallOperationCurrent(epoch, phase, expectedCallId)) { "Call operation is no longer current" }
    }

    private suspend fun checkMediaCallOperation(epoch: Int, expectedCallId: String) {
        currentCoroutineContext().ensureActive()
        check(callOperationEpoch == epoch && callId == expectedCallId &&
            state.phase in setOf(Phase.CONNECTING, Phase.CONNECTED)) { "Call operation is no longer current" }
    }

    private fun clientFor(endpoints: EndpointConfig): OkHttpClient {
        val existing = http
        if (existing != null && httpConfig == endpoints) {
            existing.connectionPool.evictAll()
            return existing
        }
        existing?.dispatcher?.executorService?.shutdown()
        existing?.connectionPool?.evictAll()
        return httpClientFactory(endpoints).also { http = it; httpConfig = endpoints }
    }

    private fun hasInternet(): Boolean {
        val active = network.activeNetwork ?: return false
        return network.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private suspend fun connect() {
        inboxReady = false
        retryJob?.cancel()
        registrationTimeout?.cancel()
        val epoch = ++generation
        lockAdmin()
        cancelLookups("Connection changed")
        socket?.cancel()
        socket = null
        inFlight.clear()
        ackWatchdog?.cancel()
        val endpoints = config() ?: run {
            socketOpening = false
            update(state.copy(online = false, mediaReady = false, configReady = false, link = Link.NONE))
            return
        }
        val bundle = publicBundleProvider()
        if (epoch != generation) return
        val handshake = RegistrationHandshake(token(), bundle)
        registrationHandshake = handshake
        val client = clientFor(endpoints)
        connectedNetwork = network.activeNetwork
        socketOpening = true
        update(state.copy(online = false, mediaReady = false, serverProtocol = 0,
            link = if (hasInternet()) Link.CONNECTING else Link.WAITING_NETWORK))
        socket = client.newWebSocket(Request.Builder().url(endpoints.apiUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch { if (epoch == generation) {
                    ws.send(handshake.packet().toString())
                    registrationTimeout = scope.launch { delay(REGISTRATION_TIMEOUT_MS); if (!state.online) disconnected(epoch) }
                } }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    if (epoch != generation) return@launch
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                    val retry = handshake.retryForLegacy(message)
                    if (retry != null) {
                        registrationTimeout?.cancel()
                        if (!ws.send(retry.toString())) { disconnected(epoch); return@launch }
                        registrationTimeout = scope.launch { delay(REGISTRATION_TIMEOUT_MS); if (!state.online) disconnected(epoch) }
                        return@launch
                    }
                    val requestId = message.optString("requestId")
                    if (requestId.isNotEmpty() && message.optString("type") == "admin_result") {
                        adminRequests.remove(requestId)?.complete(message)
                    } else if (requestId.isNotEmpty() && message.optString("type") == "bundle") {
                        val pending = lookups.remove(requestId)
                        val bundle = message.optJSONObject("bundle")
                        message.optJSONObject("profile")?.let { rememberProfile(message.optString("peer"), it) }
                        if (bundle != null) pending?.complete(bundle)
                        else pending?.completeExceptionally(IllegalStateException("Invalid bundle"))
                    } else if (requestId.isNotEmpty() && message.optString("type") == "error" && lookups.containsKey(requestId)) {
                        lookups.remove(requestId)?.completeExceptionally(IllegalStateException(message.optString("code")))
                    } else if (!incoming.trySend(epoch to message).isSuccess) disconnected(epoch)
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { scope.launch { disconnected(epoch) } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { scope.launch { disconnected(epoch) } }
        })
    }
    private suspend fun disconnected(epoch: Int) {
        if (epoch != generation) return
        generation++
        lockAdmin()
        registrationTimeout?.cancel()
        socket?.cancel(); socket = null
        socketOpening = false
        cancelLookups("Offline")
        inFlight.clear(); ackWatchdog?.cancel()
        // A connected call keeps its media session; the server holds the seat for a short grace period (protocol 8).
        val keepCall = state.phase == Phase.CONNECTED && state.server.protocol >= 8
        if (state.phase != Phase.IDLE && !keepCall) finish(Notice.CALL_NETWORK_LOST, notifyServer = false, outcome = "failed")
        val internet = hasInternet()
        update(state.copy(online = false, mediaReady = false, signalingLost = keepCall,
            link = if (internet) Link.CONNECTING else Link.WAITING_NETWORK))
        retryJob?.cancel()
        if (internet) retryJob = scope.launch { delay(reconnect.nextDelayMs()); connect() }
    }
    private fun send(type: String, extra: JSONObject = JSONObject()): Boolean =
        socket?.send(extra.put("type", type).toString()) == true

    private suspend fun lookup(number: String, consumePreKey: Boolean = true): JSONObject {
        require(number.matches(Regex("[0-9]{8}")) && number != state.number && state.online)
        val id = UUID.randomUUID().toString()
        val pending = CompletableDeferred<JSONObject>()
        lookups[id] = pending
        try {
            check(send("lookup", JSONObject().put("to", number).put("requestId", id).put("consumePreKey", consumePreKey)))
            return withTimeout(15_000) { pending.await() }
        } finally { lookups.remove(id) }
    }

    suspend fun inspectPeer(number: String): String {
        val bundle = lookup(number, consumePreKey = false)
        return db { it.rememberPeer(number, bundle); it.safetyCode(number) }
    }

    suspend fun verified(number: String): Boolean = db { it.isVerified(number) }
    suspend fun verifyPeer(number: String) {
        db { it.verifyPeer(number) }
        releaseHeldSender(number)
        if (state.serverProtocol >= 7) { inboxReady = false; send("inbox_sync") }
    }
    suspend fun messages(number: String, before: Long? = null): List<ChatMessage> = db { it.messages(number, before, 40) }
    suspend fun searchMessages(query: String, peer: String? = null, before: Long? = null): List<ChatMessage> =
        db { it.searchMessages(query, peer, before, 40) }
    suspend fun conversations(before: Long? = null): List<Conversation> = db { it.conversations(before, 40) }
    suspend fun activityEvents(callsOnly: Boolean = false, before: Long? = null): List<ActivityEvent> =
        db { it.activityEvents(callsOnly, before, 40, incomingOnly = !callsOnly) }

    fun clearMessageNotifications(peer: String) = AppNotifications.cancelConversation(this, peer)
    suspend fun deleteMessage(id: String) {
        db { it.deleteMessage(id) }
        inFlight.remove(id)
        update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
    }
    suspend fun clearConversation(peer: String) {
        db { it.clearConversation(peer) }
        AppNotifications.cancelConversation(this, peer)
        update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
    }

    /** A lookup is only needed to create the first session; later messages are encrypted offline. */
    private suspend fun preparePeer(number: String) = sessionPreparation.withLock {
        if (db { it.hasSession(number) }) {
            check(db { it.isVerified(number) }) { "SAS must be verified" }
            return@withLock
        }
        val bundle = lookup(number, consumePreKey = true)
        db {
            it.rememberPeer(number, bundle)
            check(it.isVerified(number)) { "SAS must be verified" }
            if (!it.hasSession(number)) it.establishSession(number, bundle)
        }
    }

    /** Offline-first: the message is encrypted and stored first; delivery to the server happens when a connection exists. */
    suspend fun sendChat(number: String, text: String) = chatSending.withLock {
        check(state.chatEnabled) { "chat disabled" }
        require(text.isNotBlank() && text.toByteArray().size <= 4_096)
        preparePeer(number)
        val id = UUID.randomUUID().toString()
        val payload = Receipts.chat(id, text).toString().toByteArray()
        db { it.encryptAndQueue(number, id, payload, text) }
        update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
        flushOutbox()
    }

    fun displayName(peer: String): String =
        getSharedPreferences("line-ui", MODE_PRIVATE).getString("contact-$peer", null)?.takeIf { it.isNotBlank() }
            ?: profiles[peer] ?: peer.chunked(4).joinToString(" ")

    fun profileName(peer: String): String? = profiles[peer]

    /** The conversation the user is looking at: incoming messages there are read immediately and not notified. */
    fun setViewing(peer: String?) {
        viewingPeer = peer
        if (peer != null) work { markRead(peer) }
    }

    suspend fun markRead(peer: String) {
        val ids = db { it.markRead(peer) }
        AppNotifications.cancelConversation(this, peer)
        if (ids.isEmpty()) return
        if (Settings.readReceipts(this) && db { it.queueReadReceipt(peer, ids) } != null) flushOutbox()
        update(state.copy(chatVersion = state.chatVersion + 1))
    }

    suspend fun retryMessage(id: String) {
        if (db { it.retryMessage(id) }) {
            update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
            flushOutbox()
        }
    }

    suspend fun unreadCounts(): Map<String, Int> = db { it.unreadCounts() }
    fun heldSenders(): Set<String> = prefs.getStringSet("held_senders", emptySet()).orEmpty()

    private fun holdSender(peer: String) {
        if (peer in heldSenders()) return
        prefs.edit().putStringSet("held_senders", heldSenders() + peer).apply()
        update(state.copy(heldSenders = heldSenders().size, chatVersion = state.chatVersion + 1))
        AppNotifications.showHeldSender(this, peer, displayName(peer))
    }

    private fun releaseHeldSender(peer: String) {
        if (peer !in heldSenders()) return
        prefs.edit().putStringSet("held_senders", heldSenders() - peer).apply()
        update(state.copy(heldSenders = heldSenders().size, chatVersion = state.chatVersion + 1))
    }

    /** Sends every message and receipt that the server has not yet accepted, oldest first, once per connection. */
    private suspend fun flushOutbox() = outboxFlush.withLock {
        flushRetry?.cancel()
        if (!state.online || !state.chatEnabled) return@withLock
        val items = db { it.pendingOutbox() }
        for (item in items) {
            if (item.id in inFlight) continue
            val packet = JSONObject().put("to", item.peer).put("id", item.id).put("cipherType", item.cipherType).put("body", item.body)
            if (item.receipt && state.server.supportsSilentReceipts) packet.put("silent", true)
            if (!send("envelope", packet)) break
            inFlight.add(item.id)
        }
        if (inFlight.isNotEmpty()) watchAcknowledgements()
    }

    private fun watchAcknowledgements() {
        ackWatchdog?.cancel()
        val epoch = generation
        ackWatchdog = scope.launch {
            delay(ACK_TIMEOUT_MS)
            if (epoch == generation && inFlight.isNotEmpty() && state.online) disconnected(epoch)
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        flushRetry?.cancel()
        flushRetry = scope.launch { delay(delayMs); flushOutbox() }
    }

    private suspend fun acknowledgeQueued(id: String) {
        inFlight.remove(id)
        if (inFlight.isEmpty()) ackWatchdog?.cancel()
        db { it.acknowledgeQueued(id) }
        update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
    }

    private suspend fun rejectEnvelope(id: String, code: String) {
        inFlight.remove(id)
        if (inFlight.isEmpty()) ackWatchdog?.cancel()
        when (SendFailure.classify(code)) {
            SendFailure.RETRY -> scheduleFlush(if (code == "chat_disabled") 60_000 else 15_000)
            SendFailure.PERMANENT -> {
                val failed = db {
                    val changed = it.advanceStatus(id, MessageStatus.FAILED)
                    if (!changed) it.removeOutbox(id)
                    changed
                }
                if (failed) update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
            }
        }
    }

    private fun acknowledge(id: String) {
        if (state.serverProtocol >= 7) send("delivery_ack", JSONObject().put("id", id))
    }
    private suspend fun receive(message: JSONObject) {
        when (message.getString("type")) {
            "inbox_complete" -> inboxReady = true
            "registered" -> {
                registrationTimeout?.cancel()
                socketOpening = false
                val sent = registrationHandshake?.protocolVersion ?: RegistrationHandshake.CURRENT
                registrationHandshake?.accept()
                val info = ServerInfo.fromRegistered(message, sent, System.currentTimeMillis())
                inboxReady = info.protocol < 7
                val number = message.getString("number")
                require(number.matches(Regex("[0-9]{8}")))
                db { it.setLocalNumber(number) }
                prefs.edit().putString("number", number).apply()
                reconnect.reset()
                update(state.copy(number = number, online = true, link = Link.ONLINE, mediaReady = message.optBoolean("mediaReady"),
                    callsEnabled = message.optBoolean("callsEnabled", true), chatEnabled = message.optBoolean("chatEnabled", true),
                    maxParticipants = message.optInt("maxParticipants", 8).coerceIn(2, 8), serverProtocol = info.protocol,
                    server = info, pushActive = message.optBoolean("pushEnabled")))
                flushOutbox()
                registerPush()
                work { syncOwnProfile(info); refreshPeerProfiles() }
                watchCallResume()
            }
            "push_registered" -> {
                PushRegistrar.acknowledged(this, message.optBoolean("pushEnabled"))
                update(state.copy(pushActive = message.optBoolean("pushEnabled")))
            }
            "profile_updated" -> prefs.edit().putBoolean("name_dirty", false).apply()
            "profiles" -> {
                val requested = profileRequests.remove(message.optString("requestId")).orEmpty()
                val listed = message.optJSONArray("profiles") ?: JSONArray()
                val seen = mutableSetOf<String>()
                for (i in 0 until listed.length()) {
                    val item = listed.getJSONObject(i)
                    seen += item.getString("number")
                    rememberProfile(item.getString("number"), item, notify = false)
                }
                requested.filter { it !in seen && it in profiles }.forEach { gone -> removeName(gone) }
                update(state.copy(profileVersion = state.profileVersion + 1))
            }
            "missed_call" -> {
                val id = message.getString("callId")
                val from = message.getString("from")
                message.optString("fromName").takeIf { it.isNotEmpty() }?.let { rememberName(from, it) }
                val at = message.optLong("at").takeIf { it > 0 } ?: System.currentTimeMillis()
                db { it.recordActivityEvent(ActivityEvent(id, ActivityEvent.CALL, from, true, "missed", at)) }
                send("missed_ack", JSONObject().put("callId", id))
                AppNotifications.showMissedCall(this, displayName(from))
                update(state.copy(eventVersion = state.eventVersion + 1))
            }
            "call_resume" -> {
                if (callId.isNotEmpty() && message.optString("callId") == callId) {
                    resumeWatch?.cancel()
                    update(state.copy(signalingLost = false))
                    send("join_call", JSONObject().put("callId", callId))
                }
            }
            "capabilities" -> {
                update(state.copy(callsEnabled = message.optBoolean("callsEnabled", true), chatEnabled = message.optBoolean("chatEnabled", true),
                    mediaReady = message.optBoolean("mediaReady"), maxParticipants = message.optInt("maxParticipants", 8).coerceIn(2, 8)))
                if (!state.callsEnabled && state.phase != Phase.IDLE) finish(Notice.CALLS_DISABLED, false, "failed")
            }
            "bundle" -> lookups.remove(message.getString("requestId"))?.complete(message.getJSONObject("bundle"))
            "envelope" -> {
                val from = message.getString("from")
                val id = message.getString("id")
                message.optString("fromName").takeIf { it.isNotEmpty() }?.let { rememberName(from, it) }
                if (!db { it.isVerified(from) }) {
                    holdSender(from)
                    return
                }
                val cipherType = message.getInt("cipherType")
                val payload = db { it.decryptAndStore(from, cipherType, message.getString("body"), id, message.optLong("sentAt", 0)) }
                if (payload == null) { acknowledge(id); return }
                when (payload.getString("kind")) {
                    "chat" -> {
                        require(payload.getString("id") == id)
                        require(payload.getString("text").toByteArray().size <= 4_096)
                        update(state.copy(chatVersion = state.chatVersion + 1))
                        if (viewingPeer == from) markRead(from) else notifyConversation(from)
                    }
                    "receipt" -> update(state.copy(chatVersion = state.chatVersion + 1))
                    "call-key" -> {
                        require(payload.getString("owner") == from)
                        val key = android.util.Base64.decode(payload.getString("key"), android.util.Base64.NO_WRAP)
                        require(key.size == 32)
                        val roster = strings(payload.getJSONArray("members"))
                        require(roster.size in 2..8 && roster.distinct().size == roster.size && state.number in roster && from in roster)
                        val idCall = payload.getString("callId")
                        if (pendingKeys.size >= 8) pendingKeys.clear()
                        pendingKeys[idCall] = payload
                        if (callId == idCall) adoptKey()
                    }
                }
                // Only a pre-key message consumes a one-time pre-key; replenishing after every message costs the server a disk write.
                if (cipherType == 3) send("keys", JSONObject().put("bundle", db { it.publicBundle() }))
                acknowledge(id)
            }
            "queued", "sent" -> acknowledgeQueued(message.getString("id"))
            "delivered" -> {
                val id = message.getString("id")
                inFlight.remove(id)
                db { it.markDelivered(id) }
                update(state.copy(chatVersion = state.chatVersion + 1, pending = db { it.pendingMessageCount() }))
            }
            "call_created" -> {
                if (state.phase != Phase.OUTGOING) return
                require(message.getString("owner") == state.number)
                require(strings(message.getJSONArray("members")).toSet() == state.members.toSet())
                setupCall(message)
                roomKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val peers = state.members.filter { it != state.number }
                val expectedCallId = callId
                launchCallOperation(Phase.OUTGOING, expectedCallId) { epoch, call ->
                    for (number in peers) {
                        checkCallOperation(epoch, Phase.OUTGOING, call)
                        preparePeer(number)
                        checkCallOperation(epoch, Phase.OUTGOING, call)
                        val id = UUID.randomUUID().toString()
                        val key = roomKey ?: error("Missing call key")
                        val payload = JSONObject().put("kind", "call-key").put("callId", call)
                            .put("room", callRoom).put("owner", owner).put("members", JSONArray(state.members))
                            .put("key", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
                        val encrypted = db { it.encrypt(number, payload.toString().toByteArray()) }
                        checkCallOperation(epoch, Phase.OUTGOING, call)
                        check(send("envelope", JSONObject().put("to", number).put("id", id)
                            .put("cipherType", encrypted.cipherType).put("body", encrypted.body)))
                    }
                    checkCallOperation(epoch, Phase.OUTGOING, call)
                    check(send("join_call", JSONObject().put("callId", call)))
                    update(state.copy(phase = Phase.CONNECTING))
                }
            }
            "incoming" -> {
                if (state.phase != Phase.IDLE) return
                message.optString("ownerName").takeIf { it.isNotEmpty() }?.let { rememberName(message.getString("owner"), it) }
                setupCall(message, incoming = true)
                prefs.edit().putString("notification_call_id", callId).apply()
                update(state.copy(phase = Phase.INCOMING, peer = owner))
                adoptKey()
                if (consumePendingCallDismissal(callId)) finish(Notice.CALL_DECLINED, outcome = "declined")
                else if (Settings.notifyCalls(this)) AppNotifications.showIncomingCall(this, callId, displayName(owner), state.members.size)
            }
            "room_grant" -> {
                require(message.getString("callId") == callId && message.getString("room") == callRoom)
                if (engine != null && state.phase == Phase.CONNECTED) return
                require(strings(message.getJSONArray("members")).toSet() == state.members.toSet())
                require(message.getString("owner") == owner)
                val endpoints = config() ?: error("No endpoints")
                require(message.getString("url").trimEnd('/') == endpoints.mediaUrl.trimEnd('/'))
                val key = roomKey ?: error("Missing E2EE key")
                val epochCall = callId
                engine = mediaEngineFactory(this, scope) { event ->
                    work { if (callId == epochCall && epochCall.isNotEmpty()) mediaEvent(event) }
                }
                update(state.copy(phase = Phase.CONNECTING))
                val media = engine!!
                val token = message.getString("token")
                val client = http ?: error("No signaling client")
                val highQuality = state.highQuality
                launchCallOperation(Phase.CONNECTING, epochCall) { epoch, call ->
                    checkCallOperation(epoch, Phase.CONNECTING, call)
                    media.connect(endpoints.mediaUrl, token, key, client, highQuality)
                    checkMediaCallOperation(epoch, call)
                }
            }
            "ended" -> if (message.optString("callId") == callId) finish(Notice.CALL_ENDED, false, endedOutcome(message.optString("reason")))
            "error" -> {
                val request = message.optString("requestId")
                lookups.remove(request)?.completeExceptionally(IllegalStateException(message.optString("code")))
                val id = message.optString("id")
                val code = message.optString("code")
                if (id.isNotEmpty() && id in inFlight) rejectEnvelope(id, code)
                if (code == "replaced") {
                    generation++; socket?.cancel(); socket = null
                    finish(Notice.REPLACED, false, "failed")
                    update(state.copy(online = false, link = Link.NONE))
                } else if (request.isEmpty() && id.isEmpty() && state.phase != Phase.IDLE && code !in QUIET_ERRORS) {
                    finish(Notice.CALL_UNAVAILABLE)
                }
            }
        }
    }
    /** Outcome of a call that the server ended: reaching a peer who declined differs from a plain hang-up. */
    private fun endedOutcome(reason: String): String? = when (reason) {
        "declined" -> if (callAttempt?.connectedAt == 0L) "declined" else null
        else -> null
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun setupCall(message: JSONObject, incoming: Boolean = false) {
        callId = message.getString("callId")
        callRoom = message.getString("room")
        owner = message.getString("owner")
        val members = strings(message.getJSONArray("members"))
        require(members.size in 2..8 && members.distinct().size == members.size && state.number in members && owner in members)
        val peers = members.filter { it != state.number }.joinToString(",")
        if (incoming || callAttempt == null) beginCallAttempt(peers, incoming)
        else callAttempt = callAttempt?.copy(peer = peers)
        update(state.copy(members = members))
        callTimeout?.cancel()
        callTimeout = scope.launch { delay(45_000); if (state.phase != Phase.CONNECTED) finish(Notice.CALL_NO_ANSWER) }
    }

    private fun adoptKey() {
        val payload = pendingKeys[callId] ?: return
        require(payload.getString("room") == callRoom && payload.getString("owner") == owner)
        require(strings(payload.getJSONArray("members")).toSet() == state.members.toSet())
        roomKey = android.util.Base64.decode(payload.getString("key"), android.util.Base64.NO_WRAP)
        pendingKeys.remove(callId)
    }

    private suspend fun mediaEvent(event: MediaEvent) {
        when (event) {
            is MediaEvent.Connected -> {
                callTimeout?.cancel()
                callAttempt = callAttempt?.copy(connectedAt = SystemClock.elapsedRealtime())
                update(state.copy(phase = Phase.CONNECTED, connectedAt = SystemClock.elapsedRealtime()))
            }
            is MediaEvent.Participants -> {
                require(event.numbers.all { it in state.members }) { "Unexpected room participant" }
                update(state.copy(participants = event.numbers))
            }
            is MediaEvent.Disconnected -> if (state.phase != Phase.IDLE) finish(Notice.CALL_MEDIA_LOST, outcome = "failed")
            is MediaEvent.Failed -> finish(Notice.CALL_MEDIA_FAILED, outcome = "failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> if (Settings.persistent(this) && config() != null) enterPersistentForeground() else stopSelf()
            ACTION_CONNECT -> enterPersistentForeground()
            ACTION_REPLY -> {
                val peer = intent.getStringExtra(AppNotifications.EXTRA_PEER).orEmpty()
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                if (peer.matches(Regex("[0-9]{8}")) && text.isNotBlank()) work { sendChat(peer, text.trim()); markRead(peer) }
            }
            ACTION_MARK_READ -> intent.getStringExtra(AppNotifications.EXTRA_PEER)?.takeIf { it.matches(Regex("[0-9]{8}")) }
                ?.let { peer -> work { markRead(peer) } }
            AppNotifications.ACTION_DISMISS_INCOMING -> {
                val dismissed = intent.getStringExtra(AppNotifications.EXTRA_CALL_ID).orEmpty()
                if (state.phase == Phase.INCOMING && dismissed == callId) {
                    work { finish(Notice.CALL_DECLINED, outcome = "declined") }
                } else if (dismissed.isNotEmpty()) {
                    prefs.edit().putString("pending_call_dismissal", dismissed)
                        .putLong("pending_call_dismissal_at", System.currentTimeMillis()).apply()
                }
            }
            "dial", "accept" -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    if (intent.action == "dial") {
                        val peers = intent.getStringArrayListExtra("members")?.distinct().orEmpty()
                        if (peers.isNotEmpty()) beginCallAttempt(peers.joinToString(","), incoming = false)
                    }
                    scope.launch { finish(Notice.MIC_REQUIRED, outcome = if (intent.action == "accept") "missed" else "failed") }
                    return START_NOT_STICKY
                }
                if (intent.action == "accept") AppNotifications.cancelIncomingCall(this)
                startForegroundCompat(call = true)
                work {
                    if (intent.action == "dial" && state.phase == Phase.IDLE) {
                        val members = intent.getStringArrayListExtra("members")?.distinct() ?: emptyList()
                        require(members.size in 1 until state.maxParticipants && state.number !in members)
                        beginCallAttempt(members.joinToString(","), incoming = false)
                        require(state.callsEnabled && state.number.isNotEmpty())
                        update(state.copy(phase = Phase.OUTGOING, peer = members.joinToString(", "), members = listOf(state.number) + members))
                        launchCallOperation(Phase.OUTGOING, "") { epoch, call ->
                            if (!state.online) withTimeout(CALL_CONNECT_WAIT_MS) {
                                while (!state.online) { checkCallOperation(epoch, Phase.OUTGOING, call); delay(100) }
                            }
                            check(state.mediaReady) { "Media service is not configured" }
                            withTimeout(20_000) {
                                for (number in members) {
                                    checkCallOperation(epoch, Phase.OUTGOING, call)
                                    preparePeer(number)
                                    checkCallOperation(epoch, Phase.OUTGOING, call)
                                }
                            }
                            checkCallOperation(epoch, Phase.OUTGOING, call)
                            acquireWakeLock()
                            check(send("create_call", JSONObject().put("members", JSONArray(members))))
                            callTimeout = scope.launch {
                                delay(45_000)
                                if (state.phase != Phase.CONNECTED) finish(Notice.CALL_NO_ANSWER)
                            }
                        }
                    } else if (intent.action == "accept" && state.phase == Phase.INCOMING) {
                        val expectedCallId = callId
                        launchCallOperation(Phase.INCOMING, expectedCallId) { epoch, call ->
                            for (number in state.members.filter { it != state.number }) {
                                val trusted = verified(number)
                                checkCallOperation(epoch, Phase.INCOMING, call)
                                check(trusted) { "Verify SAS for all participants" }
                            }
                            withTimeout(10_000) {
                                while (roomKey == null) {
                                    checkCallOperation(epoch, Phase.INCOMING, call)
                                    adoptKey()
                                    if (roomKey == null) delay(100)
                                    checkCallOperation(epoch, Phase.INCOMING, call)
                                }
                            }
                            checkCallOperation(epoch, Phase.INCOMING, call)
                            acquireWakeLock()
                            update(state.copy(phase = Phase.CONNECTING))
                            checkCallOperation(epoch, Phase.CONNECTING, call)
                            check(send("join_call", JSONObject().put("callId", call)))
                        }
                    } else if (state.phase == Phase.IDLE) {
                        finish(Notice.NONE)
                    }
                }
            }
            "mute" -> toggleMute()
            "hangup" -> hangup()
        }
        return if (Settings.persistent(this) && config() != null) START_STICKY else START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Line:group-call")
            .apply { acquire() }
    }

    fun toggleMute() { work {
        if (state.phase != Phase.CONNECTED) return@work
        val muted = !state.muted
        engine?.setMuted(muted)
        update(state.copy(muted = muted))
    } }

    fun toggleSpeaker() {
        if (state.phase != Phase.CONNECTED) return
        val enabled = !state.speaker
        engine?.setSpeaker(enabled)
        update(state.copy(speaker = enabled))
    }

    fun hangup() { work {
        val pending = state.phase == Phase.OUTGOING && callId.isEmpty()
        val outcome = when {
            state.phase == Phase.CONNECTED -> "completed"
            state.phase == Phase.INCOMING -> "declined"
            state.phase == Phase.OUTGOING || state.phase == Phase.CONNECTING -> "cancelled"
            else -> "failed"
        }
        finish(Notice.CALL_ENDED, outcome = outcome)
        if (pending) connect()
    } }

    private fun beginCallAttempt(peer: String, incoming: Boolean) {
        callAttempt = CallAttempt(UUID.randomUUID().toString(), peer, incoming, System.currentTimeMillis())
    }

    private fun consumePendingCallDismissal(serverCallId: String): Boolean {
        val id = prefs.getString("pending_call_dismissal", "") ?: ""
        val savedAt = prefs.getLong("pending_call_dismissal_at", 0)
        if (id.isEmpty()) return false
        prefs.edit().remove("pending_call_dismissal").remove("pending_call_dismissal_at").apply()
        return id == serverCallId && System.currentTimeMillis() - savedAt <= 60_000
    }

    private suspend fun finish(notice: Notice, notifyServer: Boolean = true, outcome: String? = null) {
        callTimeout?.cancel(); callTimeout = null
        val operation = callOperation
        callOperation = null
        callOperationEpoch++
        if (operation != currentCoroutineContext()[Job]) operation?.cancel()
        val attempt = callAttempt
        var saved = false
        var result: String? = null
        var summary: CallSummary? = null
        if (attempt != null && !attempt.eventRecorded) {
            callAttempt = attempt.copy(eventRecorded = true)
            val callOutcome = outcome ?: when {
                attempt.incoming && state.phase == Phase.INCOMING -> "missed"
                attempt.connectedAt > 0 -> "completed"
                else -> "failed"
            }
            result = callOutcome
            val duration = if (attempt.connectedAt > 0)
                (SystemClock.elapsedRealtime() - attempt.connectedAt).coerceAtLeast(0) / 1_000 else 0
            try {
                db { it.recordActivityEvent(ActivityEvent(
                    attempt.id, ActivityEvent.CALL, attempt.peer, attempt.incoming, callOutcome,
                    attempt.startedAt.coerceAtLeast(1), duration,
                )) }
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            if (CallSummary.worthShowing(callOutcome, duration, attempt.incoming)) {
                summary = CallSummary(attempt.id, attempt.peer.split(',').filter { it.isNotEmpty() }, attempt.incoming,
                    callOutcome, duration, attempt.startedAt, System.currentTimeMillis())
            }
        }
        if (notifyServer && callId.isNotEmpty()) send(if (state.phase == Phase.INCOMING) "decline_call" else "leave_call", JSONObject().put("callId", callId))
        prefs.edit().remove("notification_call_id").apply()
        callId = ""; callRoom = ""; owner = ""
        roomKey?.fill(0); roomKey = null; pendingKeys.clear()
        val oldEngine = engine; engine = null
        update(state.copy(phase = Phase.IDLE, peer = "", members = emptyList(), participants = emptyList(),
            muted = false, speaker = false, connectedAt = 0, safetyCode = "", signalingLost = false,
            notice = notice, noticeVersion = state.noticeVersion + 1, lastCall = summary ?: state.lastCall,
            eventVersion = state.eventVersion + if (saved) 1 else 0))
        oldEngine?.disconnect()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        resumeWatch?.cancel()
        val persistent = Settings.persistent(this) && config() != null
        if (persistent) runCatching { startForegroundCompat(call = false) }
        else { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE) }
        if (state.phase != Phase.INCOMING) AppNotifications.cancelIncomingCall(this)
        if (saved && result == "missed" && attempt?.incoming == true) {
            AppNotifications.showMissedCall(this, displayName(attempt.peer.substringBefore(',')))
        }
        callAttempt = null
        if (!persistent) stopSelf()
    }
    private fun notification(): Notification =
        if (state.phase == Phase.IDLE) AppNotifications.connection(this, state)
        else AppNotifications.ongoingCall(this, displayName(state.members.firstOrNull { it != state.number } ?: ""),
            state.members.size, state.muted, state.phase == Phase.CONNECTED)

    // ---- profiles ---------------------------------------------------------------------------------

    private suspend fun loadProfileCache() {
        profiles = db { it.profiles() }
        update(state.copy(ownName = db { it.ownName() }, profileVersion = state.profileVersion + 1))
    }

    private suspend fun rememberProfile(number: String, json: JSONObject, notify: Boolean = true) {
        if (!number.matches(Regex("[0-9]{8}"))) return
        val proto = json.optInt("proto", 0)
        if (proto > 0) db { it.setPeerProtocol(number, proto) }
        val name = json.optString("name")
        if (name.isEmpty()) { if (number in profiles) removeName(number) } else rememberName(number, name, json.optLong("updatedAt"), notify)
    }

    private suspend fun rememberName(number: String, raw: String, updatedAt: Long = 0, notify: Boolean = true) {
        val name = DisplayName.normalize(raw)?.takeIf { it.isNotEmpty() } ?: return
        if (profiles[number] == name) return
        db { it.saveProfile(number, name, updatedAt) }
        profiles = profiles + (number to name)
        if (notify) update(state.copy(profileVersion = state.profileVersion + 1))
    }

    private suspend fun removeName(number: String) {
        db { it.saveProfile(number, "", 0) }
        profiles = profiles - number
    }

    /** Saves the display name locally first; it reaches the server now or at the next connection. */
    suspend fun setOwnName(raw: String): Boolean {
        val name = DisplayName.normalize(raw) ?: return false
        db { it.setOwnName(name) }
        prefs.edit().putBoolean("name_dirty", true).apply()
        update(state.copy(ownName = name))
        if (state.online && state.server.supportsProfiles) send("profile_set", JSONObject().put("name", name))
        return true
    }

    private suspend fun syncOwnProfile(info: ServerInfo) {
        if (!info.supportsProfiles) return
        val own = db { it.ownName() }
        if (prefs.getBoolean("name_dirty", false)) {
            send("profile_set", JSONObject().put("name", own))
        } else if (info.name != own) {
            val adopted = DisplayName.normalize(info.name).orEmpty()
            db { it.setOwnName(adopted) }
            update(state.copy(ownName = adopted))
        }
    }

    private suspend fun refreshPeerProfiles() {
        if (!state.server.supportsProfiles) return
        db { it.knownPeers() }.chunked(32).forEach { batch ->
            val id = UUID.randomUUID().toString()
            profileRequests[id] = batch
            if (!send("profile_get", JSONObject().put("requestId", id).put("numbers", JSONArray(batch)))) return
        }
    }

    /** Whether this peer is known to run a client that understands receipts and attachments. */
    suspend fun peerSupportsMedia(peer: String): Boolean = db { it.peerSupportsReceipts(peer) }

    // ---- push (UnifiedPush) --------------------------------------------------------------------------

    private fun registerPush() {
        val info = state.server
        if (info.protocol < 8 || !info.supportsUnifiedPush) return
        val endpoint = PushRegistrar.endpoint(this) ?: return
        if (state.pushActive && PushRegistrar.isRegistered(this, endpoint)) return
        PushRegistrar.markSent(this, endpoint)
        send("push_register", JSONObject().put("provider", "unifiedpush").put("endpoint", endpoint.url).apply {
            endpoint.pubKey?.let { put("pubKey", it) }
            endpoint.auth?.let { put("auth", it) }
        })
    }

    fun pushEndpointChanged() { if (state.online) registerPush() }
    fun unregisterPush() { if (state.online && state.server.protocol >= 8) send("push_unregister") }

    // ---- calls that survive a signaling reconnect ---------------------------------------------------

    private fun watchCallResume() {
        if (callId.isEmpty() || !state.signalingLost) return
        resumeWatch?.cancel()
        resumeWatch = scope.launch {
            delay(RESUME_WAIT_MS)
            if (state.signalingLost && callId.isNotEmpty()) finish(Notice.CALL_ENDED, notifyServer = false)
        }
    }

    fun dismissLastCall() { if (state.lastCall != null) update(state.copy(lastCall = null)) }

    // ---- notifications and foreground state ----------------------------------------------------------

    private suspend fun notifyConversation(peer: String) {
        if (!Settings.notifyMessages(this)) return
        val unread = db { it.unreadMessages(peer, 6) }
        if (unread.isEmpty()) return
        AppNotifications.showConversation(this, peer, displayName(peer), unread.map { previewText(it) to it.createdAt },
            hideContent = !Settings.notifyPreview(this))
    }

    private fun previewText(message: ChatMessage): String = when (message.kind) {
        "image" -> getString(R.string.notif_photo)
        "voice" -> getString(R.string.notif_voice)
        "file" -> getString(R.string.notif_file)
        else -> message.text
    }

    private fun enterPersistentForeground() {
        if (foreground) return
        runCatching { startForegroundCompat(call = false) }.onFailure { if (state.phase == Phase.IDLE) stopSelf() }
    }

    private fun startForegroundCompat(call: Boolean) {
        val note = notification()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(AppNotifications.ACTIVE_CALL_ID, note,
                if (call) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 -> startForeground(AppNotifications.ACTIVE_CALL_ID, note,
                if (call) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            else -> startForeground(AppNotifications.ACTIVE_CALL_ID, note)
        }
        foreground = true
    }

    /** Called after the user toggles the persistent connection. */
    fun applyPersistentSetting() {
        if (Settings.persistent(this) && config() != null) ensureRunning(this)
        else if (state.phase == Phase.IDLE && foreground) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        const val ACTION_CONNECT = "app.line.action.CONNECT"
        const val ACTION_REPLY = "app.line.action.REPLY"
        const val ACTION_MARK_READ = "app.line.action.MARK_READ"
        const val KEY_REPLY = "line.reply"
        private const val REGISTRATION_TIMEOUT_MS = 10_000L
        private const val ACK_TIMEOUT_MS = 20_000L
        private const val CALL_NETWORK_GRACE_MS = 20_000L
        private const val CALL_CONNECT_WAIT_MS = 10_000L
        private const val RESUME_WAIT_MS = 8_000L
        private val QUIET_ERRORS = setOf("invalid_profile", "invalid_push_endpoint", "push_provider_unsupported", "rate_limited", "storage_unavailable")

        /** Keeps the connection alive in the background when the user allows it; safe to call while the app is in the foreground. */
        fun ensureRunning(context: Context) {
            val prefs = context.getSharedPreferences("line", MODE_PRIVATE)
            if (!Settings.persistent(context) || prefs.getString("endpoint", "").isNullOrEmpty()) return
            runCatching { context.startForegroundService(Intent(context, CallService::class.java).setAction(ACTION_CONNECT)) }
        }
    }

    override fun onDestroy() {
        lockAdmin()
        generation++
        cancelLookups("Service destroyed")
        callOperationEpoch++
        callOperation?.cancel(); callOperation = null
        retryJob?.cancel(); registrationTimeout?.cancel(); callTimeout?.cancel()
        networkGrace?.cancel(); ackWatchdog?.cancel(); flushRetry?.cancel(); resumeWatch?.cancel()
        incoming.close()
        socket?.cancel(); socket = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        runCatching { network.unregisterNetworkCallback(networkCallback) }
        roomKey?.fill(0); roomKey = null
        val oldEngine = engine
        engine = null
        if (state.phase != Phase.INCOMING) AppNotifications.cancelIncomingCall(this)
        scope.launch {
            try {
                oldEngine?.disconnect()
                withContext(Dispatchers.IO) { runCatching { secure.await().close() } }
            }
            finally { scope.cancel() }
        }
        http?.dispatcher?.executorService?.shutdown(); http?.connectionPool?.evictAll()
        listeners.clear()
        super.onDestroy()
    }
}
