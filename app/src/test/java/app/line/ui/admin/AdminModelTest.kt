package app.line.ui.admin

import app.line.core.NumberInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdminModelTest {
    private val status = JSONObject("""
        {"settings":{"callsEnabled":true,"chatEnabled":false,"registrationEnabled":true,"maxParticipants":5},
         "metrics":{"online":3,"registered":40,"activeCalls":1,"mediaConfigured":true,"uptimeSeconds":3600},
         "blockedNumbers":["11111111","22222222"],
         "calls":[{"id":"c1","participantCount":3},{"participantCount":2}],
         "events":[{"at":"t1","event":"admin_login","outcome":"ok"},{"at":"t2","event":"settings_updated"}]}
    """)

    @Test fun parsesTheServerStatus() {
        val parsed = AdminStatus.parse(status)
        assertEquals(AdminSettings(callsEnabled = true, chatEnabled = false, registrationEnabled = true, maxParticipants = 5), parsed.settings)
        assertEquals(AdminMetrics(3, 40, 1, true, 3600L), parsed.metrics)
        assertEquals(listOf("11111111", "22222222"), parsed.blocked)
        assertEquals(listOf(AdminCall("c1", 3)), parsed.calls)
        assertEquals(listOf("t1", "t2"), parsed.events.map { it.at })
        assertEquals("", parsed.events[1].outcome)
    }

    @Test fun toleratesOlderAndSparseServers() {
        val parsed = AdminStatus.parse(JSONObject("{}"))
        assertTrue(parsed.settings.callsEnabled)
        assertEquals(8, parsed.settings.maxParticipants)
        assertNull(parsed.metrics.uptimeSeconds)
        assertTrue(parsed.blocked.isEmpty() && parsed.calls.isEmpty() && parsed.events.isEmpty())
        assertEquals(8, AdminSettings.from(JSONObject("""{"maxParticipants":99}""")).maxParticipants)
        assertEquals(2, AdminSettings.from(JSONObject("""{"maxParticipants":0}""")).maxParticipants)
    }

    @Test fun settingsSerialiseForTheUpdateCommand() {
        val json = AdminSettings(false, true, false, 4).toJson()
        assertFalse(json.getBoolean("callsEnabled")); assertTrue(json.getBoolean("chatEnabled"))
        assertFalse(json.getBoolean("registrationEnabled")); assertEquals(4, json.getInt("maxParticipants"))
    }

    @Test fun secretLengthMatchesTheService() {
        assertFalse(AdminRules.secretValid("a".repeat(11)))
        assertTrue(AdminRules.secretValid("a".repeat(12)))
        assertTrue(AdminRules.secretValid("a".repeat(128)))
        assertFalse(AdminRules.secretValid("a".repeat(129)))
    }

    @Test fun sessionNeverOutlivesTheFiveMinuteCap() {
        assertEquals(1_300_000L, AdminRules.sessionEnd(1_300_000, 1_000_000))
        assertEquals(1_300_000L, AdminRules.sessionEnd(9_999_999, 1_000_000))
        assertEquals(300, AdminRules.remainingSeconds(1_300_000, 1_000_000))
        assertEquals(1, AdminRules.remainingSeconds(1_000_001, 1_000_000))
        assertEquals(0, AdminRules.remainingSeconds(900_000, 1_000_000))
        assertEquals("4:05", AdminRules.clock(245)); assertEquals("0:09", AdminRules.clock(9))
    }

    @Test fun blockedNumbersAreValidatedLikeDialledOnes() {
        assertEquals(NumberInput.Result.Valid(listOf("12345678")), AdminRules.number("1234 5678"))
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.FORMAT), AdminRules.number("1234"))
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.EMPTY), AdminRules.number(" "))
    }

    @Test fun logShowsTheNewestTwentyFirst() {
        val events = (1..30).map { AdminEvent("t$it", "e", "") }
        val shown = AdminRules.lastEvents(events)
        assertEquals(20, shown.size)
        assertEquals("t30", shown.first().at); assertEquals("t11", shown.last().at)
    }

    @Test fun warnsAboutEndingCallsOnlyWhenItIsTrue() {
        val on = AdminSettings(true, true, true, 8)
        assertTrue(AdminRules.endsCalls(on, on.copy(callsEnabled = false), 2))
        assertTrue(AdminRules.endsCalls(on, on.copy(maxParticipants = 4), 1))
        assertFalse(AdminRules.endsCalls(on, on.copy(callsEnabled = false), 0))
        assertFalse(AdminRules.endsCalls(on, on.copy(chatEnabled = false), 3))
        assertTrue(AdminRules.dirty(on, on.copy(chatEnabled = false))); assertFalse(AdminRules.dirty(on, on))
    }

    @Test fun serviceMessagesAreClassifiedForLocalisation() {
        assertEquals(AdminErrors.Kind.INVALID_CODE, AdminErrors.classify("Неверный секретный код"))
        assertEquals(AdminErrors.Kind.RATE_LIMITED, AdminErrors.classify("Слишком много попыток. Подождите минуту"))
        assertEquals(AdminErrors.Kind.NOT_CONFIGURED, AdminErrors.classify("Админ-доступ не настроен на сервере"))
        assertEquals(AdminErrors.Kind.EXPIRED, AdminErrors.classify("Войдите в админ-панель заново"))
        assertEquals(AdminErrors.Kind.EXPIRED, AdminErrors.classify("Админ-сессия закрыта"))
        assertEquals(AdminErrors.Kind.REJECTED, AdminErrors.classify("Сервер отклонил действие"))
        assertEquals(AdminErrors.Kind.CANCELLED, AdminErrors.classify("Вход отменён"))
        assertEquals(AdminErrors.Kind.OTHER, AdminErrors.classify("whatever"))
        assertEquals(AdminErrors.Kind.OTHER, AdminErrors.classify(null))
    }
}
