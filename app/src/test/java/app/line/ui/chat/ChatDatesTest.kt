package app.line.ui.chat

import app.line.ui.chat.ChatDates.Bucket
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatDatesTest {
    private val zone = ZoneId.of("Asia/Almaty")
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 4, 15)

    @Test fun sameCalendarDayIsTodayEvenAcrossMidnightBoundaries() {
        assertEquals(Bucket.TODAY, ChatDates.listBucket(now, at(2026, 10, 4, 0, 1), zone))
        assertEquals(Bucket.YESTERDAY, ChatDates.listBucket(now, at(2026, 10, 3, 23, 59), zone))
    }

    @Test fun futureTimestampsFromClockSkewCountAsToday() {
        assertEquals(Bucket.TODAY, ChatDates.listBucket(now, at(2026, 10, 5, 9), zone))
    }

    @Test fun listUsesWeekdayWithinSevenDaysThenDates() {
        assertEquals(Bucket.THIS_WEEK, ChatDates.listBucket(now, at(2026, 9, 28), zone))
        assertEquals(Bucket.THIS_YEAR, ChatDates.listBucket(now, at(2026, 9, 27), zone))
        assertEquals(Bucket.OLDER, ChatDates.listBucket(now, at(2025, 12, 31), zone))
    }

    @Test fun separatorsNeverUseWeekdays() {
        assertEquals(Bucket.TODAY, ChatDates.separatorBucket(now, at(2026, 10, 4, 1), zone))
        assertEquals(Bucket.YESTERDAY, ChatDates.separatorBucket(now, at(2026, 10, 3), zone))
        assertEquals(Bucket.THIS_YEAR, ChatDates.separatorBucket(now, at(2026, 10, 1), zone))
        assertEquals(Bucket.OLDER, ChatDates.separatorBucket(now, at(2025, 10, 1), zone))
    }

    @Test fun weekAcrossNewYearIsNotMistakenForThisYear() {
        val january = at(2026, 1, 2)
        assertEquals(Bucket.THIS_WEEK, ChatDates.listBucket(january, at(2025, 12, 30), zone))
        assertEquals(Bucket.OLDER, ChatDates.separatorBucket(january, at(2025, 12, 30), zone))
    }

    @Test fun dayKeyFollowsTheLocalCalendarNotUtc() {
        val lateEvening = at(2026, 10, 4, 23, 30)
        val earlyMorning = at(2026, 10, 5, 0, 30)
        assertEquals(1L, ChatDates.dayKey(earlyMorning, zone) - ChatDates.dayKey(lateEvening, zone))
    }
}
