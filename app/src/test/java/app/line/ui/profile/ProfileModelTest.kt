package app.line.ui.profile

import app.line.CallState
import app.line.Link
import org.junit.Assert.*
import org.junit.Test

class ProfileModelTest {
    @Test fun nameIsNormalisedAndComparedWithTheCurrentOne() {
        assertEquals(NameCheck.Unchanged, NameRules.check("  Ернур ", "Ернур"))
        assertEquals(NameCheck.Valid("Ернур Б"), NameRules.check("Ернур   Б", ""))
        assertEquals(NameCheck.Valid(""), NameRules.check("   ", "Ернур"))
        assertEquals(NameCheck.Unchanged, NameRules.check("", ""))
    }

    @Test fun nameProblemsAreTellable() {
        assertEquals(NameCheck.Invalid(tooLong = true), NameRules.check("а".repeat(NameRules.MAX + 1), ""))
        assertEquals(NameCheck.Invalid(tooLong = false), NameRules.check("Ер\u202Eнур", ""))
        assertEquals(NameCheck.Valid("а".repeat(NameRules.MAX)), NameRules.check("а".repeat(NameRules.MAX), ""))
        assertEquals(3, NameRules.length(" а\uD83D\uDE00б "))
    }

    @Test fun connectionStatusFollowsTheLink() {
        val base = CallState(configReady = true)
        assertEquals(ConnectionStatus.NOT_CONFIGURED, ProfileStatus.connection(CallState()))
        assertEquals(ConnectionStatus.ONLINE, ProfileStatus.connection(base.copy(online = true, link = Link.ONLINE)))
        assertEquals(ConnectionStatus.WAITING_NETWORK, ProfileStatus.connection(base.copy(link = Link.WAITING_NETWORK)))
        assertEquals(ConnectionStatus.CONNECTING, ProfileStatus.connection(base.copy(link = Link.CONNECTING)))
        assertEquals(ConnectionStatus.CONNECTING, ProfileStatus.connection(base.copy(link = Link.NONE)))
    }

    private fun push(
        distributors: List<String> = listOf("io.heckel.ntfy"),
        selected: String? = "io.heckel.ntfy",
        endpoint: Boolean = false,
        failure: String? = null,
        active: Boolean = false,
        online: Boolean = true,
        supports: Boolean = true,
    ) = PushStates.of(distributors, selected, endpoint, failure, active, online, supports)

    @Test fun pushStatusExplainsWhatIsMissing() {
        assertEquals(PushStatus.NO_DISTRIBUTOR, push(distributors = emptyList()))
        assertEquals(PushStatus.NOT_SELECTED, push(selected = null))
        assertEquals(PushStatus.NOT_SELECTED, push(selected = "gone.app"))
        assertEquals(PushStatus.ACTIVE, push(active = true, endpoint = true))
        assertEquals(PushStatus.FAILED, push(failure = "NETWORK"))
        assertEquals(PushStatus.WAITING, push(failure = "NETWORK", endpoint = true))
        assertEquals(PushStatus.SERVER_UNSUPPORTED, push(endpoint = true, supports = false))
        assertEquals(PushStatus.WAITING, push(endpoint = true, online = false, supports = false))
    }

    @Test fun languagesAreNamedNatively() {
        assertEquals("Русский", Languages.nativeName("ru"))
        assertEquals("English", Languages.nativeName("en"))
        assertEquals("Қазақша", Languages.nativeName("kk"))
    }
}
