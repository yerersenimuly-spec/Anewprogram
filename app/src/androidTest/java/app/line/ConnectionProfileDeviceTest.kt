package app.line

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionProfileDeviceTest {
    @Test fun profileRoundTripPreservesBothPinnedEndpoints() {
        val pin = "sha256/" + "A".repeat(43) + "="
        val config = EndpointConfig("wss://api.example.org/signal", pin, "wss://rtc.example.org", pin)
        assertEquals(config, ConnectionProfile.decode(ConnectionProfile.encode(config)))
    }
}
