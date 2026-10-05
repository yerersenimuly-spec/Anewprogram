package app.line.ui.chat

import java.time.ZoneId

/** Decides day separators and which bubbles join into one visual group. */
object BubbleGrouping {
    const val GROUP_GAP_MS = 5 * 60_000L

    data class Meta(val outgoing: Boolean, val createdAt: Long)

    sealed interface Slot {
        data class Day(val key: Long, val at: Long) : Slot
        data class Message(val index: Int, val first: Boolean, val last: Boolean) : Slot
    }

    /** Messages join a group when the sender and the day are the same and they are less than [GROUP_GAP_MS] apart. */
    fun layout(items: List<Meta>, zone: ZoneId): List<Slot> {
        val result = ArrayList<Slot>(items.size + 4)
        val days = LongArray(items.size) { ChatDates.dayKey(items[it].createdAt, zone) }
        fun joined(a: Int, b: Int): Boolean =
            items[a].outgoing == items[b].outgoing && days[a] == days[b] &&
                items[b].createdAt - items[a].createdAt in 0 until GROUP_GAP_MS
        for (index in items.indices) {
            if (index == 0 || days[index] != days[index - 1]) result += Slot.Day(days[index], items[index].createdAt)
            val first = index == 0 || !joined(index - 1, index)
            val last = index == items.lastIndex || !joined(index, index + 1)
            result += Slot.Message(index, first, last)
        }
        return result
    }
}

/** Stable 64-bit ids for RecyclerView from string ids (FNV-1a). */
object StableIds {
    fun of(id: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (char in id) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** Day separators use the epoch day; a collision with a 64-bit message hash is practically impossible. */
    fun day(dayKey: Long): Long = dayKey
}
