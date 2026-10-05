package app.line.media.attachments

import java.io.IOException

/** Recording or playback of a voice message failed; [reason] says what the UI should tell the user. */
class VoiceRecorderException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** RECORD_AUDIO has not been granted. */
        PERMISSION,

        /** The microphone is in use (for example during a call) or the recorder refused to start. */
        BUSY,

        /** The output file could not be written. */
        STORAGE,

        /** The call does not fit the current state, for example starting twice. */
        STATE,

        /** The recorded file could not be played. */
        PLAYBACK,

        FAILED,
    }
}
