package app.line.ui

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundPolicyTest {
    private val ringers = listOf(
        AudioManager.RINGER_MODE_SILENT,
        AudioManager.RINGER_MODE_VIBRATE,
        AudioManager.RINGER_MODE_NORMAL,
    )
    private val modes = listOf(
        AudioManager.MODE_NORMAL,
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
    )

    @Test fun playsWhenEnabledRingerNormalAndNoCallAudio() {
        assertTrue(UiSoundPolicy.shouldPlay(true, AudioManager.RINGER_MODE_NORMAL, AudioManager.MODE_NORMAL))
    }

    @Test fun neverPlaysWhenDisabled() {
        for (ringer in ringers) for (mode in modes) {
            assertFalse("ringer=$ringer mode=$mode", UiSoundPolicy.shouldPlay(false, ringer, mode))
            assertFalse("call cue, ringer=$ringer mode=$mode", UiSoundPolicy.shouldPlay(false, ringer, mode, communicationAllowed = true))
        }
    }

    @Test fun silentAndVibrateRingersMuteEveryCue() {
        val muted = listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)
        for (ringer in muted) for (mode in modes) {
            assertFalse("ringer=$ringer mode=$mode", UiSoundPolicy.shouldPlay(true, ringer, mode))
            assertFalse("call cue, ringer=$ringer mode=$mode", UiSoundPolicy.shouldPlay(true, ringer, mode, communicationAllowed = true))
        }
    }

    @Test fun ringingAndCallAudioMuteOrdinaryCues() {
        val busy = listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
        for (mode in busy) {
            assertFalse("mode=$mode", UiSoundPolicy.shouldPlay(true, AudioManager.RINGER_MODE_NORMAL, mode))
        }
    }

    @Test fun callCuesMayPlayOverVoipAudioButNotCellularCallsOrRinging() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        assertTrue(UiSoundPolicy.shouldPlay(true, normal, AudioManager.MODE_IN_COMMUNICATION, communicationAllowed = true))
        assertTrue(UiSoundPolicy.shouldPlay(true, normal, AudioManager.MODE_NORMAL, communicationAllowed = true))
        assertFalse(UiSoundPolicy.shouldPlay(true, normal, AudioManager.MODE_IN_CALL, communicationAllowed = true))
        assertFalse(UiSoundPolicy.shouldPlay(true, normal, AudioManager.MODE_RINGTONE, communicationAllowed = true))
    }

    @Test fun preferenceKeyIsStable() {
        assertEquals("ui_sounds", UiSounds.KEY)
    }
}
