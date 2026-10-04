package app.line.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPolicyTest {
    @Test fun choosesHighestSupportedRateThrough120Hz() {
        assertEquals(60f, RefreshPolicy.preferredRate(listOf(60f), 60f), 0f)
        assertEquals(90f, RefreshPolicy.preferredRate(listOf(60f, 90f), 60f), 0f)
        assertEquals(120f, RefreshPolicy.preferredRate(listOf(60f, 90f, 120f), 60f), 0f)
    }

    @Test fun caps144HzDisplayAt120Hz() {
        assertEquals(120f, RefreshPolicy.preferredRate(listOf(60f, 90f, 120f, 144f), 60f), 0f)
    }

    @Test fun preservesCurrentRateInsteadOfFallingBackWhenOnlyHigherModeExists() {
        assertEquals(144f, RefreshPolicy.preferredRate(listOf(144f), 144f), 0f)
        assertEquals(0f, RefreshPolicy.preferredRate(listOf(144f), 60f), 0f)
    }

    @Test fun considersOnlySeamlessModesAtCurrentResolution() {
        val modes = listOf(
            RefreshPolicy.ModeRate(1080, 2400, 60f),
            RefreshPolicy.ModeRate(1080, 2400, 90f),
            RefreshPolicy.ModeRate(1080, 2400, 120f),
            RefreshPolicy.ModeRate(1080, 2400, 144f, seamless = false),
            RefreshPolicy.ModeRate(720, 1600, 120f),
        )

        val rates = RefreshPolicy.ratesForResolution(modes, 1080, 2400)

        assertEquals(listOf(60f, 90f, 120f), rates)
        assertEquals(120f, RefreshPolicy.preferredRate(rates, 60f), 0f)
    }

    @Test fun ignoresInvalidRates() {
        assertEquals(90f, RefreshPolicy.preferredRate(listOf(Float.NaN, -1f, 90f), 60f), 0f)
    }
}
