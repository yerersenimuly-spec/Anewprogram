package app.line

import org.junit.Assert.*
import org.junit.Test

class EndpointConfigTest {
    private val pin = "sha256/" + "A".repeat(43) + "="

    @Test fun secureExplicitMediaEndpointAndPinsRequired() {
        EndpointConfig("wss://api.example/signal", pin, "wss://rtc.example", pin).validate()
        assertFalse(EndpointConfig.validMediaUrl("ws://rtc.example"))
        assertFalse(EndpointConfig.validMediaUrl("wss://user@rtc.example"))
        assertFalse(EndpointConfig.validMediaUrl("wss://rtc.example?token=secret"))
        assertFalse(EndpointConfig.validMediaUrl("wss://rtc.example/room"))
        assertThrows(IllegalArgumentException::class.java) { EndpointConfig.pins("") }
        assertThrows(IllegalArgumentException::class.java) { EndpointConfig.pins("sha256/fake") }
        assertEquals(2, EndpointConfig.pins("$pin,$pin").size)
    }
}
