package app.line

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.line.crypto.EncryptedEnvelope
import app.line.crypto.PeerIdentityChangedException
import app.line.crypto.SecureStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.Collections
import java.util.UUID

class MessageExchangeTest {
    @Test fun verifiedProfilesExchangeRatchetMessagesAndPersistAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString().replace("-", "")
        val aliceDevice = IsolatedProfile(context, testId, "alice")
        val bobDevice = IsolatedProfile(context, testId, "bob")

        try {
            val alice = aliceDevice.openStore().apply { setLocalNumber(ALICE_NUMBER) }
            val bob = bobDevice.openStore().apply { setLocalNumber(BOB_NUMBER) }
            val aliceBundle = alice.publicBundle()
            val bobBundle = bob.publicBundle()
            val aliceSafetyCode = alice.rememberPeer(BOB_NUMBER, bobBundle)
            val bobSafetyCode = bob.rememberPeer(ALICE_NUMBER, aliceBundle)
            assertEquals("Both profiles must display the same safety code", aliceSafetyCode, bobSafetyCode)

            alice.verifyPeer(BOB_NUMBER)
            bob.verifyPeer(ALICE_NUMBER)
            assertTrue(alice.isVerified(BOB_NUMBER))
            assertTrue(bob.isVerified(ALICE_NUMBER))
            alice.establishSession(BOB_NUMBER, withSelectedPreKey(bobBundle))

            val aliceIds = ArrayList<String>()
            val bobIds = ArrayList<String>()
            for (index in 1..4) {
                val aliceText = "Alice message $index"
                val aliceId = "exchange-$testId-alice-$index"
                val aliceEnvelope = alice.encryptAndQueue(
                    BOB_NUMBER, aliceId, chatPayload(aliceId, aliceText), aliceText,
                )
                aliceIds += aliceId
                val receivedByBob = bob.decryptAndStore(
                    ALICE_NUMBER, aliceEnvelope.cipherType, aliceEnvelope.body, aliceId,
                )
                assertNotNull(receivedByBob)
                assertEquals("chat", receivedByBob?.getString("kind"))
                assertEquals(aliceId, receivedByBob?.getString("id"))
                assertEquals(aliceText, bob.messages(ALICE_NUMBER).single { it.id == aliceId }.text)
                alice.markDelivered(aliceId)
                assertTrue(alice.outbox().none { it.id == aliceId })
                assertEquals("delivered", alice.messages(BOB_NUMBER).single { it.id == aliceId }.status)

                val bobText = "Bob reply $index"
                val bobId = "exchange-$testId-bob-$index"
                val bobEnvelope = bob.encryptAndQueue(
                    ALICE_NUMBER, bobId, chatPayload(bobId, bobText), bobText,
                )
                bobIds += bobId
                val receivedByAlice = alice.decryptAndStore(
                    BOB_NUMBER, bobEnvelope.cipherType, bobEnvelope.body, bobId,
                )
                assertNotNull(receivedByAlice)
                assertEquals("chat", receivedByAlice?.getString("kind"))
                assertEquals(bobId, receivedByAlice?.getString("id"))
                assertEquals(bobText, alice.messages(BOB_NUMBER).single { it.id == bobId }.text)
                bob.markDelivered(bobId)
                assertTrue(bob.outbox().none { it.id == bobId })
                assertEquals("delivered", bob.messages(ALICE_NUMBER).single { it.id == bobId }.status)
            }

            aliceDevice.closeStore()
            bobDevice.closeStore()

            val reopenedAlice = aliceDevice.openStore()
            val reopenedBob = bobDevice.openStore()
            assertTrue(reopenedAlice.isVerified(BOB_NUMBER))
            assertTrue(reopenedBob.isVerified(ALICE_NUMBER))
            assertTrue(reopenedAlice.hasSession(BOB_NUMBER))
            assertTrue(reopenedBob.hasSession(ALICE_NUMBER))
            assertEquals(4, reopenedAlice.messages(BOB_NUMBER).count { it.outgoing })
            assertEquals(4, reopenedAlice.messages(BOB_NUMBER).count { !it.outgoing })
            assertEquals(4, reopenedBob.messages(ALICE_NUMBER).count { it.outgoing })
            assertEquals(4, reopenedBob.messages(ALICE_NUMBER).count { !it.outgoing })
            assertTrue(aliceIds.all { id -> reopenedAlice.messages(BOB_NUMBER).any { it.id == id && it.status == "delivered" } })
            assertTrue(bobIds.all { id -> reopenedBob.messages(ALICE_NUMBER).any { it.id == id && it.status == "delivered" } })

            val postRestartAliceId = "exchange-$testId-alice-after-reopen"
            val postRestartAliceText = "Alice after restart"
            val postRestartAliceEnvelope = reopenedAlice.encryptAndQueue(
                BOB_NUMBER, postRestartAliceId,
                chatPayload(postRestartAliceId, postRestartAliceText), postRestartAliceText,
            )
            assertEquals(
                postRestartAliceText,
                reopenedBob.decryptAndStore(
                    ALICE_NUMBER, postRestartAliceEnvelope.cipherType, postRestartAliceEnvelope.body,
                    postRestartAliceId,
                )?.getString("text"),
            )
            reopenedAlice.acknowledgeSent(postRestartAliceId)

            val postRestartBobId = "exchange-$testId-bob-after-reopen"
            val postRestartBobText = "Bob after restart"
            val postRestartBobEnvelope = reopenedBob.encryptAndQueue(
                ALICE_NUMBER, postRestartBobId,
                chatPayload(postRestartBobId, postRestartBobText), postRestartBobText,
            )
            assertEquals(
                postRestartBobText,
                reopenedAlice.decryptAndStore(
                    BOB_NUMBER, postRestartBobEnvelope.cipherType, postRestartBobEnvelope.body,
                    postRestartBobId,
                )?.getString("text"),
            )
            reopenedBob.acknowledgeSent(postRestartBobId)
            assertTrue(reopenedAlice.outbox().isEmpty())
            assertTrue(reopenedBob.outbox().isEmpty())
        } finally {
            aliceDevice.cleanup()
            bobDevice.cleanup()
        }
    }

    @Test fun unverifiedReceiverKeepsQueuedCiphertextAndChangedIdentityIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString().replace("-", "")
        val aliceDevice = IsolatedProfile(context, testId, "alice")
        val bobDevice = IsolatedProfile(context, testId, "bob")
        val changedIdentityDevice = IsolatedProfile(context, testId, "changed-identity")

        try {
            val alice = aliceDevice.openStore().apply { setLocalNumber(ALICE_NUMBER) }
            val bob = bobDevice.openStore().apply { setLocalNumber(BOB_NUMBER) }
            val changedIdentity = changedIdentityDevice.openStore().apply { setLocalNumber("55556666") }
            val aliceBundle = alice.publicBundle()
            val bobBundle = bob.publicBundle()
            val changedBundle = changedIdentity.publicBundle()

            val aliceSafetyCode = alice.rememberPeer(BOB_NUMBER, bobBundle)
            val bobSafetyCode = bob.rememberPeer(ALICE_NUMBER, aliceBundle)
            assertEquals(aliceSafetyCode, bobSafetyCode)
            alice.verifyPeer(BOB_NUMBER)
            alice.establishSession(BOB_NUMBER, withSelectedPreKey(bobBundle))

            val id = "exchange-$testId-unverified"
            val text = "Must remain queued until verified"
            val envelope: EncryptedEnvelope = alice.encryptAndQueue(
                BOB_NUMBER, id, chatPayload(id, text), text,
            )
            var denialMessage = ""
            try {
                bob.decryptAndStore(ALICE_NUMBER, envelope.cipherType, envelope.body, id)
                fail("An unverified receiver must not decrypt a message")
            } catch (expected: IllegalStateException) {
                denialMessage = expected.message.orEmpty()
            }
            assertTrue(denialMessage.contains("verified"))
            val queued = alice.outbox().single { it.id == id }
            assertEquals(envelope.cipherType, queued.cipherType)
            assertEquals(envelope.body, queued.body)
            assertEquals("queued", alice.messages(BOB_NUMBER).single { it.id == id }.status)
            assertTrue(bob.messages(ALICE_NUMBER).none { it.id == id })

            bob.verifyPeer(ALICE_NUMBER)
            assertEquals(bobSafetyCode, bob.safetyCode(ALICE_NUMBER))
            val received = bob.decryptAndStore(ALICE_NUMBER, queued.cipherType, queued.body, id)
            assertNotNull(received)
            assertEquals(text, received?.getString("text"))
            assertNull(bob.decryptAndStore(ALICE_NUMBER, queued.cipherType, queued.body, id))
            assertEquals(1, bob.messages(ALICE_NUMBER).count { it.id == id })
            alice.acknowledgeSent(id)
            assertTrue(alice.outbox().none { it.id == id })

            try {
                bob.rememberPeer(ALICE_NUMBER, changedBundle)
                fail("A changed peer identity must be rejected rather than silently repinned")
            } catch (_: PeerIdentityChangedException) {
            }
            assertTrue(bob.isVerified(ALICE_NUMBER))
            assertEquals(bobSafetyCode, bob.safetyCode(ALICE_NUMBER))
        } finally {
            aliceDevice.cleanup()
            bobDevice.cleanup()
            changedIdentityDevice.cleanup()
        }
    }

    private fun chatPayload(id: String, text: String): ByteArray =
        JSONObject().put("kind", "chat").put("id", id).put("text", text).toString().toByteArray(Charsets.UTF_8)

    private fun withSelectedPreKey(publicBundle: JSONObject): JSONObject {
        val selected = publicBundle.getJSONArray("preKeys").getJSONObject(0)
        return JSONObject(publicBundle.toString()).put("preKey", JSONObject(selected.toString()))
    }

    private class IsolatedProfile(baseContext: Context, testId: String, deviceName: String) {
        private val context = IsolatedStoreContext(baseContext, testId, deviceName)
        private var store: SecureStore? = null

        fun openStore(): SecureStore {
            check(store == null) { "Close the profile before reopening its store" }
            return SecureStore(context).also { store = it }
        }

        fun closeStore() {
            store?.close()
            store = null
        }

        fun cleanup() {
            closeStore()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val testAliasPrefix = "${context.packageName}."
            Collections.list(keyStore.aliases())
                .filter { it.startsWith(testAliasPrefix) }
                .forEach(keyStore::deleteEntry)
            context.deleteDatabase("line-secure-store.db")
            context.profileDirectory.deleteRecursively()
        }
    }

    private class IsolatedStoreContext(
        baseContext: Context,
        testId: String,
        deviceName: String,
    ) : ContextWrapper(baseContext) {
        val profileDirectory = File(baseContext.cacheDir, "line-secure-store-tests/$testId/$deviceName")
        private val isolatedPackageName = "${baseContext.packageName}.line-test-$testId.$deviceName"

        init {
            check(profileDirectory.mkdirs() || profileDirectory.isDirectory)
        }

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = isolatedPackageName

        override fun getDatabasePath(name: String): File = File(profileDirectory, name)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase {
            check(profileDirectory.mkdirs() || profileDirectory.isDirectory)
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase {
            check(profileDirectory.mkdirs() || profileDirectory.isDirectory)
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }

        override fun deleteDatabase(name: String): Boolean =
            SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    private companion object {
        const val ALICE_NUMBER = "11112222"
        const val BOB_NUMBER = "33334444"
    }
}
