package app.line.ui.chat

import app.line.ui.chat.RecordGesture.Outcome
import app.line.ui.chat.RecordGesture.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordGestureTest {
    private fun gesture(towardStart: Int = 1) = RecordGesture(cancelDistance = 100f, lockDistance = 80f, towardStart = towardStart)

    @Test fun releasingInPlaceSends() {
        val g = gesture()
        g.press(500f, 500f)
        assertNull(g.move(490f, 498f))
        assertEquals(Outcome.SEND, g.release())
        assertEquals(State.IDLE, g.state)
    }

    @Test fun slidingPastTheCancelDistanceArmsCancelAndReleaseDiscards() {
        val g = gesture()
        g.press(500f, 500f)
        assertEquals(State.CANCEL_ARMED, g.move(390f, 500f))
        assertEquals(Outcome.CANCEL, g.release())
    }

    @Test fun slidingBackBeforeReleaseDisarmsCancel() {
        val g = gesture()
        g.press(500f, 500f)
        g.move(380f, 500f)
        assertEquals(State.HOLDING, g.move(470f, 500f))
        assertEquals(Outcome.SEND, g.release())
    }

    @Test fun slidingUpLocksAndReleaseKeepsRecording() {
        val g = gesture()
        g.press(500f, 500f)
        assertEquals(State.LOCKED, g.move(500f, 410f))
        assertNull(g.move(300f, 410f))
        assertEquals(Outcome.KEEP, g.release())
        assertEquals(State.LOCKED, g.state)
    }

    @Test fun progressFollowsTheFinger() {
        val g = gesture()
        g.press(500f, 500f)
        g.move(450f, 460f)
        assertEquals(0.5f, g.cancelProgress, 0.001f)
        assertEquals(0.5f, g.lockProgress, 0.001f)
    }

    @Test fun rightToLeftLayoutsCancelTowardsTheRight() {
        val g = gesture(towardStart = -1)
        g.press(100f, 500f)
        assertNull(g.move(40f, 500f))
        assertEquals(State.CANCEL_ARMED, g.move(210f, 500f))
    }

    @Test fun diagonalMovesPickTheDominantDirection() {
        val g = gesture()
        g.press(500f, 500f)
        // Past both thresholds: the larger travel relative to its distance wins.
        assertEquals(State.CANCEL_ARMED, g.move(380f, 415f))
    }
}
