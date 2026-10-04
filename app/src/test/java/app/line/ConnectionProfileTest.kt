package app.line

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileTest {
    private val pin = "sha256/" + "A".repeat(43) + "="
    @Test fun preservesBothPinnedEndpoints() {
        val config = EndpointConfig("wss://api.example.org/signal", pin, "wss://rtc.example.org", pin)
        assertEquals(config, ConnectionProfile.decode(ConnectionProfile.encode(config)))
    }
    @Test fun rejectsInvalidAndOversizedCodes() {
        listOf("", "wss://your-server.com/signal", "LINE1.invalid", "LINE1." + "A".repeat(4097)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectionProfile.decode(it) }
        }
    }
}
