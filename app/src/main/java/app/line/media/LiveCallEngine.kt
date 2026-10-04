package app.line.media

import android.content.Context
import android.util.Base64
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.util.LoggingLevel
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Audio-only LiveKit room engine. The caller is responsible for distributing the room key securely. */
interface CallMediaEngine {
    suspend fun connect(url: String, token: String, roomKey: ByteArray, http: OkHttpClient, highQuality: Boolean = true)
    suspend fun setMuted(muted: Boolean)
    fun setSpeaker(enabled: Boolean)
    suspend fun disconnect()
}

class LiveCallEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val onEvent: (MediaEvent) -> Unit,
) : CallMediaEngine {
    private class Session(
        val room: Room,
        val keyProvider: BaseKeyProvider,
    ) {
        val stopped = AtomicBoolean(false)
        val connected = AtomicBoolean(false)
        var eventsJob: Job? = null
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var speakerphoneEnabled = false

    /** Connect and publish microphone audio. `roomKey` must be the same device-generated key for all room members. */
    override suspend fun connect(
        url: String,
        token: String,
        roomKey: ByteArray,
        http: OkHttpClient,
        highQuality: Boolean,
    ) {
        LiveKit.loggingLevel = LoggingLevel.OFF
        LiveKit.enableWebRTCLogging = false
        require(isSecureRoomUrl(url)) { "LiveKit requires a secure room URL" }
        require(token.isNotBlank()) { "A LiveKit participant token is required" }
        require(roomKey.size == ROOM_KEY_BYTES) { "Room key must be 256 bits" }
        synchronized(lock) {
            check(session == null) { "A call is already active" }
        }

        val provider = BaseKeyProvider()
        val encodedKey = Base64.encodeToString(roomKey, Base64.NO_WRAP)
        check(provider.setSharedKey(encodedKey)) { "Could not initialize call encryption" }

        val options = RoomOptions(
            e2eeOptions = E2EEOptions(
                keyProvider = provider,
            ),
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                echoCancellation = true,
                noiseSuppression = true,
                autoGainControl = true,
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                // This is an encoder ceiling; available bandwidth and the device still determine the actual rate.
                audioBitrate = if (highQuality) OPUS_MAX_BITRATE_BPS else SPEECH_BITRATE_BPS,
                dtx = !highQuality,
                red = true,
            ),
        )

        val room = try {
            LiveKit.create(
                appContext = appContext,
                options = options,
                overrides = LiveKitOverrides(okHttpClient = http),
            ).also { configureAudio(it) }
        } catch (error: Exception) {
            emit(MediaEvent.Failed(ENCRYPTION_SETUP_FAILED))
            return
        }
        val call = Session(room, provider)
        synchronized(lock) {
            if (session != null) {
                room.release()
                throw IllegalStateException("A call is already active")
            }
            session = call
        }
        call.eventsJob = scope.launch { observe(call) }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                room.connect(url, token, ConnectOptions(audio = true, video = false))
            }
            announceConnected(call)
        } catch (timeout: TimeoutCancellationException) {
            stop(call, MediaEvent.Failed(CONNECTION_FAILED), disconnectRoom = true)
        } catch (cancelled: CancellationException) {
            stop(call, null, disconnectRoom = true)
            throw cancelled
        } catch (error: Exception) {
            stop(call, MediaEvent.Failed(CONNECTION_FAILED), disconnectRoom = true)
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        val call = session ?: return
        try {
            if (!call.room.localParticipant.setMicrophoneEnabled(!muted)) {
                stop(call, MediaEvent.Failed(MICROPHONE_FAILED), disconnectRoom = true)
            }
        } catch (error: Exception) {
            stop(call, MediaEvent.Failed(MICROPHONE_FAILED), disconnectRoom = true)
        }
    }

    override fun setSpeaker(enabled: Boolean) {
        speakerphoneEnabled = enabled
        session?.room?.audioSwitchHandler?.let { configureAudio(it, enabled) }
    }

    override suspend fun disconnect() {
        session?.let {
            stop(it, MediaEvent.Disconnected(LOCAL_DISCONNECT), disconnectRoom = true)
        }
    }

    private suspend fun observe(call: Session) {
        call.room.events.collect { event ->
            if (call.stopped.get()) return@collect
            when (event) {
                is RoomEvent.Connected -> Unit
                is RoomEvent.Reconnecting -> stop(call, MediaEvent.Disconnected("CONNECTION_LOST"), disconnectRoom = true)
                is RoomEvent.ParticipantConnected,
                is RoomEvent.ParticipantDisconnected,
                -> if (call.connected.get()) emitParticipants(call)
                is RoomEvent.Disconnected -> stop(
                    call,
                    MediaEvent.Disconnected(event.reason.name),
                    disconnectRoom = false,
                )
                is RoomEvent.FailedToConnect -> stop(
                    call,
                    MediaEvent.Failed(CONNECTION_FAILED),
                    disconnectRoom = true,
                )
                is RoomEvent.TrackE2EEStateEvent -> if (event.state in ENCRYPTION_FAILURE_STATES) {
                    stop(call, MediaEvent.Failed(ENCRYPTION_FAILED), disconnectRoom = true)
                }
                else -> Unit
            }
        }
    }

    private fun announceConnected(call: Session) {
        if (call.stopped.get() || !call.connected.compareAndSet(false, true)) return
        emit(MediaEvent.Connected)
        emitParticipants(call)
    }

    private fun emitParticipants(call: Session) {
        val identities = call.room.remoteParticipants.values
            .mapNotNull { it.identity?.value }
            .sorted()
        val local = call.room.localParticipant.identity?.value
        emit(MediaEvent.Participants((listOfNotNull(local) + identities).distinct()))
    }

    private fun configureAudio(room: Room) {
        room.audioSwitchHandler?.let { configureAudio(it, speakerphoneEnabled) }
    }

    private fun configureAudio(handler: AudioSwitchHandler, speaker: Boolean) {
        handler.loggingEnabled = false
        handler.preferredDeviceList = if (speaker) {
            listOf(
                AudioDevice.Speakerphone::class.java,
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
            )
        } else {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java,
            )
        }
        handler.selectDevice(null)
    }

    private fun stop(call: Session, event: MediaEvent?, disconnectRoom: Boolean) {
        if (!call.stopped.compareAndSet(false, true)) return
        synchronized(lock) {
            if (session === call) session = null
        }
        call.eventsJob?.cancel()
        try {
            if (disconnectRoom) call.room.disconnect()
        } catch (_: Exception) {
            // Continue releasing local media resources even when signaling is already gone.
        } finally {
            runCatching { call.room.release() }
        }
        if (event != null) emit(event)
    }

    private fun emit(event: MediaEvent) {
        runCatching { onEvent(event) }
    }

    private fun isSecureRoomUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.path.isEmpty() || uri.path == "/")
    }.getOrDefault(false)

    private companion object {
        const val ROOM_KEY_BYTES = 32
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val OPUS_MAX_BITRATE_BPS = 510_000
        const val SPEECH_BITRATE_BPS = 64_000
        const val ENCRYPTION_SETUP_FAILED = "Could not initialize end-to-end encryption"
        const val CONNECTION_FAILED = "Secure call connection failed"
        const val MICROPHONE_FAILED = "Could not update microphone state"
        const val ENCRYPTION_FAILED = "End-to-end audio encryption failed"
        const val LOCAL_DISCONNECT = "LOCAL"
        val ENCRYPTION_FAILURE_STATES = setOf(
            E2EEState.MISSING_KEY,
            E2EEState.ENCRYPTION_FAILED,
            E2EEState.DECRYPTION_FAILED,
            E2EEState.INTERNAL_ERROR,
        )
    }
}
