package app.line

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import app.line.crypto.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class MessageDeletionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun versionOneMigrationPreservesIdentityAndHistory() {
        val peer = "88009900"
        val id = "migration-${UUID.randomUUID()}"
        val identity = SecureStore(context).use { store ->
            store.saveMessage(peer, id, "До обновления", false, "received")
            store.publicBundle().getString("identityKey")
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("line-secure-store.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                database.execSQL("DROP INDEX messages_peer_sequence")
                database.execSQL("ALTER TABLE messages RENAME TO messages_v2_test")
                database.execSQL("""CREATE TABLE messages (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, peer TEXT NOT NULL,
                    text BLOB NOT NULL, outgoing INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL)""")
                database.execSQL("""INSERT INTO messages(sequence,id,peer,text,outgoing,status,created_at)
                    SELECT sequence,id,peer,text,outgoing,status,created_at FROM messages_v2_test WHERE deleted=0""")
                database.execSQL("DROP TABLE messages_v2_test")
                database.execSQL("CREATE INDEX messages_peer_sequence ON messages(peer, sequence DESC)")
                database.execSQL("DROP TABLE activity_events")
                database.execSQL("DROP TABLE activity_clock")
                database.version = 1
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        SecureStore(context).use { store ->
            assertEquals(identity, store.publicBundle().getString("identityKey"))
            assertTrue(store.messages(peer).any { it.id == id && it.text == "До обновления" })
            store.deleteMessage(id)
            assertFalse(store.messages(peer).any { it.id == id })
        }
    }

    @Test fun deletingMessageHidesItRetainsTombstoneAndRemovesItsOutboxItem() {
        val peer = "44005566"
        val otherPeer = "55006677"
        val messageId = "delete-${UUID.randomUUID()}"

        SecureStore(context).use { store ->
            store.saveMessage(peer, "older-${UUID.randomUUID()}", "Останется", false, "received")
            store.saveMessage(peer, messageId, "Удалённый текст", true, "queued")
            store.putOutbox(peer, messageId, 2, android.util.Base64.encodeToString("opaque".toByteArray(), android.util.Base64.NO_WRAP))
            store.saveMessage(otherPeer, "other-${UUID.randomUUID()}", "Другой диалог", false, "received")

            store.deleteMessage(messageId)

            assertEquals(listOf("Останется"), store.messages(peer).map { it.text })
            assertEquals("Останется", store.conversations().single { it.peer == peer }.text)
            assertTrue(store.outbox().none { it.id == messageId })
            assertTrue(store.conversations().any { it.peer == otherPeer })

            try {
                store.saveMessage(peer, messageId, "Попытка восстановления", false, "received")
                fail("A deleted message id must remain reserved")
            } catch (_: SQLiteConstraintException) {
                // The retained tombstone prevents an id from being reused to restore message text.
            }
            assertEquals(listOf("Останется"), store.messages(peer).map { it.text })
        }
    }

    @Test fun acknowledgeSentUpdatesQueuedChatAndRemovesOutbox() {
        val peer = "88009900"
        val id = "ack-${UUID.randomUUID()}"
        val body = android.util.Base64.encodeToString("opaque".toByteArray(), android.util.Base64.NO_WRAP)
        SecureStore(context).use { store ->
            store.saveMessage(peer, id, "Подтверждение", true, "queued")
            store.putOutbox(peer, id, 2, body)

            store.acknowledgeSent(id)

            assertEquals("sent", store.messages(peer).single { it.id == id }.status)
            assertTrue(store.outbox().none { it.id == id })
            store.acknowledgeSent(id)
            assertEquals("sent", store.messages(peer).single { it.id == id }.status)
        }
    }

    @Test fun acknowledgeSentRollsBackOutboxRemovalWhenStatusUpdateFails() {
        val peer = "12344321"
        val id = "ack-rollback-${UUID.randomUUID()}"
        val body = android.util.Base64.encodeToString("opaque".toByteArray(), android.util.Base64.NO_WRAP)
        SecureStore(context).use { store ->
            store.saveMessage(peer, id, "Останется в очереди", true, "queued")
            store.putOutbox(peer, id, 2, body)
            SQLiteDatabase.openDatabase(context.getDatabasePath("line-secure-store.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL("CREATE TRIGGER fail_ack BEFORE UPDATE OF status ON messages " +
                    "WHEN NEW.id='$id' BEGIN SELECT RAISE(ABORT, 'Test acknowledgement failure'); END")
                try {
                    try {
                        store.acknowledgeSent(id)
                        fail("A failed status update must roll back the entire acknowledgement")
                    } catch (_: SQLiteException) {
                    }
                    assertTrue(store.outbox().any { it.id == id })
                    assertEquals("queued", store.messages(peer).single { it.id == id }.status)
                } finally {
                    database.execSQL("DROP TRIGGER fail_ack")
                }
            }
            store.acknowledgeSent(id)
            assertTrue(store.outbox().none { it.id == id })
        }
    }

    @Test fun acknowledgeSentHandlesNoChatRowAndDeletedMessage() {
        val peer = "99001122"
        val missingId = "ack-key-${UUID.randomUUID()}"
        val deletedId = "ack-deleted-${UUID.randomUUID()}"
        val body = android.util.Base64.encodeToString("opaque".toByteArray(), android.util.Base64.NO_WRAP)
        SecureStore(context).use { store ->
            store.putOutbox(peer, missingId, 2, body)
            store.acknowledgeSent(missingId)
            assertTrue(store.outbox().none { it.id == missingId })
            assertTrue(store.messages(peer).none { it.id == missingId })
            store.saveMessage(peer, deletedId, "Удалено", true, "queued")
            store.putOutbox(peer, deletedId, 2, body)
            store.deleteMessage(deletedId)
            store.acknowledgeSent(deletedId)
            assertTrue(store.outbox().none { it.id == deletedId })
            assertTrue(store.messages(peer).none { it.id == deletedId })
            try {
                store.saveMessage(peer, deletedId, "Не восстанавливать", false, "received")
                fail("Acknowledgement must preserve deletion tombstones")
            } catch (_: SQLiteConstraintException) {
            }
        }
    }

    @Test fun clearingConversationHidesItsMessagesAndPendingOutboxOnly() {
        val peer = "66007788"
        val otherPeer = "77008899"

        SecureStore(context).use { store ->
            store.saveMessage(peer, "first-${UUID.randomUUID()}", "Первое", false, "received")
            store.saveMessage(peer, "second-${UUID.randomUUID()}", "Второе", true, "queued")
            store.putOutbox(peer, "pending-${UUID.randomUUID()}", 2, android.util.Base64.encodeToString("opaque".toByteArray(), android.util.Base64.NO_WRAP))
            store.saveMessage(otherPeer, "other-${UUID.randomUUID()}", "Сохранённый диалог", false, "received")

            store.clearConversation(peer)

            assertTrue(store.messages(peer).isEmpty())
            assertFalse(store.conversations().any { it.peer == peer })
            assertEquals("Сохранённый диалог", store.conversations().single { it.peer == otherPeer }.text)
            assertTrue(store.outbox().none { it.peer == peer })
        }
    }
}
