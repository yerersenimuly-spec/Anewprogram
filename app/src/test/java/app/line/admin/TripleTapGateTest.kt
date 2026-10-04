package app.line.admin

import org.junit.Assert.*
import org.junit.Test

class TripleTapGateTest {
    @Test fun requiresThreeTapsWithinWindowAndResets() {
        val gate = TripleTapGate()
        assertFalse(gate.tap(100)); assertFalse(gate.tap(400)); assertTrue(gate.tap(900))
        assertFalse(gate.tap(1000)); assertFalse(gate.tap(3000)); assertFalse(gate.tap(3300)); assertTrue(gate.tap(3600))
        gate.reset(); assertFalse(gate.tap(3700))
    }
}
