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
import app.line.push.PushConfiguration
import app.line.push.PushAlerts
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var callTimeout: Job? = null
    private var registrationTimeout: Job? = null
    private val network by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            scope.launch {
                delay(500)
                val active = network.activeNetwork
                if (active == null || network.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                    if (state.phase != Phase.IDLE) finish("Интернет отключён. Звонок завершён", outcome = "failed")
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
                        update(state.copy(message = "Неверное или повторное зашифрованное сообщение отклонено"))
                    } else if (state.phase != Phase.IDLE || foreground) finish("Ошибка протокола звонка")
                    else update(state.copy(message = "Неверное сообщение сервера отклонено"))
                }
            }
        }
        val initialGeneration = generation
        work { secure.await(); if (generation == initialGeneration) connect() }
    }

    override fun onBind(intent: Intent): IBinder = binder
    fun observe(listener: (CallState) -> Unit) { listeners.add(listener); listener(state) }
    fun removeObserver(listener: (CallState) -> Unit) { listeners.remove(listener) }

    private fun update(value: CallState) {
        state = value
        listeners.toList().forEach { it(value) }
        if (foreground) getSystemService(NotificationManager::class.java)
            .notify(AppNotifications.ACTIVE_CALL_ID, notification())
    }

    private fun work(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (callAttempt != null) finish("Ошибка защищённого соединения. Проверьте SAS участников", outcome = "failed")
            else if (state.phase != Phase.IDLE || foreground) finish("Ошибка защищённого соединения. Проверьте SAS участников")
            else update(state.copy(message = "Операция не выполнена: проверьте сеть, ключи и SAS"))
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
        require(old == null || old.apiUrl == value.apiUrl) { "Для другого сервера нужен отдельный профиль/очистка данных: номера и доверие не переносятся" }
        generation++
        cancelLookups("Connection settings changed")
        prefs.edit().putString("endpoint", value.apiUrl).putString("api_pins", value.apiPins)
            .putString("media_endpoint", value.mediaUrl).putString("media_pins", value.mediaPins)
            .putBoolean("high_quality", highQuality).apply()
        update(state.copy(configReady = true, highQuality = highQuality))
        work { connect() }
    }

    fun reconnectNow() { if (state.phase == Phase.IDLE) work { connect() } }
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
                    finish("Нет ответа или соединения. Звонок завершён")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (callOperationEpoch == epoch && callId == expectedCallId && state.phase != Phase.IDLE) {
                    finish("Ошибка защищённого соединения. Проверьте SAS участников")
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

    private suspend fun connect() {
        inboxReady = false
        retryJob?.cancel()
        registrationTimeout?.cancel()
        val epoch = ++generation
        lockAdmin()
        cancelLookups("Connection changed")
        socket?.cancel()
        socket = null
        http?.dispatcher?.executorService?.shutdown()
        http?.connectionPool?.evictAll()
        http = null
        val endpoints = config() ?: run {
            update(state.copy(online = false, mediaReady = false, configReady = false, message = "Подключите Line, чтобы получить номер"))
            return
        }
        val bundle = publicBundleProvider()
        if (epoch != generation) return
        val handshake = RegistrationHandshake(token(), bundle)
        registrationHandshake = handshake
        val client = httpClientFactory(endpoints)
        if (epoch != generation) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            return
        }
        http = client
        update(state.copy(online = false, mediaReady = false, serverProtocol = 0, message = "Подключение…"))
        socket = client.newWebSocket(Request.Builder().url(endpoints.apiUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch { if (epoch == generation) {
                    ws.send(handshake.packet().toString())
                    registrationTimeout = scope.launch { delay(15_000); if (!state.online) disconnected(epoch) }
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
                        registrationTimeout = scope.launch { delay(15_000); if (!state.online) disconnected(epoch) }
                        return@launch
                    }
                    val requestId = message.optString("requestId")
                    if (requestId.isNotEmpty() && message.optString("type") == "admin_result") {
                        adminRequests.remove(requestId)?.complete(message)
                    } else if (requestId.isNotEmpty() && message.optString("type") == "bundle") {
                        val pending = lookups.remove(requestId)
                        val bundle = message.optJSONObject("bundle")
                        if (bundle != null) pending?.complete(bundle)
                        else pending?.completeExceptionally(IllegalStateException("Invalid bundle"))
                    } else if (requestId.isNotEmpty() && message.optString("type") == "error") {
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
        cancelLookups("Offline")
        if (state.phase != Phase.IDLE) finish("Соединение потеряно. Звонок завершён", notifyServer = false, outcome = "failed")
        update(state.copy(online = false, mediaReady = false, message = "Не удалось подключиться. Проверьте настройки сервиса"))
        retryJob?.cancel()
        retryJob = scope.launch { delay(5_000); connect() }
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
        if (state.serverProtocol >= 7) { inboxReady = false; send("inbox_sync") }
    }
    suspend fun messages(number: String, before: Long? = null): List<ChatMessage> = db { it.messages(number, before, 40) }
    suspend fun searchMessages(query: String, peer: String? = null, before: Long? = null): List<ChatMessage> =
        db { it.searchMessages(query, peer, before, 40) }
    suspend fun conversations(before: Long? = null): List<ChatMessage> = db { it.conversations(before, 40) }
    suspend fun activityEvents(callsOnly: Boolean = false, before: Long? = null): List<ActivityEvent> =
        db { it.activityEvents(callsOnly, before, 40, incomingOnly = !callsOnly) }

    suspend fun clearMessageNotifications(peer: String) {
        var cursor: Long? = null
        do {
            val page = db { it.activityEvents(before = cursor, limit = 100) }
            page.filter { it.kind == ActivityEvent.MESSAGE && it.peer == peer && it.incoming }
                .forEach { AppNotifications.cancelMessage(this, it.id) }
            cursor = page.lastOrNull()?.timestamp
        } while (page.size == 100)
    }
    suspend fun deleteMessage(id: String) {
        db { it.deleteMessage(id) }
        chatEnvelopeIds.remove(id)
        AppNotifications.cancelMessage(this, id)
        update(state.copy(chatVersion = state.chatVersion + 1, eventVersion = state.eventVersion + 1))
    }
    suspend fun clearConversation(peer: String) {
        val removedEvents = db { it.clearConversation(peer) }
        removedEvents.forEach { AppNotifications.cancelMessage(this, it) }
        update(state.copy(chatVersion = state.chatVersion + 1, eventVersion = state.eventVersion + 1))
    }

    private suspend fun preparePeer(number: String) = sessionPreparation.withLock {
        val exists = db { it.hasSession(number) }
        if (exists && !state.online) { check(db { it.isVerified(number) }) { "SAS must be verified" }; return@withLock }
        val bundle = lookup(number, consumePreKey = !exists)
        db {
            it.rememberPeer(number, bundle)
            check(it.isVerified(number)) { "SAS must be verified" }
            if (!it.hasSession(number)) it.establishSession(number, bundle)
        }
    }

    suspend fun sendChat(number: String, text: String) = chatSending.withLock {
        check(state.chatEnabled) { "Администратор отключил сообщения" }
        require(text.isNotBlank() && text.toByteArray().size <= 4_096)
        preparePeer(number)
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("kind", "chat").put("id", id).put("text", text).toString().toByteArray()
        val envelope = db { it.encryptAndQueue(number, id, payload, text) }
        chatEnvelopeIds.add(id)
        if (state.online) send("envelope", JSONObject().put("to", number).put("id", id).put("cipherType", envelope.cipherType).put("body", envelope.body))
        Feedback.messageSent(this)
        update(state.copy(chatVersion = state.chatVersion + 1))
    }

    private suspend fun receive(message: JSONObject) {
        when (message.getString("type")) {
            "inbox_complete" -> inboxReady = true
            "registered" -> {
                registrationTimeout?.cancel()
                val protocol = registrationHandshake?.protocolVersion ?: 7
                registrationHandshake?.accept()
                inboxReady = protocol < 7
                val number = message.getString("number")
                require(number.matches(Regex("[0-9]{8}")))
                db { it.setLocalNumber(number) }
                prefs.edit().putString("number", number).apply()
                update(state.copy(number = number, online = true, mediaReady = message.optBoolean("mediaReady"),
                    callsEnabled = message.optBoolean("callsEnabled", true), chatEnabled = message.optBoolean("chatEnabled", true),
                    maxParticipants = message.optInt("maxParticipants", 8).coerceIn(2, 8), serverProtocol = protocol, message = "В сети"))
                val outbox = db { it.outbox() }
                chatEnvelopeIds.addAll(outbox.map { it.id })
                if (state.chatEnabled) outbox.forEach { send("envelope", JSONObject().put("to", it.peer).put("id", it.id).put("cipherType", it.cipherType).put("body", it.body)) }
                if (protocol >= 7) work {
                    val pushToken = PushConfiguration.token(this@CallService)
                    if (!pushToken.isNullOrBlank() && state.online && state.serverProtocol >= 7) send("push_register", JSONObject().put("token", pushToken))
                }
            }
            "capabilities" -> {
                update(state.copy(callsEnabled = message.optBoolean("callsEnabled", true), chatEnabled = message.optBoolean("chatEnabled", true),
                    mediaReady = message.optBoolean("mediaReady"), maxParticipants = message.optInt("maxParticipants", 8).coerceIn(2, 8)))
                if (!state.callsEnabled && state.phase != Phase.IDLE) finish("Администратор отключил звонки", false, "failed")
            }
            "bundle" -> lookups.remove(message.getString("requestId"))?.complete(message.getJSONObject("bundle"))
            "envelope" -> {
                val from = message.getString("from")
                val id = message.getString("id")
                if (!db { it.isVerified(from) }) {
                    AppNotifications.showIncomingMessage(this, from, id)
                    update(state.copy(message = "Сообщение от $from отклонено: сначала сверьте SAS")); return
                }
                val payload = db { it.decryptAndStore(from, message.getInt("cipherType"), message.getString("body"), id) }
                if (payload == null) { if (state.serverProtocol >= 7) send("delivery_ack", JSONObject().put("id", id)); return }
                when (payload.getString("kind")) {
                    "chat" -> {
                        require(payload.getString("id") == id)
                        val text = payload.getString("text")
                        require(text.toByteArray().size <= 4_096)
                        update(state.copy(chatVersion = state.chatVersion + 1, eventVersion = state.eventVersion + 1,
                            message = "Новое зашифрованное сообщение от $from"))
                        AppNotifications.showIncomingMessage(this, from, id)
                    }
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
                send("keys", JSONObject().put("bundle", db { it.publicBundle() }))
                if (state.serverProtocol >= 7) send("delivery_ack", JSONObject().put("id", id))
                PushAlerts.dismissMessage(this, id)
            }
            "queued" -> {
                val id = message.getString("id")
                if (id in chatEnvelopeIds) {
                    db { it.updateMessageStatus(id, "queued") }
                    update(state.copy(chatVersion = state.chatVersion + 1))
                }
            }
            "delivered" -> {
                val id = message.getString("id")
                val removed = db { it.markDelivered(id) }
                chatEnvelopeIds.remove(id)
                if (removed) Feedback.messageSent(this)
                update(state.copy(chatVersion = state.chatVersion + 1))
            }
            "sent" -> {
                val id = message.getString("id")
                if (!chatEnvelopeIds.remove(id)) return
                val evicted = db {
                    it.acknowledgeSent(id)
                    it.recordMessageActivity(id, outgoing = true, outcome = "sent")
                }
                evicted.forEach { AppNotifications.cancelMessage(this, it) }
                update(state.copy(chatVersion = state.chatVersion + 1, eventVersion = state.eventVersion + 1))
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
                    update(state.copy(phase = Phase.CONNECTING, message = "Соединяем зашифрованную группу…"))
                }
            }
            "incoming" -> {
                if (state.phase != Phase.IDLE) return
                setupCall(message, incoming = true)
                PushAlerts.dismissCall(this, callId)
                prefs.edit().putString("notification_call_id", callId).apply()
                update(state.copy(phase = Phase.INCOMING, peer = owner, message = "Входящий групповой звонок"))
                adoptKey()
                if (consumePendingCallDismissal(callId)) finish("Звонок отклонён", outcome = "declined")
                else AppNotifications.showIncomingCall(this, callId)
            }
            "room_grant" -> {
                require(message.getString("callId") == callId && message.getString("room") == callRoom)
                require(strings(message.getJSONArray("members")).toSet() == state.members.toSet())
                require(message.getString("owner") == owner)
                val endpoints = config() ?: error("No endpoints")
                require(message.getString("url").trimEnd('/') == endpoints.mediaUrl.trimEnd('/'))
                val key = roomKey ?: error("Missing E2EE key")
                val epochCall = callId
                engine = mediaEngineFactory(this, scope) { event ->
                    work { if (callId == epochCall && epochCall.isNotEmpty()) mediaEvent(event) }
                }
                update(state.copy(phase = Phase.CONNECTING, message = "LiveKit · устанавливаем E2EE…"))
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
            "ended" -> if (message.optString("callId") == callId) finish("Групповой звонок завершён", false)
            "error" -> {
                val request = message.optString("requestId")
                lookups.remove(request)?.completeExceptionally(IllegalStateException(message.optString("code")))
                val id = message.optString("id")
                if (id in chatEnvelopeIds) {
                    val evicted = db {
                        it.updateMessageStatus(id, "failed")
                        it.recordMessageActivity(id, outgoing = true, outcome = "failed")
                    }
                    evicted.forEach { AppNotifications.cancelMessage(this, it) }
                    update(state.copy(chatVersion = state.chatVersion + 1, eventVersion = state.eventVersion + 1,
                        message = "Сообщение не отправлено: адресат недоступен"))
                }
                if (message.optString("code") == "replaced") {
                    generation++; socket?.cancel(); socket = null
                    finish("Учётная запись открыта на другом устройстве", false, "failed")
                    update(state.copy(online = false))
                } else if (request.isEmpty() && id.isEmpty() && state.phase != Phase.IDLE) finish("Звонок невозможен: участник недоступен или LiveKit не настроен")
            }
        }
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
        callTimeout = scope.launch { delay(45_000); if (state.phase != Phase.CONNECTED) finish("Нет ответа или соединения. Звонок завершён") }
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
                update(state.copy(phase = Phase.CONNECTED, connectedAt = SystemClock.elapsedRealtime(), message = "Голос E2EE · ${if (state.highQuality) "Opus HQ" else "Opus речь"}"))
            }
            is MediaEvent.Participants -> {
                require(event.numbers.all { it in state.members }) { "Unexpected room participant" }
                update(state.copy(participants = event.numbers))
            }
            is MediaEvent.Disconnected -> if (state.phase != Phase.IDLE) finish("Медиасоединение потеряно. Звонок завершён", outcome = "failed")
            is MediaEvent.Failed -> finish("Ошибка E2EE или медиасервера. Звонок завершён", outcome = "failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppNotifications.ACTION_DISMISS_INCOMING -> {
                val dismissed = intent.getStringExtra(AppNotifications.EXTRA_CALL_ID).orEmpty()
                if (state.phase == Phase.INCOMING && dismissed == callId) {
                    work { finish("Звонок отклонён", outcome = "declined") }
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
                    scope.launch { finish("Нужен доступ к микрофону", outcome = if (intent.action == "accept") "missed" else "failed") }
                    return START_NOT_STICKY
                }
                if (intent.action == "accept") AppNotifications.cancelIncomingCall(this)
                if (!foreground) {
                    if (Build.VERSION.SDK_INT >= 30) startForeground(AppNotifications.ACTIVE_CALL_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(AppNotifications.ACTIVE_CALL_ID, notification())
                    foreground = true
                }
                work {
                    if (intent.action == "dial" && state.phase == Phase.IDLE) {
                        val members = intent.getStringArrayListExtra("members")?.distinct() ?: emptyList()
                        require(members.size in 1 until state.maxParticipants && state.number !in members)
                        beginCallAttempt(members.joinToString(","), incoming = false)
                        require(state.online && state.mediaReady && state.callsEnabled)
                        update(state.copy(phase = Phase.OUTGOING, peer = members.joinToString(", "), members = listOf(state.number) + members, message = "Создаём группу…"))
                        launchCallOperation(Phase.OUTGOING, "") { epoch, call ->
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
                                if (state.phase != Phase.CONNECTED) finish("Не удалось установить звонок")
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
                            update(state.copy(phase = Phase.CONNECTING, message = "Соединяем…"))
                            checkCallOperation(epoch, Phase.CONNECTING, call)
                            check(send("join_call", JSONObject().put("callId", call)))
                        }
                    } else if (state.phase == Phase.IDLE) {
                        finish("Готов к звонку")
                    }
                }
            }
            "mute" -> toggleMute()
            "hangup" -> hangup()
        }
        return START_NOT_STICKY
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
        finish("Звонок завершён", outcome = outcome)
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

    private suspend fun finish(message: String, notifyServer: Boolean = true, outcome: String? = null) {
        callTimeout?.cancel(); callTimeout = null
        val operation = callOperation
        callOperation = null
        callOperationEpoch++
        if (operation != currentCoroutineContext()[Job]) operation?.cancel()
        val attempt = callAttempt
        var saved = false
        var result: String? = null
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
                val evicted = db { it.recordActivityEvent(ActivityEvent(
                    attempt.id, ActivityEvent.CALL, attempt.peer, attempt.incoming, callOutcome,
                    attempt.startedAt.coerceAtLeast(1), duration,
                )) }
                evicted.forEach { AppNotifications.cancelMessage(this, it) }
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        if (notifyServer && callId.isNotEmpty()) send(if (state.phase == Phase.INCOMING) "decline_call" else "leave_call", JSONObject().put("callId", callId))
        prefs.edit().remove("notification_call_id").apply()
        callId = ""; callRoom = ""; owner = ""
        roomKey?.fill(0); roomKey = null; pendingKeys.clear()
        val oldEngine = engine; engine = null
        update(state.copy(phase = Phase.IDLE, peer = "", members = emptyList(), participants = emptyList(),
            muted = false, speaker = false, connectedAt = 0, safetyCode = "", message = message,
            eventVersion = state.eventVersion + if (saved) 1 else 0))
        oldEngine?.disconnect()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (state.phase != Phase.INCOMING) AppNotifications.cancelIncomingCall(this)
        if (saved && result == "missed" && attempt?.incoming == true) AppNotifications.showMissedCall(this)
        callAttempt = null
        stopSelf()
    }

    private fun notification(): Notification = AppNotifications.ongoingCall(this, state.muted, state.phase == Phase.CONNECTED)

    override fun onDestroy() {
        lockAdmin()
        generation++
        cancelLookups("Service destroyed")
        callOperationEpoch++
        callOperation?.cancel(); callOperation = null
        retryJob?.cancel(); registrationTimeout?.cancel(); callTimeout?.cancel()
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
