package app.line.crypto

/**
 * SQL of the secure-store schema, kept apart from the SQLite helper so that the upgrade path can be checked on the
 * JVM. Every step is additive: tables, columns and indexes are added and status labels rewritten, but no message text,
 * key, session or outbox ciphertext is deleted or re-encrypted, so data from 0.7.x (schema 3) and 0.8 (schema 4) survives.
 */
internal object SchemaMigrations {
    const val CURRENT = 4

    fun activityEvents(): List<String> = listOf(
        """CREATE TABLE activity_events (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL, payload BLOB NOT NULL)""",
        "CREATE INDEX activity_events_timestamp ON activity_events(timestamp DESC)",
        "CREATE TABLE activity_clock (id INTEGER PRIMARY KEY CHECK(id=1), timestamp INTEGER NOT NULL)",
        "INSERT INTO activity_clock(id, timestamp) VALUES(1, 0)",
    )

    /** Read tracking, duplicate-envelope memory and attachments; 0.7 stored "queued" both for "in the outbox" and "accepted". */
    fun toVersion4(): List<String> = listOf(
        "ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'",
        "CREATE TABLE seen_envelopes (id TEXT PRIMARY KEY, seen_at INTEGER NOT NULL)",
        "CREATE INDEX seen_envelopes_at ON seen_envelopes(seen_at)",
        """CREATE TABLE attachments (
                message_id TEXT PRIMARY KEY, blob_id TEXT NOT NULL, outgoing INTEGER NOT NULL, stage TEXT NOT NULL,
                size INTEGER NOT NULL, played INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)""",
        "CREATE INDEX attachments_stage ON attachments(stage)",
        "CREATE INDEX messages_unread ON messages(peer) WHERE outgoing=0 AND deleted=0 AND status='received'",
        "UPDATE messages SET status='pending' WHERE outgoing=1 AND status='queued' AND id IN (SELECT id FROM outbox)",
        "UPDATE messages SET status='sent' WHERE outgoing=1 AND status='queued'",
        // History that existed before read tracking must not appear as unread.
        "UPDATE messages SET status='read' WHERE outgoing=0 AND status='received'",
    )

    /** Statements that bring a schema at [from] to [to]; throws for a downgrade or an unknown version. */
    fun upgrade(from: Int, to: Int): List<String> {
        require(from in 1..to && to == CURRENT) { "Unsupported secure-store schema upgrade $from -> $to" }
        val steps = ArrayList<String>()
        if (from <= 1) steps += "ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0"
        if (from <= 2) steps += activityEvents()
        if (from <= 3) steps += toVersion4()
        return steps
    }
}
