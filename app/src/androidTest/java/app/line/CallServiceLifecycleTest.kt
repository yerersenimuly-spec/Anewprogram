package app.line

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import app.line.crypto.SecureStore
import app.line.media.CallMediaEngine
import app.line.media.MediaEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CallServiceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun legacyRegistrationRetriesOriginalAccountAndDoesNotSendV7Commands() {
        resetDevice()
        val peer = TestPeer()
        seedPeer(peer, establishSession = true)
        val signaling = TestSignaling(LOCAL_NUMBER, peer.bundle, legacyRegistration = true).also { it.start() }
        val running = bindService()
        try {
            configure(running, signaling)
            await { running.service.state.online }
            assertEquals(6, running.service.state.serverProtocol)
            assertEquals(LOCAL_NUMBER, running.service.state.number)
            assertEquals(2, signaling.registrationPackets.size)
            val first = signaling.registrationPackets[0]
            val retry = signaling.registrationPackets[1]
            assertEquals(7, first.getInt("protocolVersion"))
            assertEquals(setOf("type", "token", "bundle"), retry.keys().asSequence().toSet())
            assertEquals(first.getString("token"), retry.getString("token"))
            assertEquals(first.getJSONObject("bundle").toString(), retry.getJSONObject("bundle").toString())
            runBlocking { withContext(Dispatchers.Main) {
                running.service.verifyPeer(PEER_NUMBER)
                running.service.sendChat(PEER_NUMBER, "Legacy server online message")
            } }
            await { signaling.envelopeCount.get() == 1 }
            await { runBlocking { withContext(Dispatchers.IO) { SecureStore(context).use { store -> store.messages(PEER_NUMBER).any { it.text == "Legacy server online message" && it.status == "sent" } } } } }
            assertTrue(running.service.inboxSynchronized())
            assertFalse(signaling.commandTypes.any { it in setOf("push_register", "delivery_ack", "inbox_sync") })
        } finally { running.close(); signaling.close() }
    }

    @Test fun modernRegistrationKeepsProtocolSevenWithoutRetry() {
        resetDevice()
        val signaling = TestSignaling(LOCAL_NUMBER).also { it.start() }
        val running = bindService()
        try {
            configure(running, signaling)
            await { running.service.state.online }
            assertEquals(7, running.service.state.serverProtocol)
            assertEquals(1, signaling.registrationPackets.size)
            assertEquals(7, signaling.registrationPackets.single().getInt("protocolVersion"))
        } finally { running.close(); signaling.close() }
    }

    @Test fun conversationCallButtonReachesCallSetupAndConnectedState() {
        resetDevice()
        context.getSharedPreferences("line-ui", 0).edit().putString("language", "ru").commit()
        val peer = TestPeer()
        seedPeer(peer, establishSession = true)
        val signaling = TestSignaling(LOCAL_NUMBER, peer.bundle).also { it.start() }
        val running = bindService()
        val media = AtomicReference<WaitingMediaEngine?>()
        try {
            configure(running, signaling)
            running.service.mediaEngineFactory = { _, _, event -> WaitingMediaEngine(event).also(media::set) }
            grantMicrophone()
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).putExtra("peer", PEER_NUMBER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            instrumentation.runOnMainSync {
                fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
                nodes(running.activity.window.decorView).first { it.contentDescription?.toString() == "Позвонить контакту" }.performClick()
            }
            await { signaling.createCallCount.get() == 1 && media.get()?.connecting?.count == 0L }
            assertEquals(1, signaling.joinCount.get())
            media.get()!!.succeed()
            await { running.service.state.phase == Phase.CONNECTED }
            instrumentation.runOnMainSync { running.service.hangup() }
            await { running.service.state.phase == Phase.IDLE }
        } finally { running.close(); signaling.close() }
    }

    @Test fun staleConnectCannotReplaceCurrentClientAndReconfigureFailsPendingLookups() {
        resetDevice()
        val signaling = TestSignaling(LOCAL_NUMBER)
        signaling.start()
        val running = bindService()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val bundleCalls = AtomicInteger()
        val clients = CopyOnWriteArrayList<String>()
        try {
            running.service.httpClientFactory = { profile ->
                clients += profile.mediaUrl
                signaling.client()
            }
            running.service.publicBundleProvider = {
                if (bundleCalls.incrementAndGet() == 1) {
                    entered.countDown()
                    try {
                        withContext(Dispatchers.IO) { check(release.await(10, TimeUnit.SECONDS)) }
                    } finally {
                        firstReturned.countDown()
                    }
                }
                JSONObject()
            }

            instrumentation.runOnMainSync { running.service.configure(signaling.profile(MEDIA_ONE), true) }
            assertTrue("First connect reached its delayed bundle boundary", entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { running.service.configure(signaling.profile(MEDIA_TWO), true) }
            await { running.service.state.online }
            release.countDown()
            assertTrue("Stale connect completed", firstReturned.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(MEDIA_TWO), clients.toList())
            assertTrue(running.service.state.online)

            signaling.holdLookups = true
            val lookupFailed = CompletableDeferred<Throwable?>()
            val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            lookupScope.launch {
                try {
                    running.service.inspectPeer(PEER_NUMBER)
                    lookupFailed.complete(null)
                } catch (error: Exception) {
                    lookupFailed.complete(error)
                }
            }
            await { signaling.lookupCount.get() == 1 }
            instrumentation.runOnMainSync { running.service.configure(signaling.profile(MEDIA_THREE), true) }
            val failure = runBlocking { withTimeout(5_000) { lookupFailed.await() } }
            assertTrue("Reconfiguration fails outstanding lookup", failure is IllegalStateException)
            await { running.service.state.online && signaling.registrationCount.get() >= 2 }
            lookupScope.cancel()
        } finally {
            release.countDown()
            running.close()
            signaling.close()
        }
    }

    @Test fun hangupCancelsDialAndAcceptWaitsWithoutStoppingIncomingReceiver() {
        resetDevice()
        val peer = TestPeer()
        seedPeer(peer, establishSession = false)
        val signaling = TestSignaling(LOCAL_NUMBER, peer.bundle)
        signaling.start()
        val running = bindService()
        try {
            configure(running, signaling)
            grantMicrophone()
            signaling.holdLookups = true
            startCall("dial", PEER_NUMBER)
            await { running.service.state.phase == Phase.OUTGOING && signaling.lookupCount.get() == 1 }
            assertTrue(running.service.callOperationActive)

            instrumentation.runOnMainSync { running.service.hangup() }
            await { running.service.state.phase == Phase.IDLE && signaling.registrationCount.get() >= 2 }
            assertEquals("Cancelled dial never creates a call", 0, signaling.createCallCount.get())

            signaling.sendCapabilities(callsEnabled = true, chatEnabled = false)
            await { !running.service.state.chatEnabled }
            signaling.sendIncoming()
            await { running.service.state.phase == Phase.INCOMING }
            signaling.holdLookups = false
            startCall("accept")
            await { running.service.callOperationActive }

            instrumentation.runOnMainSync { running.service.hangup() }
            await { running.service.state.phase == Phase.IDLE && !running.service.callOperationActive }
            assertEquals("Accept cancelled while waiting for the room key", 0, signaling.joinCount.get())
            await { signaling.declineCount.get() == 1 }

            signaling.sendCapabilities(callsEnabled = true, chatEnabled = true)
            await { running.service.state.chatEnabled }
        } finally {
            running.close()
            signaling.close()
        }
    }

    @Test fun hangupCancelsRoomGrantMediaConnectAndReleasesEngine() {
        resetDevice()
        val peer = TestPeer()
        seedPeer(peer, establishSession = true)
        val signaling = TestSignaling(LOCAL_NUMBER, peer.bundle)
        signaling.start()
        val running = bindService()
        val media = AtomicReference<WaitingMediaEngine?>()
        try {
            configure(running, signaling)
            running.service.mediaEngineFactory = { _, _, onEvent -> WaitingMediaEngine(onEvent).also(media::set) }
            grantMicrophone()
            startCall("dial", PEER_NUMBER)
            assertTrue("Call reached media connect", awaitLatch(media, { it.connecting }, 20))
            await { running.service.state.phase == Phase.CONNECTING }
            assertEquals(1, signaling.createCallCount.get())
            assertEquals(1, signaling.joinCount.get())

            instrumentation.runOnMainSync { running.service.hangup() }
            assertTrue("Media connect coroutine was cancelled", awaitLatch(media, { it.cancelled }, 5))
            assertTrue("Media engine was disconnected", awaitLatch(media, { it.disconnected }, 5))
            await { running.service.state.phase == Phase.IDLE && !running.service.callOperationActive }
        } finally {
            running.close()
            signaling.close()
        }
    }

    @Test fun successfulMediaConnectEventDoesNotInvalidateCallOperation() {
        resetDevice()
        val peer = TestPeer()
        seedPeer(peer, establishSession = true)
        val signaling = TestSignaling(LOCAL_NUMBER, peer.bundle)
        signaling.start()
        val running = bindService()
        val media = AtomicReference<WaitingMediaEngine?>()
        try {
            configure(running, signaling)
            running.service.mediaEngineFactory = { _, _, onEvent -> WaitingMediaEngine(onEvent).also(media::set) }
            grantMicrophone()
            startCall("dial", PEER_NUMBER)
            await { media.get()?.connecting?.count == 0L }
            media.get()!!.succeed()
            await { running.service.state.phase == Phase.CONNECTED && !running.service.callOperationActive }
            instrumentation.runOnMainSync { running.service.hangup() }
            await { running.service.state.phase == Phase.IDLE }
        } finally {
            running.close()
            signaling.close()
        }
    }

    private fun resetDevice() {
        context.stopService(Intent(context, CallService::class.java))
        context.deleteDatabase("line-secure-store.db")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("${context.packageName}.line.secure-store.v1")
        context.getSharedPreferences("line", Context.MODE_PRIVATE).edit().clear()
            .putString("number", LOCAL_NUMBER).commit()
    }

    private fun seedPeer(peer: TestPeer, establishSession: Boolean) {
        SecureStore(context).use { store ->
            store.setLocalNumber(LOCAL_NUMBER)
            store.rememberPeer(PEER_NUMBER, JSONObject().put("identityKey", peer.identityKey))
            store.verifyPeer(PEER_NUMBER)
            if (establishSession) store.establishSession(PEER_NUMBER, peer.bundle)
        }
    }

    private fun bindService(): RunningService {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val ready = CountDownLatch(1)
        var service: CallService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as CallService.LocalBinder).service
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        assertTrue(context.bindService(Intent(context, CallService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue("CallService bound", ready.await(10, TimeUnit.SECONDS))
        val boundService = service ?: error("CallService binder unavailable")
        boundService.publicBundleProvider = { JSONObject() }
        return RunningService(activity, boundService, connection)
    }

    private fun configure(running: RunningService, signaling: TestSignaling) {
        running.service.httpClientFactory = { signaling.client() }
        instrumentation.runOnMainSync { running.service.configure(signaling.profile(), true) }
        await { running.service.state.online }
    }

    private fun grantMicrophone() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun startCall(action: String, peer: String? = null) {
        val intent = Intent(context, CallService::class.java).setAction(action)
        if (peer != null) intent.putStringArrayListExtra("members", arrayListOf(peer))
        instrumentation.runOnMainSync { context.startForegroundService(intent) }
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = check() }
            if (passed) return
            Thread.sleep(50)
        }
        fail("Call lifecycle did not reach expected state")
    }

    private fun <T> awaitLatch(reference: AtomicReference<T?>, latch: (T) -> CountDownLatch, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
        while (System.currentTimeMillis() < deadline) {
            reference.get()?.let { if (latch(it).count == 0L) return true }
            Thread.sleep(25)
        }
        return false
    }

    private inner class RunningService(
        val activity: MainActivity,
        val service: CallService,
        private val connection: ServiceConnection,
    ) {
        fun close() {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            context.unbindService(connection)
            context.stopService(Intent(context, CallService::class.java))
        }
    }

    private class TestPeer {
        private val identity = IdentityKeyPair.generate()
        private val registrationId = KeyHelper.generateRegistrationId(false)
        private val oneTime = ECKeyPair.generate()
        private val signed = ECKeyPair.generate()
        private val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        private val signedSignature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        private val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        val identityKey = encode(identity.publicKey.serialize())
        val bundle = JSONObject()
            .put("identityKey", identityKey)
            .put("registrationId", registrationId)
            .put("signedPreKey", JSONObject().put("id", 2).put("publicKey", encode(signed.publicKey.serialize()))
                .put("signature", encode(signedSignature)))
            .put("kyberPreKey", JSONObject().put("id", 3).put("publicKey", encode(kyber.publicKey.serialize()))
                .put("signature", encode(kyberSignature)))
            .put("preKey", JSONObject().put("id", 1).put("publicKey", encode(oneTime.publicKey.serialize())))

        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private inner class TestSignaling(
        private val number: String,
        private val peerBundle: JSONObject? = null,
        private val legacyRegistration: Boolean = false,
    ) : AutoCloseable {
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val pin = "sha256/" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded), Base64.NO_WRAP,
        )
        private val server = MockWebServer()
        private val activeSocket = AtomicReference<WebSocket?>()
        private val callId = AtomicReference("")
        private val callRoom = AtomicReference("")
        val registrationCount = AtomicInteger()
        val registrationPackets = CopyOnWriteArrayList<JSONObject>()
        val commandTypes = CopyOnWriteArrayList<String>()
        val lookupCount = AtomicInteger()
        val createCallCount = AtomicInteger()
        val envelopeCount = AtomicInteger()
        val joinCount = AtomicInteger()
        val declineCount = AtomicInteger()
        @Volatile var holdLookups = false

        val apiUrl: String get() = "wss://localhost:${server.port}/signal"

        fun start() {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(listener)
            }
            server.start()
        }

        fun profile(media: String = MEDIA_DEFAULT): EndpointConfig = EndpointConfig(apiUrl, pin, media, pin)

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", pin).build())
            .build()

        fun sendCapabilities(callsEnabled: Boolean, chatEnabled: Boolean) {
            activeSocket.get()?.send(JSONObject().put("type", "capabilities")
                .put("callsEnabled", callsEnabled).put("chatEnabled", chatEnabled)
                .put("mediaReady", true).put("maxParticipants", 8).toString())
        }

        fun sendIncoming() {
            val id = UUID.randomUUID().toString()
            activeSocket.get()?.send(JSONObject().put("type", "incoming").put("callId", id)
                .put("room", "incoming-$id").put("owner", PEER_NUMBER)
                .put("members", JSONArray().put(number).put(PEER_NUMBER)).toString())
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                activeSocket.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                activeSocket.set(webSocket)
                val message = JSONObject(text)
                commandTypes += message.getString("type")
                when (message.getString("type")) {
                    "register" -> {
                        registrationPackets += message
                        if (legacyRegistration && message.keys().asSequence().any { it !in setOf("type", "token", "bundle") }) {
                            webSocket.send(JSONObject().put("type", "error").put("code", "registration_required").toString())
                            return
                        }
                        registrationCount.incrementAndGet()
                        webSocket.send(JSONObject().put("type", "registered").put("number", number)
                            .put("mediaReady", true).put("callsEnabled", true).put("chatEnabled", true)
                            .put("maxParticipants", 8).toString())
                    }
                    "lookup" -> {
                        lookupCount.incrementAndGet()
                        if (!holdLookups && peerBundle != null) {
                            webSocket.send(JSONObject().put("type", "bundle").put("requestId", message.getString("requestId"))
                                .put("bundle", peerBundle).toString())
                        }
                    }
                    "create_call" -> {
                        createCallCount.incrementAndGet()
                        val id = UUID.randomUUID().toString()
                        val room = "line-$id"
                        callId.set(id); callRoom.set(room)
                        val members = JSONArray().put(number)
                        val requested = message.getJSONArray("members")
                        for (index in 0 until requested.length()) members.put(requested.getString(index))
                        webSocket.send(JSONObject().put("type", "call_created").put("callId", id).put("room", room)
                            .put("owner", number).put("members", members).toString())
                    }
                    "envelope" -> {
                        envelopeCount.incrementAndGet()
                        webSocket.send(JSONObject().put("type", "sent").put("id", message.getString("id")).toString())
                    }
                    "join_call" -> {
                        joinCount.incrementAndGet()
                        val id = callId.get()
                        val members = JSONArray().put(number).put(PEER_NUMBER)
                        webSocket.send(JSONObject().put("type", "room_grant").put("callId", id)
                            .put("room", callRoom.get()).put("owner", number).put("members", members)
                            .put("url", MEDIA_DEFAULT).put("token", "test-token").toString())
                    }
                    "decline_call" -> declineCount.incrementAndGet()
                }
            }
        }

        override fun close() { server.shutdown() }
    }

    private class WaitingMediaEngine(private val onEvent: (MediaEvent) -> Unit) : CallMediaEngine {
        val connecting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        private val finishConnect = CompletableDeferred<Unit>()

        override suspend fun connect(url: String, token: String, roomKey: ByteArray, http: OkHttpClient, highQuality: Boolean) {
            connecting.countDown()
            try {
                finishConnect.await()
                onEvent(MediaEvent.Connected)
            } catch (cancelled: CancellationException) {
                this@WaitingMediaEngine.cancelled.countDown()
                throw cancelled
            }
        }
        fun succeed() { finishConnect.complete(Unit) }
        override suspend fun setMuted(muted: Boolean) = Unit
        override fun setSpeaker(enabled: Boolean) = Unit
        override suspend fun disconnect() { disconnected.countDown() }
    }

    private companion object {
        const val LOCAL_NUMBER = "81818181"
        const val PEER_NUMBER = "91919191"
        const val MEDIA_DEFAULT = "wss://rtc.example.test"
        const val MEDIA_ONE = "wss://rtc-one.example.test"
        const val MEDIA_TWO = "wss://rtc-two.example.test"
        const val MEDIA_THREE = "wss://rtc-three.example.test"

        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
