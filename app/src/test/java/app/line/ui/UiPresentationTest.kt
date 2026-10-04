package app.line.ui

import app.line.CallState
import org.junit.Assert.*
import org.junit.Test

class UiPresentationTest {
    @Test fun firstLaunchExplainsMissingNumberWithoutFakePlaceholder() {
        val state = CallState()
        assertEquals("Подключите аккаунт", UiPresentation.numberTitle(state))
        assertEquals("Номер появится после подключения к Line", UiPresentation.numberHint(state))
        assertFalse(UiPresentation.canCall(state))
    }

    @Test fun assignedNumberRemainsVisibleOfflineAndCallingRequiresMedia() {
        val state = CallState(number = "01234567", configReady = true)
        assertEquals("0123 4567", UiPresentation.numberTitle(state))
        assertFalse(UiPresentation.canCall(state.copy(online = true)))
        assertTrue(UiPresentation.canCall(state.copy(online = true, mediaReady = true)))
    }
}
