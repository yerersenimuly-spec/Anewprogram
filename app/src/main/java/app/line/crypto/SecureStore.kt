package app.line.crypto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.line.ActivityEvent
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedEnvelope(val cipherType: Int, val body: String)

data class ChatMessage(
    val id: String,
    val peer: String,
    val text: String,
    val outgoing: Boolean,
    val status: String,
    val sequence: Long,
    val createdAt: Long,
)

data class OutboxItem(
    val peer: String,
    val id: String,
    val cipherType: Int,
    val body: String,
    val createdAt: Long,
)

private data class MessageActivityRecord(
    val peer: String,
    val outgoing: Boolean,
    val deleted: Boolean,
    val status: String,
)

class PeerIdentityChangedException(number: String) :
    SecurityException("The pinned Signal identity changed for $number")

/** Synchronous encrypted storage. Call from an IO dispatcher; all operations serialize on this instance. */
class SecureStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val alias = "${appContext.packageName}.line.secure-store.v1"
    private val dbFile = appContext.getDatabasePath(DATABASE_NAME)
    private val storageKey = loadStorageKey(appContext, alias, dbFile.exists())
    private val helper = CryptoDatabase(appContext)
    private val db: SQLiteDatabase = helper.writableDatabase
    private val protocolStore = ProtocolStore()
    private var closed = false

    @Synchronized
    fun setLocalNumber(number: String) {
        ensureOpen()
        val checked = validNumber(number)
        transaction {
            ensureLocalIdentity()
            val old = getSecret("settings", "local-number")?.toString(StandardCharsets.UTF_8)
            check(old == null || old == checked) {
                "Changing the local number requires a new Signal identity and store"
            }
            putSecret("settings", "local-number", checked.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** Returns the public identity and a replenished pool of one-time prekeys; private keys never leave this device. */
    @Synchronized
    fun publicBundle(): JSONObject {
        ensureOpen()
        return transaction {
            ensureLocalIdentity()
            replenishPreKeys()
            val identity = localIdentityPair().publicKey
            val registrationId = protocolStore.registrationIdValue
            val signed = activeSignedPreKey()
            val kyber = activeKyberPreKey()
            val preKeys = JSONArray()
            protocolStore.loadPreKeyIds().sorted().forEach { id ->
                val key = protocolStore.loadPreKey(id).keyPair.publicKey.serialize()
                preKeys.put(JSONObject().put("id", id).put("publicKey", encode(key)))
            }
            JSONObject()
                .put("identityKey", encode(identity.serialize()))
                .put("registrationId", registrationId)
                .put("signedPreKey", JSONObject()
                    .put("id", signed.id)
                    .put("publicKey", encode(signed.keyPair.publicKey.serialize()))
                    .put("signature", encode(signed.signature)))
                .put("kyberPreKey", JSONObject()
                    .put("id", kyber.id)
                    .put("publicKey", encode(kyber.keyPair.publicKey.serialize()))
                    .put("signature", encode(kyber.signature)))
                .put("preKeys", preKeys)
        }
    }

    /** Pins first-seen identity keys; a changed key is an error, never an automatic replacement. */
    @Synchronized
    fun rememberPeer(number: String, bundle: JSONObject): String {
        ensureOpen()
        val peer = validNumber(number)
        return transaction {
            ensureLocalIdentity()
            check(peer != localNumber()) { "Cannot pin the local identity as a peer" }
            val incoming = IdentityKey(decode(bundle.getString("identityKey")))
            val old = protocolStore.getIdentity(SignalProtocolAddress(peer, DEVICE_ID))
            if (old != null && !sameIdentity(old, incoming)) {
                throw PeerIdentityChangedException(peer)
            }
            if (old == null) protocolStore.pinIdentity(peer, incoming)
            safetyCodeInternal(peer, incoming)
        }
    }

    /** Must only be called after the user has compared the displayed Signal safety code out of band. */
    @Synchronized
    fun verifyPeer(number: String) {
        ensureOpen()
        val peer = validNumber(number)
        transaction {
            check(protocolStore.getIdentity(SignalProtocolAddress(peer, DEVICE_ID)) != null) {
                "Peer identity must be pinned before it can be verified"
            }
            protocolStore.setVerified(peer, true)
        }
    }

    @Synchronized
    fun safetyCode(number: String): String {
        ensureOpen()
        val peer = validNumber(number)
        return safetyCodeInternal(
            peer,
            protocolStore.getIdentity(SignalProtocolAddress(peer, DEVICE_ID))
                ?: throw SecurityException("No pinned identity for peer"),
        )
    }

    @Synchronized
    fun isVerified(number: String): Boolean {
        ensureOpen()
        val peer = validNumber(number)
        return protocolStore.isVerified(peer)
    }

    @Synchronized
    fun hasSession(number: String): Boolean {
        ensureOpen()
        val peer = validNumber(number)
        return protocolStore.containsSession(SignalProtocolAddress(peer, DEVICE_ID))
    }

    /** Accepts a fetched server bundle with a selected `preKey` object and validates all identity signatures. */
    @Synchronized
    fun establishSession(number: String, bundle: JSONObject) {
        ensureOpen()
        val peer = validNumber(number)
        transaction {
            ensureLocalIdentity()
            check(peer != localNumber()) { "Cannot establish a session with the local identity" }
            val pinned = protocolStore.getIdentity(SignalProtocolAddress(peer, DEVICE_ID))
                ?: throw SecurityException("Remember and compare the peer safety code before session setup")
            check(protocolStore.isVerified(peer)) { "Peer safety code must be verified before session setup" }
            val parsed = parseBundle(bundle, pinned)
            val remote = SignalProtocolAddress(peer, DEVICE_ID)
            val local = SignalProtocolAddress(localNumber(), DEVICE_ID)
            SessionBuilder(protocolStore, remote, local).process(parsed)
        }
    }

    @Synchronized
    fun encrypt(number: String, plaintext: ByteArray): EncryptedEnvelope {
        ensureOpen()
        val peer = validNumber(number)
        return transaction { encryptInternal(peer, plaintext) }
    }

    /** Encrypts, stores the exact retry payload and persists the local message in one SQLite transaction. */
    @Synchronized
    fun encryptAndQueue(
        number: String,
        id: String,
        plaintext: ByteArray,
        displayText: String? = null,
    ): EncryptedEnvelope {
        ensureOpen()
        val peer = validNumber(number)
        validId(id)
        require(plaintext.size <= MAX_ENVELOPE_BYTES) { "Encrypted payload exceeds the size limit" }
        if (displayText != null) {
            require(displayText.toByteArray(StandardCharsets.UTF_8).size <= MAX_CHAT_TEXT_BYTES) {
                "Chat text exceeds the size limit"
            }
        }
        return transaction {
            ensureLocalIdentity()
            requireVerified(peer)
            val existing = getOutbox(id)
            if (existing != null) {
                check(existing.peer == peer) { "Message id is already assigned to another peer" }
                val saved = findMessage(id)
                val savedRequestHash = getSecret("outbox-request", id)
                check(
                        savedRequestHash != null && MessageDigest.isEqual(savedRequestHash, sha256(plaintext)) &&
                        (if (displayText == null) saved == null
                        else saved?.let { it.peer == peer && it.outgoing && it.text == displayText } == true),
                ) {
                    "Message id is already assigned to another payload"
                }
                return@transaction EncryptedEnvelope(existing.cipherType, existing.body)
            }
            check(findMessage(id) == null) { "Message id is already assigned to another record" }
            val envelope = encryptInternal(peer, plaintext)
            if (displayText != null) saveMessageInternal(peer, id, displayText, true, "queued")
            putOutboxInternal(peer, id, envelope)
            putSecret("outbox-request", id, sha256(plaintext))
            envelope
        }
    }

    @Synchronized
    fun decrypt(number: String, cipherType: Int, body: String): ByteArray {
        ensureOpen()
        val peer = validNumber(number)
        return transaction {
            ensureLocalIdentity()
            decryptInternal(peer, cipherType, body)
        }
    }

    /** Decrypts and stores chat text atomically with the ratchet step; returns null for an already-stored chat. */
    @Synchronized
    fun decryptAndStore(number: String, cipherType: Int, body: String, id: String): JSONObject? {
        ensureOpen()
        val peer = validNumber(number)
        validId(id)
        return transaction {
            ensureLocalIdentity()
            requireVerified(peer, requireSession = false)
            val previous = findMessage(id)
            if (previous != null) {
                check(previous.peer == peer && !previous.outgoing) { "Message id is already assigned to another record" }
                return@transaction null
            }
            val plaintext = decryptInternal(peer, cipherType, body)
            require(plaintext.size <= MAX_ENVELOPE_BYTES) { "Decrypted message exceeds the size limit" }
            val payload = try {
                JSONObject(String(plaintext, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                throw SecurityException("Decrypted payload is not valid JSON", e)
            }
            when (payload.optString("kind")) {
                "chat" -> {
                    check(payload.optString("id") == id) { "Chat payload id does not match its envelope" }
                    val text = payload.getString("text")
                    require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_CHAT_TEXT_BYTES) {
                        "Chat text exceeds the size limit"
                    }
                    saveMessageInternal(peer, id, text, false, "received")
                    recordActivityEventInternal(ActivityEvent(
                        id, ActivityEvent.MESSAGE, peer, true, "received", System.currentTimeMillis(),
                    ))
                }
                "call-key" -> Unit
                else -> throw SecurityException("Unsupported decrypted payload kind")
            }
            payload
        }
    }

    @Synchronized
    fun saveMessage(peer: String, id: String, text: String, outgoing: Boolean, status: String) {
        ensureOpen()
        val checkedPeer = validNumber(peer)
        validId(id)
        validStatus(status)
        transaction {
            ensureLocalIdentity()
            saveMessageInternal(checkedPeer, id, text, outgoing, status)
        }
    }

    @Synchronized
    fun updateMessageStatus(id: String, status: String) {
        ensureOpen()
        validId(id)
        validStatus(status)
        transaction {
            val values = ContentValues().apply { put("status", status) }
            db.update("messages", values, "id=?", arrayOf(id))
            if (status == "failed") updateMessageActivityOutcomeInternal(id, status)
        }
    }

    @Synchronized
    fun recordMessageActivity(id: String, outgoing: Boolean, outcome: String): List<String> {
        ensureOpen()
        validId(id)
        require(outcome in MESSAGE_OUTCOMES)
        return transaction {
            val row = db.query(
                "messages", arrayOf("peer", "outgoing", "deleted", "status"), "id=?", arrayOf(id), null, null, null, "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) MessageActivityRecord(
                    cursor.getString(0), cursor.getInt(1) != 0, cursor.getInt(2) != 0, cursor.getString(3),
                ) else null
            } ?: return@transaction emptyList()
            if (row.outgoing != outgoing || row.deleted) return@transaction emptyList()
            recordActivityEventInternal(ActivityEvent(
                id, ActivityEvent.MESSAGE, row.peer, !outgoing,
                if (row.status == "failed") "failed" else outcome, System.currentTimeMillis(),
            ), replaceExisting = true)
        }
    }

    @Synchronized
    fun recordActivityEvent(event: ActivityEvent): List<String> {
        ensureOpen()
        validateActivityEvent(event)
        return transaction { recordActivityEventInternal(event) }
    }

    @Synchronized
    fun activityEvents(callsOnly: Boolean = false, before: Long? = null, limit: Int = 40, incomingOnly: Boolean = false): List<ActivityEvent> {
        ensureOpen()
        val boundedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        var cursorBefore = before ?: Long.MAX_VALUE
        var firstPage = true
        val result = ArrayList<ActivityEvent>(boundedLimit)
        while (result.size < boundedLimit) {
            val selection = if (firstPage && before == null) null else "timestamp<?"
            val selectionArgs = if (selection == null) null else arrayOf(cursorBefore.toString())
            var rowsRead = 0
            db.query(
                "activity_events", arrayOf("id", "timestamp", "payload"),
                selection, selectionArgs, null, null,
                "timestamp DESC", MAX_PAGE_SIZE.toString(),
            ).use { cursor ->
                firstPage = false
                while (cursor.moveToNext() && result.size < boundedLimit) {
                    rowsRead++
                    val event = readActivityEvent(cursor.getString(0), cursor.getLong(1), cursor.getBlob(2))
                    cursorBefore = event.timestamp
                    if ((!callsOnly || event.kind == ActivityEvent.CALL) && (!incomingOnly || event.incoming)) result += event
                }
            }
            if (rowsRead == 0 || rowsRead < MAX_PAGE_SIZE) break
        }
        return result
    }

    /** Decrypts keyset pages on demand; plaintext is never indexed or loaded into memory wholesale. */
    @Synchronized
    fun searchMessages(query: String, peer: String? = null, before: Long? = null, limit: Int = 40): List<ChatMessage> {
        ensureOpen()
        if (query.isBlank()) return emptyList()
        require(query.toByteArray(StandardCharsets.UTF_8).size <= MAX_SEARCH_QUERY_BYTES) { "Search query is too long" }
        val checkedPeer = peer?.let(::validNumber)
        val needle = query.trim().lowercase(Locale.ROOT)
        val boundedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val matches = ArrayList<ChatMessage>(boundedLimit)
        var cursorBefore = before ?: Long.MAX_VALUE
        while (matches.size < boundedLimit) {
            val clauses = mutableListOf("deleted=0", "sequence<?")
            val args = mutableListOf(cursorBefore.toString())
            if (checkedPeer != null) { clauses += "peer=?"; args += checkedPeer }
            var rowsRead = 0
            db.query(
                "messages", arrayOf("id", "peer", "text", "outgoing", "status", "sequence", "created_at"),
                clauses.joinToString(" AND "), args.toTypedArray(), null, null, "sequence DESC", MAX_PAGE_SIZE.toString(),
            ).use { cursor ->
                while (cursor.moveToNext() && matches.size < boundedLimit) {
                    rowsRead++
                    val id = cursor.getString(0)
                    val messagePeer = cursor.getString(1)
                    val sequence = cursor.getLong(5)
                    val text = open("messages", scopedKey(messagePeer, id), cursor.getBlob(2)).toString(StandardCharsets.UTF_8)
                    cursorBefore = sequence
                    if (text.lowercase(Locale.ROOT).contains(needle)) matches += ChatMessage(
                        id, messagePeer, text, cursor.getInt(3) != 0, cursor.getString(4), sequence, cursor.getLong(6),
                    )
                }
            }
            if (rowsRead == 0 || (rowsRead < MAX_PAGE_SIZE && matches.size < boundedLimit)) break
        }
        return matches
    }

    /** Hides and wipes a message on this device; a message already delivered to a peer cannot be recalled. */
    @Synchronized
    fun deleteMessage(id: String) {
        ensureOpen()
        validId(id)
        transaction {
            val message = db.query(
                "messages", arrayOf("peer", "outgoing"), "id=?", arrayOf(id), null, null, null, "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to (cursor.getInt(1) != 0) else null
            } ?: return@transaction

            wipeMessageText(message.first, id)
            db.delete("activity_events", "id=?", arrayOf(id))
            if (message.second) removeQueuedMessage(id)
        }
    }

    /** Hides and wipes this conversation on this device; already-delivered messages remain on other devices. */
    @Synchronized
    fun clearConversation(peer: String): List<String> {
        ensureOpen()
        val checkedPeer = validNumber(peer)
        return transaction {
            var lastSequence = 0L
            while (true) {
                val batch = ArrayList<Pair<Long, String>>(MAX_PAGE_SIZE)
                db.query(
                    "messages", arrayOf("sequence", "id"), "peer=? AND sequence>? AND deleted=0",
                    arrayOf(checkedPeer, lastSequence.toString()), null, null, "sequence ASC", MAX_PAGE_SIZE.toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) batch += cursor.getLong(0) to cursor.getString(1)
                }
                if (batch.isEmpty()) break
                batch.forEach { (sequence, id) ->
                    wipeMessageText(checkedPeer, id)
                    lastSequence = sequence
                }
            }

            val queuedIds = ArrayList<String>()
            db.query("outbox", arrayOf("id"), "peer=?", arrayOf(checkedPeer), null, null, null).use { cursor ->
                while (cursor.moveToNext()) queuedIds += cursor.getString(0)
            }
            db.delete("outbox", "peer=?", arrayOf(checkedPeer))
            queuedIds.forEach { deleteSecret("outbox-request", it) }
            removeMessageActivityForPeerInternal(checkedPeer)
        }
    }

    /** Returns an ascending page using a keyset cursor; at most 100 encrypted message bodies are opened. */
    @Synchronized
    fun messages(peer: String, before: Long? = null, limit: Int = 40): List<ChatMessage> {
        ensureOpen()
        val checkedPeer = validNumber(peer)
        val boundedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val selection = if (before == null) "peer=? AND deleted=0" else "peer=? AND deleted=0 AND sequence<?"
        val args = if (before == null) arrayOf(checkedPeer) else arrayOf(checkedPeer, before.toString())
        val descending = ArrayList<ChatMessage>(boundedLimit)
        db.query(
            "messages",
            arrayOf("id", "peer", "text", "outgoing", "status", "sequence", "created_at"),
            selection,
            args,
            null,
            null,
            "sequence DESC",
            boundedLimit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val messagePeer = cursor.getString(1)
                val text = open("messages", scopedKey(messagePeer, id), cursor.getBlob(2))
                    .toString(StandardCharsets.UTF_8)
                descending += ChatMessage(
                    id, messagePeer, text, cursor.getInt(3) != 0, cursor.getString(4),
                    cursor.getLong(5), cursor.getLong(6),
                )
            }
        }
        return descending.asReversed()
    }

    @Synchronized
    fun conversations(before: Long? = null, limit: Int = 40): List<ChatMessage> {
        ensureOpen()
        val cursorClause = if (before == null) "WHERE m.deleted=0" else "WHERE m.deleted=0 AND m.sequence < ?"
        val args = if (before == null) arrayOf(limit.coerceIn(1, MAX_PAGE_SIZE).toString())
            else arrayOf(before.toString(), limit.coerceIn(1, MAX_PAGE_SIZE).toString())
        val result = ArrayList<ChatMessage>()
        db.rawQuery("""SELECT m.id, m.peer, m.text, m.outgoing, m.status, m.sequence, m.created_at
            FROM messages m JOIN (SELECT peer, MAX(sequence) AS latest FROM messages WHERE deleted=0 GROUP BY peer) c
            ON m.sequence=c.latest $cursorClause ORDER BY m.sequence DESC LIMIT ?""", args).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val peer = cursor.getString(1)
                result += ChatMessage(id, peer, open("messages", scopedKey(peer, id), cursor.getBlob(2)).toString(StandardCharsets.UTF_8),
                    cursor.getInt(3) != 0, cursor.getString(4), cursor.getLong(5), cursor.getLong(6))
            }
        }
        return result
    }

    private fun recordActivityEventInternal(event: ActivityEvent, replaceExisting: Boolean = false): List<String> {
        validateActivityEvent(event)
        val existing = db.query(
            "activity_events", arrayOf("timestamp"), "id=?", arrayOf(event.id), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existing != null) {
            if (!replaceExisting) return emptyList()
            val values = ContentValues().apply { put("payload", seal("activity_events", event.id, activityPayload(event))) }
            db.update("activity_events", values, "id=?", arrayOf(event.id))
            return emptyList()
        }

        val previousTimestamp = db.rawQuery("SELECT timestamp FROM activity_clock WHERE id=1", null).use { cursor ->
            check(cursor.moveToFirst()) { "Activity timestamp clock is missing" }
            cursor.getLong(0)
        }
        val timestamp = maxOf(event.timestamp, previousTimestamp + 1)
        val values = ContentValues().apply {
            put("id", event.id)
            put("timestamp", timestamp)
            put("payload", seal("activity_events", event.id, activityPayload(event)))
        }
        if (db.insertWithOnConflict("activity_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return emptyList()
        db.update("activity_clock", ContentValues().apply { put("timestamp", timestamp) }, "id=1", null)
        return trimActivityEventsInternal()
    }

    private fun updateMessageActivityOutcomeInternal(id: String, outcome: String) {
        if (outcome !in MESSAGE_OUTCOMES) return
        val row = db.query(
            "activity_events", arrayOf("timestamp", "payload"), "id=?", arrayOf(id), null, null, null, "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getBlob(1) else null
        } ?: return
        val old = readActivityEvent(id, row.first, row.second)
        if (old.kind != ActivityEvent.MESSAGE) return
        val values = ContentValues().apply { put("payload", seal("activity_events", id, activityPayload(old.copy(outcome = outcome)))) }
        db.update("activity_events", values, "id=?", arrayOf(id))
    }

    private fun removeMessageActivityForPeerInternal(peer: String): List<String> {
        val ids = ArrayList<String>()
        db.query("activity_events", arrayOf("id", "timestamp", "payload"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val event = readActivityEvent(id, cursor.getLong(1), cursor.getBlob(2))
                if (event.kind == ActivityEvent.MESSAGE && event.peer == peer) ids += id
            }
        }
        ids.forEach { db.delete("activity_events", "id=?", arrayOf(it)) }
        return ids
    }

    private fun trimActivityEventsInternal(): List<String> {
        val ids = ArrayList<String>()
        db.rawQuery("SELECT id FROM activity_events ORDER BY sequence DESC LIMIT -1 OFFSET $MAX_ACTIVITY_EVENTS", null).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        ids.forEach { db.delete("activity_events", "id=?", arrayOf(it)) }
        return ids
    }

    private fun readActivityEvent(id: String, timestamp: Long, sealed: ByteArray): ActivityEvent {
        val payload = JSONObject(String(open("activity_events", id, sealed), StandardCharsets.UTF_8))
        return ActivityEvent(id, payload.getString("kind"), payload.getString("peer"), payload.getBoolean("incoming"),
            payload.getString("outcome"), timestamp, payload.getLong("durationSeconds")).also(::validateActivityEvent)
    }

    private fun activityPayload(event: ActivityEvent): ByteArray = JSONObject()
        .put("kind", event.kind).put("peer", event.peer).put("incoming", event.incoming)
        .put("outcome", event.outcome).put("durationSeconds", event.durationSeconds)
        .toString().toByteArray(StandardCharsets.UTF_8)

    private fun validateActivityEvent(event: ActivityEvent) {
        validId(event.id)
        require(event.timestamp > 0 && event.durationSeconds >= 0)
        when (event.kind) {
            ActivityEvent.MESSAGE -> {
                validNumber(event.peer)
                require(event.outcome in MESSAGE_OUTCOMES)
            }
            ActivityEvent.CALL -> {
                val peers = event.peer.split(',').map(String::trim)
                require(peers.size in 1..7 && peers.distinct().size == peers.size)
                peers.forEach(::validNumber)
                require(event.outcome in CALL_OUTCOMES)
                require(event.durationSeconds == 0L || event.outcome == "completed" || event.outcome == "failed")
            }
            else -> error("Unsupported activity event kind")
        }
    }

    /** Idempotent for an identical entry; ciphertext is retained unchanged for all retries. */
    @Synchronized
    fun putOutbox(peer: String, id: String, cipherType: Int, body: String) {
        ensureOpen()
        val checkedPeer = validNumber(peer)
        validId(id)
        val envelope = EncryptedEnvelope(cipherType, body)
        transaction { putOutboxInternal(checkedPeer, id, envelope) }
    }

    @Synchronized
    fun outbox(): List<OutboxItem> {
        ensureOpen()
        val result = ArrayList<OutboxItem>()
        db.query(
            "outbox", arrayOf("peer", "id", "cipher_type", "body", "created_at"),
            null, null, null, null, "created_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val peer = cursor.getString(0)
                val id = cursor.getString(1)
                val body = open("outbox", scopedKey(peer, id), cursor.getBlob(3))
                    .toString(StandardCharsets.UTF_8)
                result += OutboxItem(peer, id, cursor.getInt(2), body, cursor.getLong(4))
            }
        }
        return result
    }

    @Synchronized
    fun removeOutbox(id: String) {
        ensureOpen()
        validId(id)
        transaction {
            db.delete("outbox", "id=?", arrayOf(id))
            deleteSecret("outbox-request", id)
        }
    }

    @Synchronized
    fun markDelivered(id: String): Boolean {
        ensureOpen(); validId(id)
        return transaction {
            val queued = getOutbox(id) != null
            removeOutbox(id)
            updateMessageStatus(id, "delivered")
            queued
        }
    }

    @Synchronized
    fun acknowledgeSent(id: String) {
        ensureOpen()
        validId(id)
        transaction {
            db.delete("outbox", "id=?", arrayOf(id))
            deleteSecret("outbox-request", id)
            val values = ContentValues().apply { put("status", "sent") }
            db.update("messages", values, "id=? AND deleted=0", arrayOf(id))
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            helper.close()
        }
    }

    private fun encryptInternal(peer: String, plaintext: ByteArray): EncryptedEnvelope {
        ensureLocalIdentity()
        requireVerified(peer)
        val remote = SignalProtocolAddress(peer, DEVICE_ID)
        val local = SignalProtocolAddress(localNumber(), DEVICE_ID)
        val message = SessionCipher(protocolStore, local, remote).encrypt(plaintext)
        val type = message.type
        check(type == CiphertextMessage.PREKEY_TYPE || type == CiphertextMessage.WHISPER_TYPE) {
            "Unexpected Signal ciphertext type"
        }
        return EncryptedEnvelope(type, encode(message.serialize()))
    }

    private fun decryptInternal(peer: String, cipherType: Int, body: String): ByteArray {
        requireVerified(peer, requireSession = cipherType != CiphertextMessage.PREKEY_TYPE)
        val serialized = decode(body)
        val remote = SignalProtocolAddress(peer, DEVICE_ID)
        val local = SignalProtocolAddress(localNumber(), DEVICE_ID)
        val pinned = protocolStore.getIdentity(remote)
            ?: throw SecurityException("Unknown peer; fetch and remember its server bundle first")
        val cipher = SessionCipher(protocolStore, local, remote)
        return when (cipherType) {
            CiphertextMessage.PREKEY_TYPE -> {
                val message = PreKeySignalMessage(serialized)
                check(sameIdentity(pinned, message.identityKey)) {
                    "PreKey message identity does not match the pinned peer identity"
                }
                cipher.decrypt(message)
            }
            CiphertextMessage.WHISPER_TYPE -> {
                val session = protocolStore.loadSession(remote)
                    ?: throw SecurityException("No established Signal session for peer")
                check(session.hasSenderChain() && sameIdentity(pinned, session.remoteIdentityKey)) {
                    "Signal session identity does not match the pinned peer identity"
                }
                cipher.decrypt(SignalMessage(serialized))
            }
            else -> throw SecurityException("Unsupported Signal ciphertext type")
        }
    }

    private fun parseBundle(bundle: JSONObject, pinned: IdentityKey): PreKeyBundle {
        val remoteIdentity = IdentityKey(decode(bundle.getString("identityKey")))
        check(sameIdentity(pinned, remoteIdentity)) { "Server bundle identity differs from the pinned key" }
        val registration = bundle.getInt("registrationId")
        require(registration in 1..16380) { "Invalid Signal registration id" }
        val signedJson = bundle.getJSONObject("signedPreKey")
        val signedId = signedJson.getInt("id").also { require(it >= 0) }
        val signedPublic = ECPublicKey(decode(signedJson.getString("publicKey")))
        val signedSignature = decode(signedJson.getString("signature"))
        check(remoteIdentity.publicKey.verifySignature(signedPublic.serialize(), signedSignature)) {
            "Invalid signed prekey signature"
        }

        val kyberJson = bundle.getJSONObject("kyberPreKey")
        val kyberId = kyberJson.getInt("id").also { require(it >= 0) }
        val kyberPublic = org.signal.libsignal.protocol.kem.KEMPublicKey(decode(kyberJson.getString("publicKey")))
        val kyberSignature = decode(kyberJson.getString("signature"))
        check(remoteIdentity.publicKey.verifySignature(kyberPublic.serialize(), kyberSignature)) {
            "Invalid Kyber prekey signature"
        }

        val oneTimeJson = bundle.optJSONObject("preKey")
        val preKeyId: Int
        val preKey: ECPublicKey?
        if (oneTimeJson == null) {
            preKeyId = PreKeyBundle.NULL_PRE_KEY_ID
            preKey = null
        } else {
            preKeyId = oneTimeJson.getInt("id").also { require(it >= 0) }
            preKey = ECPublicKey(decode(oneTimeJson.getString("publicKey")))
        }
        return PreKeyBundle(
            registration,
            DEVICE_ID,
            preKeyId,
            preKey,
            signedId,
            signedPublic,
            signedSignature,
            remoteIdentity,
            kyberId,
            kyberPublic,
            kyberSignature,
        )
    }

    private fun safetyCodeInternal(peer: String, remote: IdentityKey): String {
        val localName = localNumber()
        val localIdentity = localIdentityPair().publicKey
        return NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            .createFor(
                FINGERPRINT_VERSION,
                localName.toByteArray(StandardCharsets.UTF_8),
                localIdentity,
                peer.toByteArray(StandardCharsets.UTF_8),
                remote,
            )
            .displayableFingerprint.displayText
    }

    private fun requireVerified(peer: String, requireSession: Boolean = true) {
        check(protocolStore.isVerified(peer)) { "Peer safety code must be verified before sending" }
        check(!requireSession || protocolStore.containsSession(SignalProtocolAddress(peer, DEVICE_ID))) {
            "No Signal session for peer; establish one before sending"
        }
    }

    private fun saveMessageInternal(peer: String, id: String, text: String, outgoing: Boolean, status: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("peer", peer)
            put("text", seal("messages", scopedKey(peer, id), text.toByteArray(StandardCharsets.UTF_8)))
            put("outgoing", if (outgoing) 1 else 0)
            put("status", status)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("messages", null, values)
    }

    private fun wipeMessageText(peer: String, id: String) {
        val values = ContentValues().apply {
            put("text", seal("messages", scopedKey(peer, id), byteArrayOf()))
            put("deleted", 1)
        }
        db.update("messages", values, "id=? AND peer=?", arrayOf(id, peer))
    }

    private fun removeQueuedMessage(id: String) {
        db.delete("outbox", "id=?", arrayOf(id))
        deleteSecret("outbox-request", id)
    }

    private fun findMessage(id: String): ChatMessage? {
        db.query(
            "messages",
            arrayOf("id", "peer", "text", "outgoing", "status", "sequence", "created_at"),
            "id=?", arrayOf(id), null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val peer = cursor.getString(1)
            return ChatMessage(
                cursor.getString(0), peer,
                open("messages", scopedKey(peer, id), cursor.getBlob(2)).toString(StandardCharsets.UTF_8),
                cursor.getInt(3) != 0, cursor.getString(4), cursor.getLong(5), cursor.getLong(6),
            )
        }
    }

    private fun putOutboxInternal(peer: String, id: String, envelope: EncryptedEnvelope) {
        val existing = getOutbox(id)
        if (existing != null) {
            check(existing.peer == peer && existing.cipherType == envelope.cipherType && existing.body == envelope.body) {
                "Outbox id already contains a different encrypted payload"
            }
            return
        }
        check(rowCount("outbox") < MAX_OUTBOX_ITEMS) {
            "Encrypted outbox is full; retry or remove queued messages first"
        }
        val values = ContentValues().apply {
            put("peer", peer)
            put("id", id)
            put("cipher_type", envelope.cipherType)
            put("body", seal("outbox", scopedKey(peer, id), envelope.body.toByteArray(StandardCharsets.UTF_8)))
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("outbox", null, values)
    }

    private fun getOutbox(id: String): OutboxItem? {
        db.query(
            "outbox", arrayOf("peer", "id", "cipher_type", "body", "created_at"),
            "id=?", arrayOf(id), null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val peer = cursor.getString(0)
            return OutboxItem(
                peer, cursor.getString(1), cursor.getInt(2),
                open("outbox", scopedKey(peer, id), cursor.getBlob(3)).toString(StandardCharsets.UTF_8),
                cursor.getLong(4),
            )
        }
    }

    private fun activeSignedPreKey(): SignedPreKeyRecord {
        val now = System.currentTimeMillis()
        val existing = protocolStore.loadSignedPreKeys().maxByOrNull { it.timestamp }
        if (existing != null && now - existing.timestamp < SIGNED_PREKEY_ROTATION_MILLIS) return existing
        val id = nextId("signed-prekey")
        val keyPair = ECKeyPair.generate()
        val signature = localIdentityPair().privateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, now, keyPair, signature).also {
            protocolStore.storeSignedPreKey(id, it)
        }
    }

    private fun activeKyberPreKey(): KyberPreKeyRecord {
        val existing = protocolStore.loadKyberPreKeys()
            .filterNot { protocolStore.hasKyberPreKeyBeenUsed(it.id) }
            .maxByOrNull { it.timestamp }
        if (existing != null) return existing
        val id = nextId("kyber-prekey")
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = localIdentityPair().privateKey.calculateSignature(pair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature).also {
            protocolStore.storeKyberPreKey(id, it)
        }
    }

    private fun replenishPreKeys() {
        val missing = (PREKEY_POOL_SIZE - protocolStore.loadPreKeyIds().size).coerceAtLeast(0)
        repeat(missing) {
            val id = nextId("prekey")
            protocolStore.storePreKey(id, PreKeyRecord(id, ECKeyPair.generate()))
        }
    }

    private fun ensureLocalIdentity() {
        val pair = getSecret("local", "identity-pair")
        val registration = getSecret("local", "registration-id")
        if (pair == null && registration == null) {
            check(!hasPersistentData()) { "Local Signal identity is missing; refusing to regenerate keys" }
            val identityPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)
            putSecret("local", "identity-pair", identityPair.serialize())
            putSecret("local", "registration-id", intBytes(registrationId))
        } else {
            check(pair != null && registration != null && registration.size == Int.SIZE_BYTES) {
                "Incomplete local Signal identity; refusing to regenerate keys"
            }
            IdentityKeyPair(pair)
        }
    }

    private fun localIdentityPair(): IdentityKeyPair = IdentityKeyPair(
        getSecret("local", "identity-pair") ?: throw IllegalStateException("Local identity is not initialized"),
    )

    private fun localNumber(): String = getSecret("settings", "local-number")
        ?.toString(StandardCharsets.UTF_8)
        ?: throw IllegalStateException("Set the local number before using peer sessions")

    private fun nextId(namespace: String): Int {
        val key = "counter:$namespace"
        val priorBytes = getSecret("counters", key)
        var next = if (priorBytes == null) 1 else ByteBuffer.wrap(priorBytes).int + 1
        if (next <= 0) next = 1
        while (hasSecret(namespace, next.toString())) next = if (next == Int.MAX_VALUE) 1 else next + 1
        putSecret("counters", key, intBytes(next))
        return next
    }

    private fun safetyIdentity(number: String): IdentityKey =
        protocolStore.getIdentity(SignalProtocolAddress(number, DEVICE_ID))
            ?: throw SecurityException("No pinned identity for peer")

    private fun hasPersistentData(): Boolean =
        rowCount("signal_state") > 0 || rowCount("peers") > 0 || rowCount("messages") > 0 || rowCount("outbox") > 0

    private fun rowCount(table: String): Long = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
        check(cursor.moveToFirst()) { "Unable to inspect encrypted storage" }
        cursor.getLong(0)
    }

    private fun getSecret(table: String, key: String): ByteArray? =
        db.query(
            "signal_state", arrayOf("payload"), "kind=? AND record_key=?", arrayOf(table, key),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else open("signal_state", stateKey(table, key), cursor.getBlob(0))
        }

    private fun hasSecret(table: String, key: String): Boolean =
        db.query(
            "signal_state", arrayOf("record_key"), "kind=? AND record_key=?", arrayOf(table, key),
            null, null, null, "1",
        ).use { it.moveToFirst() }

    private fun putSecret(table: String, key: String, plaintext: ByteArray) {
        val values = ContentValues().apply {
            put("kind", table)
            put("record_key", key)
            put("payload", seal("signal_state", stateKey(table, key), plaintext))
        }
        db.insertWithOnConflict("signal_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun deleteSecret(table: String, key: String) {
        db.delete("signal_state", "kind=? AND record_key=?", arrayOf(table, key))
    }

    private fun secretEntries(table: String): List<Pair<String, ByteArray>> {
        val values = ArrayList<Pair<String, ByteArray>>()
        db.query("signal_state", arrayOf("record_key", "payload"), "kind=?", arrayOf(table),
            null, null, "record_key ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                values += key to open("signal_state", stateKey(table, key), cursor.getBlob(1))
            }
        }
        return values
    }

    private fun peerIdentity(number: String): IdentityKey? =
        db.query("peers", arrayOf("identity"), "peer=?", arrayOf(number), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else IdentityKey(open("peers", "$number:identity", cursor.getBlob(0)))
        }

    private fun peerVerified(number: String): Boolean =
        db.query("peers", arrayOf("verified"), "peer=?", arrayOf(number), null, null, null).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }

    private fun pinPeerIdentity(number: String, identity: IdentityKey) {
        val values = ContentValues().apply {
            put("peer", number)
            put("identity", seal("peers", "$number:identity", identity.serialize()))
            put("verified", 0)
        }
        db.insertOrThrow("peers", null, values)
    }

    private fun setPeerVerified(number: String, verified: Boolean) {
        val values = ContentValues().apply { put("verified", if (verified) 1 else 0) }
        check(db.update("peers", values, "peer=?", arrayOf(number)) == 1) {
            "No pinned identity for peer"
        }
    }

    private inline fun <T> transaction(block: () -> T): T {
        ensureOpen()
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun seal(table: String, key: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, storageKey)
        cipher.updateAAD(aad(table, key))
        val encrypted = cipher.doFinal(plaintext)
        return cipher.iv + encrypted
    }

    private fun open(table: String, key: String, sealed: ByteArray): ByteArray {
        check(sealed.size > GCM_IV_BYTES) { "Encrypted local record is truncated" }
        val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = sealed.copyOfRange(GCM_IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, storageKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(table, key))
        return cipher.doFinal(ciphertext)
    }

    private fun ensureOpen() = check(!closed) { "SecureStore is closed" }

    private inner class ProtocolStore : org.signal.libsignal.protocol.state.SignalProtocolStore {
        val registrationIdValue: Int
            get() = ByteBuffer.wrap(getSecret("local", "registration-id")
                ?: throw IllegalStateException("Local identity is not initialized")).int

        override fun getIdentityKeyPair(): IdentityKeyPair = localIdentityPair()
        override fun getLocalRegistrationId(): Int = registrationIdValue

        @Synchronized
        override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
            val pinned = peerIdentity(address.name)
                ?: throw SecurityException("Refusing to trust an identity without an explicit bundle")
            if (!sameIdentity(pinned, identityKey)) throw PeerIdentityChangedException(address.name)
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }

        @Synchronized
        override fun isTrustedIdentity(
            address: SignalProtocolAddress,
            identityKey: IdentityKey,
            direction: IdentityKeyStore.Direction,
        ): Boolean = address.deviceId == DEVICE_ID && peerIdentity(address.name)?.let { sameIdentity(it, identityKey) } == true

        @Synchronized
        override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
            if (address.deviceId == DEVICE_ID) peerIdentity(address.name) else null

        fun pinIdentity(number: String, identity: IdentityKey) = pinPeerIdentity(number, identity)
        fun setVerified(number: String, verified: Boolean) = setPeerVerified(number, verified)
        fun isVerified(number: String): Boolean = peerVerified(number)

        @Synchronized
        override fun loadPreKey(preKeyId: Int): PreKeyRecord =
            getSecret("prekey", preKeyId.toString())?.let(::PreKeyRecord)
                ?: throw InvalidKeyIdException("No such one-time prekey")

        @Synchronized
        override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
            putSecret("prekey", preKeyId.toString(), record.serialize())

        @Synchronized
        override fun containsPreKey(preKeyId: Int): Boolean = hasSecret("prekey", preKeyId.toString())

        @Synchronized
        override fun removePreKey(preKeyId: Int) = deleteSecret("prekey", preKeyId.toString())

        fun loadPreKeyIds(): List<Int> = secretEntries("prekey").mapNotNull { it.first.toIntOrNull() }

        @Synchronized
        override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
            getSecret("session", addressKey(address))?.let(::SessionRecord)

        @Synchronized
        override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
            val sessions = ArrayList<SessionRecord>(addresses.size)
            addresses.forEach { address ->
                sessions += loadSession(address) ?: throw org.signal.libsignal.protocol.NoSessionException(
                    address, "No session record for $address",
                )
            }
            return sessions
        }

        @Synchronized
        override fun getSubDeviceSessions(name: String): MutableList<Int> = secretEntries("session")
            .mapNotNull { (key, _) -> parseAddressKey(key)?.takeIf { it.first == name && it.second != DEVICE_ID }?.second }
            .toMutableList()

        @Synchronized
        override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
            putSecret("session", addressKey(address), record.serialize())

        @Synchronized
        override fun containsSession(address: SignalProtocolAddress): Boolean =
            hasSecret("session", addressKey(address))

        @Synchronized
        override fun deleteSession(address: SignalProtocolAddress) = deleteSecret("session", addressKey(address))

        @Synchronized
        override fun deleteAllSessions(name: String) {
            secretEntries("session").map { it.first }.filter { parseAddressKey(it)?.first == name }
                .forEach { deleteSecret("session", it) }
        }

        @Synchronized
        override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
            getSecret("signed-prekey", signedPreKeyId.toString())?.let(::SignedPreKeyRecord)
                ?: throw InvalidKeyIdException("No such signed prekey")

        @Synchronized
        override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
            secretEntries("signed-prekey").map { SignedPreKeyRecord(it.second) }.toMutableList()

        @Synchronized
        override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
            putSecret("signed-prekey", signedPreKeyId.toString(), record.serialize())

        @Synchronized
        override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
            hasSecret("signed-prekey", signedPreKeyId.toString())

        @Synchronized
        override fun removeSignedPreKey(signedPreKeyId: Int) = deleteSecret("signed-prekey", signedPreKeyId.toString())

        @Synchronized
        override fun storeSenderKey(
            address: SignalProtocolAddress,
            distributionId: java.util.UUID,
            record: org.signal.libsignal.protocol.groups.state.SenderKeyRecord,
        ) = putSecret("sender-key", "${addressKey(address)}:${distributionId}", record.serialize())

        @Synchronized
        override fun loadSenderKey(
            address: SignalProtocolAddress,
            distributionId: java.util.UUID,
        ): org.signal.libsignal.protocol.groups.state.SenderKeyRecord? =
            getSecret("sender-key", "${addressKey(address)}:${distributionId}")
                ?.let { org.signal.libsignal.protocol.groups.state.SenderKeyRecord(it) }

        @Synchronized
        override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
            getSecret("kyber-prekey", kyberPreKeyId.toString())?.let(::KyberPreKeyRecord)
                ?: throw InvalidKeyIdException("No such Kyber prekey")

        @Synchronized
        override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
            secretEntries("kyber-prekey").map { KyberPreKeyRecord(it.second) }.toMutableList()

        @Synchronized
        override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
            putSecret("kyber-prekey", kyberPreKeyId.toString(), record.serialize())

        @Synchronized
        override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
            hasSecret("kyber-prekey", kyberPreKeyId.toString())

        @Synchronized
        override fun markKyberPreKeyUsed(
            kyberPreKeyId: Int,
            signedPreKeyId: Int,
            baseKey: ECPublicKey,
        ) {
            check(containsKyberPreKey(kyberPreKeyId)) { "Unknown Kyber prekey" }
            val baseId = "$kyberPreKeyId:$signedPreKeyId:${encode(baseKey.serialize())}"
            if (hasSecret("kyber-base-key", baseId)) throw ReusedBaseKeyException()
            putSecret("kyber-base-key", baseId, byteArrayOf(1))
            putSecret("kyber-used", kyberPreKeyId.toString(), byteArrayOf(1))
        }

        @Synchronized
        fun hasKyberPreKeyBeenUsed(kyberPreKeyId: Int): Boolean =
            hasSecret("kyber-used", kyberPreKeyId.toString())
    }

    private inner class CryptoDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onConfigure(database: SQLiteDatabase) {
            super.onConfigure(database)
            database.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "Secure deletion could not be enabled" }
            }
            database.execSQL("PRAGMA synchronous=FULL")
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("""CREATE TABLE signal_state (
                kind TEXT NOT NULL, record_key TEXT NOT NULL, payload BLOB NOT NULL,
                PRIMARY KEY(kind, record_key))""")
            database.execSQL("""CREATE TABLE peers (
                peer TEXT PRIMARY KEY, identity BLOB NOT NULL, verified INTEGER NOT NULL DEFAULT 0)""")
            database.execSQL("""CREATE TABLE messages (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, peer TEXT NOT NULL,
                text BLOB NOT NULL, outgoing INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0)""")
            database.execSQL("CREATE INDEX messages_peer_sequence ON messages(peer, sequence DESC)")
            database.execSQL("""CREATE TABLE outbox (
                id TEXT PRIMARY KEY, peer TEXT NOT NULL, cipher_type INTEGER NOT NULL,
                body BLOB NOT NULL, created_at INTEGER NOT NULL)""")
            database.execSQL("CREATE INDEX outbox_created_at ON outbox(created_at, id)")
            createActivityEventsTable(database)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var version = oldVersion
            if (version == 1) {
                database.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                version = 2
            }
            if (version == 2) {
                createActivityEventsTable(database)
                version = 3
            }
            if (version != newVersion) {
                throw SQLiteException("Unsupported secure-store schema upgrade $oldVersion -> $newVersion")
            }
        }

        private fun createActivityEventsTable(database: SQLiteDatabase) {
            database.execSQL("""CREATE TABLE activity_events (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL, payload BLOB NOT NULL)""")
            database.execSQL("CREATE INDEX activity_events_timestamp ON activity_events(timestamp DESC)")
            database.execSQL("CREATE TABLE activity_clock (id INTEGER PRIMARY KEY CHECK(id=1), timestamp INTEGER NOT NULL)")
            database.execSQL("INSERT INTO activity_clock(id, timestamp) VALUES(1, 0)")
        }
    }

    companion object {
        private const val DATABASE_NAME = "line-secure-store.db"
        private const val DATABASE_VERSION = 3
        private const val DEVICE_ID = 1
        private const val PREKEY_POOL_SIZE = 100
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_ACTIVITY_EVENTS = 300
        private const val MAX_OUTBOX_ITEMS = 100
        private const val MAX_ENVELOPE_BYTES = 16 * 1024
        private const val MAX_CHAT_TEXT_BYTES = 4 * 1024
        private const val MAX_SEARCH_QUERY_BYTES = 4 * 1024
        private val MESSAGE_OUTCOMES = setOf("received", "sent", "failed")
        private val CALL_OUTCOMES = setOf("completed", "missed", "declined", "cancelled", "failed")
        private const val SIGNED_PREKEY_ROTATION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val FINGERPRINT_ITERATIONS = 5200
        private const val FINGERPRINT_VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private fun loadStorageKey(context: Context, alias: String, databaseExists: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let {
                if (!databaseExists) {
                    throw GeneralSecurityException("Encrypted database is missing; refusing to create a replacement identity")
                }
                return it
            }
            if (databaseExists) {
                throw GeneralSecurityException("Android Keystore key is missing; refusing to replace encrypted storage")
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun sameIdentity(first: IdentityKey, second: IdentityKey): Boolean =
            MessageDigest.isEqual(first.serialize(), second.serialize())

        private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
        private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
        private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
        private fun validNumber(value: String): String {
            require(value == value.trim() && value.isNotEmpty() && value.length <= 128 &&
                value.none(Char::isISOControl)) { "Invalid peer number" }
            return value
        }
        private fun validId(value: String) {
            require(value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)) { "Invalid message id" }
        }
        private fun validStatus(value: String) {
            require(value.isNotBlank() && value.length <= 40 && value.none(Char::isISOControl)) { "Invalid message status" }
        }
        private fun scopedKey(peer: String, id: String): String = "${peer.length}:$peer$id"
        private fun stateKey(table: String, key: String): String = "$table\u0000$key"
        private fun aad(table: String, key: String): ByteArray =
            "$table:${key.length}:$key".toByteArray(StandardCharsets.UTF_8)
        private fun addressKey(address: SignalProtocolAddress): String =
            "${address.deviceId}:${encode(address.name.toByteArray(StandardCharsets.UTF_8))}"
        private fun parseAddressKey(key: String): Pair<String, Int>? = runCatching {
            val separator = key.indexOf(':')
            val device = key.substring(0, separator).toInt()
            val name = String(Base64.decode(key.substring(separator + 1), Base64.DEFAULT), StandardCharsets.UTF_8)
            name to device
        }.getOrNull()
    }
}
