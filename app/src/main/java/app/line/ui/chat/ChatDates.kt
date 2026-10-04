package app.line.ui.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar rules for chat lists and day separators. Pure, so they are tested on the JVM. */
object ChatDates {
    enum class Bucket { TODAY, YESTERDAY, THIS_WEEK, THIS_YEAR, OLDER }

    fun dayOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun dayKey(millis: Long, zone: ZoneId): Long = dayOf(millis, zone).toEpochDay()

    /** How the chat list stamps a dialog: time today, "yesterday", weekday within a week, then dates. */
    fun listBucket(nowMillis: Long, thenMillis: Long, zone: ZoneId): Bucket {
        val today = dayOf(nowMillis, zone)
        val day = dayOf(thenMillis, zone)
        val age = today.toEpochDay() - day.toEpochDay()
        return when {
            age <= 0 -> Bucket.TODAY
            age == 1L -> Bucket.YESTERDAY
            age < 7 -> Bucket.THIS_WEEK
            day.year == today.year -> Bucket.THIS_YEAR
            else -> Bucket.OLDER
        }
    }

    /** Day separators inside a conversation: today, yesterday, then a date (with the year when it is not the current one). */
    fun separatorBucket(nowMillis: Long, thenMillis: Long, zone: ZoneId): Bucket {
        val today = dayOf(nowMillis, zone)
        val day = dayOf(thenMillis, zone)
        val age = today.toEpochDay() - day.toEpochDay()
        return when {
            age <= 0 -> Bucket.TODAY
            age == 1L -> Bucket.YESTERDAY
            day.year == today.year -> Bucket.THIS_YEAR
            else -> Bucket.OLDER
        }
    }
}
