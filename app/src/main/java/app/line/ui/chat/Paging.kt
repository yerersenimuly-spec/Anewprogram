package app.line.ui.chat

import app.line.crypto.ChatMessage
import app.line.crypto.Conversation

/** Merging of loaded pages with refreshed ones, so live updates never drop what the user scrolled back to. */
object Paging {
    const val PAGE = 40

    /** Newest [fresh] messages replace the overlapping tail; older loaded history stays untouched. */
    fun refreshMessages(current: List<ChatMessage>, fresh: List<ChatMessage>): List<ChatMessage> {
        if (fresh.isEmpty()) return fresh
        val boundary = fresh.first().sequence
        return current.filter { it.sequence < boundary } + fresh
    }

    /** [older] is a page that precedes everything in [current]. */
    fun prependMessages(current: List<ChatMessage>, older: List<ChatMessage>): List<ChatMessage> {
        if (older.isEmpty()) return current
        val known = current.mapTo(HashSet(current.size)) { it.id }
        return older.filter { it.id !in known } + current
    }

    /** Dialogs are ordered by their last message, newest first; a dialog is listed once. */
    fun appendConversations(current: List<Conversation>, older: List<Conversation>): List<Conversation> {
        val known = current.mapTo(HashSet(current.size)) { it.peer }
        return current + older.filter { it.peer !in known }
    }

    fun hasMore(pageSize: Int): Boolean = pageSize >= PAGE
}
