package app.line.ui.calls

import app.line.ActivityEvent
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class RecentCallsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 100 * day + 12 * 3_600_000L

    private fun call(id: String, at: Long, peer: String = "22222222", incoming: Boolean = true, outcome: String = "missed", duration: Long = 0) =
        ActivityEvent(id, ActivityEvent.CALL, peer, incoming, outcome, at, duration)

    @Test fun headersSplitDaysAndNameTodayAndYesterday() {
        val items = RecentCalls.build(listOf(
            call("a", now - 1_000, outcome = "completed", duration = 30),
            call("b", now - day, outcome = "completed", duration = 10),
            call("c", now - 5 * day, outcome = "completed", duration = 5),
        ), now, utc)
        val headers = items.filterIsInstance<RecentItem.Header>()
        assertEquals(listOf(DayLabel.TODAY, DayLabel.YESTERDAY, DayLabel.OTHER), headers.map { it.label })
        assertEquals(6, items.size)
        assertTrue(items[0] is RecentItem.Header && items[1] is RecentItem.Row)
    }

    @Test fun repeatedMissedCallsFromTheSamePersonCollapse() {
        val items = RecentCalls.build(listOf(call("a", now - 1_000), call("b", now - 2_000), call("c", now - 3_000)), now, utc)
        val rows = items.filterIsInstance<RecentItem.Row>()
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].row.count)
        assertEquals("a", rows[0].row.key)
    }

    @Test fun answeredCallsNeverCollapse() {
        val items = RecentCalls.build(listOf(
            call("a", now - 1_000, outcome = "completed", duration = 20),
            call("b", now - 2_000, outcome = "completed", duration = 30),
        ), now, utc)
        assertEquals(2, items.filterIsInstance<RecentItem.Row>().size)
    }

    @Test fun differentPeersDirectionsOrOutcomesStaySeparate() {
        val items = RecentCalls.build(listOf(
            call("a", now - 1_000),
            call("b", now - 2_000, peer = "33333333"),
            call("c", now - 3_000, incoming = false, outcome = "failed"),
            call("d", now - 4_000, incoming = false, outcome = "declined"),
        ), now, utc)
        assertEquals(4, items.filterIsInstance<RecentItem.Row>().size)
    }

    @Test fun collapsingNeverCrossesMidnight() {
        val midnight = 100 * day
        val items = RecentCalls.build(listOf(call("a", midnight + 1_000), call("b", midnight - 1_000)), now, utc)
        assertEquals(2, items.filterIsInstance<RecentItem.Row>().size)
        assertEquals(2, items.filterIsInstance<RecentItem.Header>().size)
    }

    @Test fun timeZoneMovesTheDayBoundary() {
        val plus5 = TimeZone.getTimeZone("GMT+5")
        val at = 100 * day - 3_600_000L
        assertEquals(99L, RecentCalls.epochDay(at, utc))
        assertEquals(100L, RecentCalls.epochDay(at, plus5))
    }

    @Test fun groupCallsKeepTheirPeersAndMessagesAreIgnored() {
        val items = RecentCalls.build(listOf(
            call("g", now - 1_000, peer = "22222222,33333333", outcome = "completed", duration = 9),
            ActivityEvent("m", ActivityEvent.MESSAGE, "22222222", true, "received", now - 2_000),
        ), now, utc)
        val rows = items.filterIsInstance<RecentItem.Row>()
        assertEquals(1, rows.size)
        assertEquals(listOf("22222222", "33333333"), rows[0].row.peers)
        assertTrue(rows[0].row.group)
        assertTrue(RecentCalls.build(emptyList(), now, utc).isEmpty())
    }

    @Test fun unknownDurationOnMissedEventIsStillMissed() {
        assertEquals(OutcomeKind.MISSED, RecentCalls.kindOf(call("a", 1)))
        assertEquals(OutcomeKind.COMPLETED, RecentCalls.kindOf(call("a", 1, outcome = "failed", duration = 3)))
    }
}
