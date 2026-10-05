package app.line.ui.onboarding

import app.line.ConnectionProfile
import app.line.EndpointConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ConnectionCodesTest {
    private val pin = "sha256/" + "A".repeat(43) + "="
    private val config = EndpointConfig("wss://api.example.org/signal", pin, "wss://rtc.example.org", pin)
    private val code = ConnectionProfile.encode(config)

    private fun problem(raw: String) = (ConnectionCodes.parse(raw) as ConnectionCodes.Result.Invalid).problem

    @Test fun acceptsAPlainCodeAndReportsTheHost() {
        val result = ConnectionCodes.parse(code) as ConnectionCodes.Result.Valid
        assertEquals(config, result.config)
        assertEquals("api.example.org", result.host)
        assertEquals(code, result.code)
    }

    @Test fun toleratesWhatMessengersAndClipboardsAdd() {
        assertEquals(code, (ConnectionCodes.parse("  $code\n") as ConnectionCodes.Result.Valid).code)
        assertEquals(code, (ConnectionCodes.parse("\"$code\"") as ConnectionCodes.Result.Valid).code)
        assertEquals(code, (ConnectionCodes.parse("Код подключения: $code Не передавайте его другим.") as ConnectionCodes.Result.Valid).code)
        val wrapped = code.chunked(40).joinToString("\n")
        assertEquals(code, (ConnectionCodes.parse(wrapped) as ConnectionCodes.Result.Valid).code)
        assertEquals(code, (ConnectionCodes.parse("<$code>") as ConnectionCodes.Result.Valid).code)
    }

    @Test fun explainsWhyACodeIsRejected() {
        assertEquals(ConnectionCodes.Problem.EMPTY, problem(""))
        assertEquals(ConnectionCodes.Problem.EMPTY, problem("   \n"))
        assertEquals(ConnectionCodes.Problem.NOT_A_CODE, problem("wss://api.example.org/signal"))
        assertEquals(ConnectionCodes.Problem.NOT_A_CODE, problem("hello"))
        assertEquals(ConnectionCodes.Problem.DAMAGED, problem(code.dropLast(7)))
        assertEquals(ConnectionCodes.Problem.DAMAGED, problem("LINE1.invalid"))
        assertEquals(ConnectionCodes.Problem.TOO_LONG, problem("LINE1." + "A".repeat(4100)))
    }

    @Test fun rejectsOtherVersionsAndMalformedProfiles() {
        fun encode(json: String) = "LINE1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertEquals(ConnectionCodes.Problem.UNSUPPORTED, problem(encode("""{"version":2}""")))
        assertEquals(ConnectionCodes.Problem.INVALID, problem(encode("""{"version":1,"api":"https://x.org/signal"}""")))
        assertEquals(ConnectionCodes.Problem.INVALID, problem(encode(
            """{"version":1,"api":"ws://api.example.org/signal","apiPins":"$pin","media":"wss://rtc.example.org","mediaPins":"$pin"}""")))
    }

    @Test fun hostComesFromTheApiAddress() {
        assertEquals("api.example.org", ConnectionCodes.hostOf(config))
        assertNull(ConnectionCodes.hostOf(config.copy(apiUrl = "not a url")))
    }
}
