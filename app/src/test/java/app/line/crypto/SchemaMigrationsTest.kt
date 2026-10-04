package app.line.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SchemaMigrationsTest {
    private val destructive = Regex("\\b(DROP|DELETE|TRUNCATE|RENAME|VACUUM)\\b", RegexOption.IGNORE_CASE)

    @Test fun schema3UpgradesWithOnlyTheVersion4Steps() {
        assertEquals(SchemaMigrations.toVersion4(), SchemaMigrations.upgrade(3, 4))
    }

    @Test fun schema4NeedsNothing() {
        assertTrue(SchemaMigrations.upgrade(4, SchemaMigrations.CURRENT).isEmpty())
    }

    @Test fun olderSchemasChainEveryStepInOrder() {
        val from1 = SchemaMigrations.upgrade(1, 4)
        assertTrue(from1.first().startsWith("ALTER TABLE messages ADD COLUMN deleted"))
        assertEquals(SchemaMigrations.activityEvents() + SchemaMigrations.toVersion4(), SchemaMigrations.upgrade(2, 4))
        assertEquals(1 + SchemaMigrations.activityEvents().size + SchemaMigrations.toVersion4().size, from1.size)
    }

    @Test fun unknownVersionsAreRefusedInsteadOfGuessing() {
        listOf(0 to 4, 5 to 4, 3 to 5, -1 to 4).forEach { (from, to) ->
            try {
                SchemaMigrations.upgrade(from, to)
                fail("$from -> $to")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test fun nothingIsDestroyedAndOnlyStatusLabelsAreRewritten() {
        val steps = SchemaMigrations.upgrade(1, 4)
        steps.forEach { assertFalse(it, destructive.containsMatchIn(it)) }
        val updates = steps.filter { it.startsWith("UPDATE") }
        assertEquals(3, updates.size)
        updates.forEach { assertTrue(it, it.startsWith("UPDATE messages SET status=")) }
    }

    @Test fun theAttachmentTableHasTheColumnsTheOutboxQueryAndTheStoreUse() {
        val table = SchemaMigrations.toVersion4().single { it.startsWith("CREATE TABLE attachments") }
        listOf("message_id TEXT PRIMARY KEY", "blob_id TEXT NOT NULL", "outgoing INTEGER NOT NULL", "stage TEXT NOT NULL",
            "size INTEGER NOT NULL", "played INTEGER NOT NULL DEFAULT 0", "updated_at INTEGER NOT NULL").forEach {
            assertTrue(it, table.contains(it))
        }
        assertTrue(SchemaMigrations.toVersion4().contains("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'"))
    }
}
