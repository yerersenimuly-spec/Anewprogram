package app.line

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.line.crypto.SecureStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ActivityJournalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedConnectedCallRetainsItsDuration() {
        val id = "failed-call-${UUID.randomUUID()}"
        SecureStore(context).use { store ->
            store.recordActivityEvent(ActivityEvent(id, ActivityEvent.CALL, "84000001", false, "failed", System.currentTimeMillis(), 12))
        }
        SecureStore(context).use { store ->
            val event = store.activityEvents(callsOnly = true, limit = 100).single { it.id == id }
            assertEquals("failed", event.outcome)
            assertEquals(12L, event.durationSeconds)
        }
    }

    @Test fun activityHistoryHasUniqueTimestampCursorsAndPersists() {
        val start = System.currentTimeMillis() + 60_000
        val ids = List(4) { "call-${UUID.randomUUID()}" }
        SecureStore(context).use { store ->
            ids.forEachIndexed { index, id ->
                store.recordActivityEvent(ActivityEvent(
                    id, ActivityEvent.CALL, "81000001", incoming = index % 2 == 0,
                    outcome = if (index == 0) "missed" else "completed", timestamp = start,
                    durationSeconds = if (index == 0) 0 else index.toLong(),
                ))
            }
        }
        SecureStore(context).use { store ->
            val events = store.activityEvents(callsOnly = true, limit = 100).filter { it.id in ids }
            assertEquals(4, events.size)
            assertTrue(events.zipWithNext().all { (newer, older) -> newer.timestamp > older.timestamp })
            val page = store.activityEvents(callsOnly = true, limit = 2).filter { it.id in ids }
            val older = store.activityEvents(callsOnly = true, before = page.last().timestamp, limit = 10)
            assertEquals(2, older.count { it.id in ids })
            assertFalse(older.any { it.id == page.last().id })
            assertTrue(older.none { it.timestamp >= page.last().timestamp })
            assertEquals("missed", events.last().outcome)
        }
    }

    @Test fun journalIsBoundedAndCallPeerMetadataIsEncrypted() {
        val eventId = "private-${UUID.randomUUID()}"
        val peer = "82000001"
        SecureStore(context).use { store ->
            repeat(305) { index ->
                store.recordActivityEvent(ActivityEvent(
                    "bounded-${UUID.randomUUID()}", ActivityEvent.CALL, "83000001", false, "cancelled",
                    System.currentTimeMillis() + 300_000 + index,
                ))
            }
            store.recordActivityEvent(ActivityEvent(eventId, ActivityEvent.CALL, peer, true, "missed", System.currentTimeMillis() + 200_000))
        }
        val database = SQLiteDatabase.openDatabase(context.getDatabasePath("line-secure-store.db").path, null, SQLiteDatabase.OPEN_READONLY)
        database.use {
            it.rawQuery("SELECT payload FROM activity_events WHERE id=?", arrayOf(eventId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val ciphertext = String(cursor.getBlob(0), Charsets.ISO_8859_1)
                assertFalse(ciphertext.contains(peer))
                assertFalse(ciphertext.contains("missed"))
            }
            it.rawQuery("SELECT COUNT(*) FROM activity_events", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) <= 300)
            }
        }
    }

    @Test fun encryptedSearchPaginatesNewestFirstAndHonorsDeletion() {
        val peer = "search-${UUID.randomUUID()}"
        SecureStore(context).use { store ->
            repeat(205) { index ->
                val body = if (index % 37 == 0) "Needle item $index" else "ordinary item $index"
                store.saveMessage(peer, "search-${UUID.randomUUID()}", body, false, "received")
            }
            val matches = store.searchMessages("NEEDLE", peer)
            assertEquals(6, matches.size)
            assertTrue(matches.zipWithNext().all { (newer, older) -> newer.sequence > older.sequence })
            val newer = store.searchMessages("needle", peer, limit = 2)
            val older = store.searchMessages("needle", peer, before = newer.last().sequence, limit = 2)
            assertEquals(2, older.size)
            assertTrue(older.all { it.sequence < newer.last().sequence })

            val id = "delete-${UUID.randomUUID()}"
            store.saveMessage(peer, id, "private needle", false, "received")
            store.recordMessageActivity(id, outgoing = false, outcome = "received")
            store.deleteMessage(id)
            assertFalse(store.searchMessages("private needle", peer).any { it.id == id })
            assertFalse(store.activityEvents().any { it.id == id })
        }
    }

    @Test fun clearingConversationRemovesOnlyItsMessageEvents() {
        val peer = "peer-${UUID.randomUUID()}"
        val messageId = "message-${UUID.randomUUID()}"
        val callId = "call-${UUID.randomUUID()}"
        SecureStore(context).use { store ->
            store.saveMessage(peer, messageId, "secret", true, "queued")
            store.recordMessageActivity(messageId, outgoing = true, outcome = "sent")
            store.recordActivityEvent(ActivityEvent(callId, ActivityEvent.CALL, peer, false, "failed", System.currentTimeMillis() + 500_000))
            assertEquals(listOf(messageId), store.clearConversation(peer))
            val remaining = store.activityEvents()
            assertTrue(remaining.none { it.id == messageId })
            assertTrue(remaining.any { it.id == callId })
        }
    }
}
