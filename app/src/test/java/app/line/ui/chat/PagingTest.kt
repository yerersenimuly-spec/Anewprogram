package app.line.ui.chat

import app.line.crypto.ChatMessage
import app.line.crypto.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTest {
    private fun message(sequence: Long, status: String = "sent") =
        ChatMessage("id-$sequence", "87654321", "text $sequence", true, status, sequence, sequence * 1000)

    @Test fun refreshReplacesTheOverlappingTailAndKeepsOlderHistory() {
        val current = (1L..6L).map { message(it) }
        val fresh = (5L..8L).map { message(it, if (it == 5L) "read" else "sent") }
        val merged = Paging.refreshMessages(current, fresh)
        assertEquals((1L..8L).toList(), merged.map { it.sequence })
        assertEquals("read", merged[4].status)
    }

    @Test fun refreshDropsMessagesThatNoLongerExist() {
        val current = (1L..4L).map { message(it) }
        val fresh = listOf(message(1), message(2), message(4))
        assertEquals(listOf(1L, 2L, 4L), Paging.refreshMessages(current, fresh).map { it.sequence })
    }

    @Test fun clearedHistoryRefreshesToNothing() {
        assertTrue(Paging.refreshMessages(listOf(message(1)), emptyList()).isEmpty())
    }

    @Test fun olderPagesArePrependedWithoutDuplicates() {
        val current = (5L..8L).map { message(it) }
        val older = (2L..5L).map { message(it) }
        assertEquals((2L..8L).toList(), Paging.prependMessages(current, older).map { it.sequence })
    }

    @Test fun dialogsAreAppendedOnceByPeer() {
        fun dialog(peer: String, sequence: Long) = Conversation(
            ChatMessage("m$sequence", peer, "t", false, "received", sequence, sequence), 0,
        )
        val merged = Paging.appendConversations(
            listOf(dialog("11111111", 9), dialog("22222222", 8)),
            listOf(dialog("22222222", 3), dialog("33333333", 2)),
        )
        assertEquals(listOf("11111111", "22222222", "33333333"), merged.map { it.peer })
    }

    @Test fun aFullPageMeansThereMayBeMore() {
        assertTrue(Paging.hasMore(Paging.PAGE))
        assertFalse(Paging.hasMore(Paging.PAGE - 1))
    }
}
