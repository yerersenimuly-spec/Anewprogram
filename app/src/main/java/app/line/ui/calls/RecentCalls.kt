package app.line.ui.calls

import app.line.ActivityEvent
import java.util.TimeZone

enum class DayLabel { TODAY, YESTERDAY, OTHER }

/** One row of the recents list: consecutive identical unanswered calls collapse into one row with a count. */
data class RecentRow(
    val latest: ActivityEvent,
    val peers: List<String>,
    val kind: OutcomeKind,
    val count: Int,
) {
    val incoming: Boolean get() = latest.incoming
    val group: Boolean get() = peers.size > 1
    val durationSeconds: Long get() = latest.durationSeconds
    val key: String get() = latest.id
}

sealed interface RecentItem {
    data class Header(val epochDay: Long, val label: DayLabel, val timestamp: Long) : RecentItem
    data class Row(val row: RecentRow) : RecentItem
}

object RecentCalls {
    private const val DAY_MS = 86_400_000L
    private val collapsible = setOf(OutcomeKind.MISSED, OutcomeKind.DECLINED, OutcomeKind.CANCELLED, OutcomeKind.FAILED)

    fun peersOf(event: ActivityEvent): List<String> = event.peer.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun kindOf(event: ActivityEvent): OutcomeKind = CallOutcomes.kind(event.outcome, event.durationSeconds)

    fun epochDay(timestamp: Long, zone: TimeZone): Long = Math.floorDiv(timestamp + zone.getOffset(timestamp), DAY_MS)

    fun label(day: Long, today: Long): DayLabel = when (day) {
        today -> DayLabel.TODAY
        today - 1 -> DayLabel.YESTERDAY
        else -> DayLabel.OTHER
    }

    /** [events] are newest first, as the store returns them. */
    fun build(events: List<ActivityEvent>, now: Long, zone: TimeZone): List<RecentItem> {
        val today = epochDay(now, zone)
        val items = ArrayList<RecentItem>(events.size + 8)
        var day = Long.MIN_VALUE
        var open: RecentRow? = null

        fun flush() {
            open?.let { items += RecentItem.Row(it) }
            open = null
        }

        for (event in events) {
            if (event.kind != ActivityEvent.CALL) continue
            val eventDay = epochDay(event.timestamp, zone)
            if (eventDay != day) {
                flush()
                day = eventDay
                items += RecentItem.Header(eventDay, label(eventDay, today), event.timestamp)
            }
            val kind = kindOf(event)
            val peers = peersOf(event)
            val current = open
            if (current != null && kind in collapsible && current.kind == kind &&
                current.incoming == event.incoming && current.peers == peers) {
                open = current.copy(count = current.count + 1)
            } else {
                flush()
                open = RecentRow(event, peers, kind, 1)
            }
        }
        flush()
        return items
    }
}
