package app.line.ui.chat

import app.line.ui.chat.BubbleGrouping.Meta
import app.line.ui.chat.BubbleGrouping.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BubbleGroupingTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun messages(slots: List<Slot>) = slots.filterIsInstance<Slot.Message>()

    @Test fun emptyHistoryHasNoSlots() {
        assertTrue(BubbleGrouping.layout(emptyList(), zone).isEmpty())
    }

    @Test fun firstMessageOpensADaySeparator() {
        val slots = BubbleGrouping.layout(listOf(Meta(true, at(4, 10, 0))), zone)
        assertTrue(slots[0] is Slot.Day)
        assertEquals(Slot.Message(0, first = true, last = true), slots[1])
    }

    @Test fun sameSenderWithinFiveMinutesFormsOneGroup() {
        val slots = messages(BubbleGrouping.layout(listOf(
            Meta(false, at(4, 10, 0)), Meta(false, at(4, 10, 2)), Meta(false, at(4, 10, 4)),
        ), zone))
        assertEquals(listOf(true, false, false), slots.map { it.first })
        assertEquals(listOf(false, false, true), slots.map { it.last })
    }

    @Test fun senderChangeAndLongPausesBreakGroups() {
        val slots = messages(BubbleGrouping.layout(listOf(
            Meta(false, at(4, 10, 0)), Meta(true, at(4, 10, 1)), Meta(true, at(4, 10, 7)),
        ), zone))
        assertEquals(listOf(true, true, true), slots.map { it.first })
        assertEquals(listOf(true, true, true), slots.map { it.last })
    }

    @Test fun groupsNeverSpanMidnightAndEachDayGetsASeparator() {
        val slots = BubbleGrouping.layout(listOf(
            Meta(true, at(4, 23, 58)), Meta(true, at(5, 0, 1)),
        ), zone)
        assertEquals(2, slots.count { it is Slot.Day })
        val items = messages(slots)
        assertTrue(items[0].last && items[1].first)
    }

    @Test fun stableIdsDifferPerMessageAndAreRepeatable() {
        val a = "6f1f1a3e-0f39-4c9e-a5a5-3d3a8b1b2c01"
        val b = "6f1f1a3e-0f39-4c9e-a5a5-3d3a8b1b2c02"
        assertEquals(StableIds.of(a), StableIds.of(a))
        assertNotEquals(StableIds.of(a), StableIds.of(b))
    }
}
