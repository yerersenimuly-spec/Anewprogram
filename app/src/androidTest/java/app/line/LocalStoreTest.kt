package app.line

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import app.line.crypto.SecureStore
import org.junit.Assert.*
import org.junit.Test

class LocalStoreTest {
    @Test fun encryptedHistoryIsPagedAndActivityStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        SecureStore(context).use { store ->
            val peer = "11112222"
            repeat(85) { store.saveMessage(peer, "local-test-$it", "Сообщение $it", it % 2 == 0, "sent") }
            val newest = store.messages(peer, null, 40)
            assertEquals(40, newest.size)
            assertTrue(newest.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            val older = store.messages(peer, newest.first().sequence, 40)
            assertEquals(40, older.size)
            assertTrue(older.last().sequence < newest.first().sequence)
            store.saveMessage("22223333", "conversation-test", "Другой диалог", false, "received")
            val conversations = store.conversations(limit = 2)
            assertEquals(2, conversations.size)
            assertEquals("22223333", conversations[0].peer)
            assertEquals(peer, conversations[1].peer)
            assertEquals("Сообщение 84", conversations[1].text)
            assertTrue(store.conversations(before = conversations.last().sequence).isEmpty())
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { assertNotNull(activity.window.decorView); activity.finish() }
    }
}
