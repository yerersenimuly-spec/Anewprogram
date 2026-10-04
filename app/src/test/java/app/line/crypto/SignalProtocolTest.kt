package app.line.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.nio.charset.StandardCharsets

class SignalProtocolTest {
    @Test
    fun pqxdhDoubleRatchetExchangeAndReplayRejection() {
        val alice = Endpoint("+15550000001")
        val bob = Endpoint("+15550000002")
        val aliceAddress = SignalProtocolAddress(alice.number, 1)
        val bobAddress = SignalProtocolAddress(bob.number, 1)
        alice.store.saveIdentity(bobAddress, bob.identity.publicKey)
        bob.store.saveIdentity(aliceAddress, alice.identity.publicKey)

        SessionBuilder(alice.store, bobAddress, aliceAddress).process(bob.bundle)
        val first = SessionCipher(alice.store, aliceAddress, bobAddress).encrypt("hello Bob".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        val received = SessionCipher(bob.store, bobAddress, aliceAddress)
            .decrypt(PreKeySignalMessage(first.serialize()))
        assertEquals("hello Bob", String(received, StandardCharsets.UTF_8))

        assertThrows(Exception::class.java) {
            SessionCipher(bob.store, bobAddress, aliceAddress)
                .decrypt(PreKeySignalMessage(first.serialize()))
        }

        val reply = SessionCipher(bob.store, bobAddress, aliceAddress).encrypt("hello Alice".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        val replyText = SessionCipher(alice.store, aliceAddress, bobAddress)
            .decrypt(SignalMessage(reply.serialize()))
        assertEquals("hello Alice", String(replyText, StandardCharsets.UTF_8))
    }

    @Test
    fun pqxdhExchangeWorksWhenServerHasNoOneTimePrekeyLeft() {
        val alice = Endpoint("+15550000001")
        val bob = Endpoint("+15550000002")
        val aliceAddress = SignalProtocolAddress(alice.number, 1)
        val bobAddress = SignalProtocolAddress(bob.number, 1)
        alice.store.saveIdentity(bobAddress, bob.identity.publicKey)
        bob.store.saveIdentity(aliceAddress, alice.identity.publicKey)

        SessionBuilder(alice.store, bobAddress, aliceAddress)
            .process(bob.bundleWithoutOneTimePreKey())
        val first = SessionCipher(alice.store, aliceAddress, bobAddress).encrypt("usable without OTK".toByteArray())
        val received = SessionCipher(bob.store, bobAddress, aliceAddress)
            .decrypt(PreKeySignalMessage(first.serialize()))
        assertEquals("usable without OTK", String(received, StandardCharsets.UTF_8))
    }

    @Test
    fun changedIdentityIsNotTrustedAndCannotReplaceThePinnedSessionIdentity() {
        val alice = Endpoint("+15550000001")
        val bob = Endpoint("+15550000002")
        val attacker = Endpoint("+15550000002")
        val aliceAddress = SignalProtocolAddress(alice.number, 1)
        val bobAddress = SignalProtocolAddress(bob.number, 1)
        alice.store.saveIdentity(bobAddress, bob.identity.publicKey)

        assertFalse(alice.store.isTrustedIdentity(
            bobAddress, attacker.identity.publicKey, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING,
        ))
        assertThrows(UntrustedIdentityException::class.java) {
            SessionBuilder(alice.store, bobAddress, aliceAddress).process(attacker.bundle)
        }
        assertTrue(alice.store.isTrustedIdentity(
            bobAddress, bob.identity.publicKey, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING,
        ))
    }

    @Test
    fun signalSafetyCodeIsSymmetricAndChangesWithEitherIdentity() {
        val alice = Endpoint("+15550000001")
        val bob = Endpoint("+15550000002")
        val replacementBob = Endpoint("+15550000002")
        fun code(local: Endpoint, remote: Endpoint) = NumericFingerprintGenerator(5200)
            .createFor(
                1,
                local.number.toByteArray(StandardCharsets.UTF_8),
                local.identity.publicKey,
                remote.number.toByteArray(StandardCharsets.UTF_8),
                remote.identity.publicKey,
            )
            .displayableFingerprint.displayText

        assertEquals(code(alice, bob), code(bob, alice))
        assertNotEquals(code(alice, bob), code(alice, replacementBob))
        assertTrue(code(alice, bob).isNotBlank())
    }

    private class Endpoint(val number: String) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        val store = InMemorySignalProtocolStore(identity, registrationId)
        val bundle: PreKeyBundle

        init {
            val oneTimeId = 1
            val signedId = 2
            val kyberId = 3
            val oneTime = ECKeyPair.generate()
            val signed = ECKeyPair.generate()
            val signedSignature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
            val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
            store.storePreKey(oneTimeId, PreKeyRecord(oneTimeId, oneTime))
            store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, System.currentTimeMillis(), signed, signedSignature))
            store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, System.currentTimeMillis(), kyber, kyberSignature))
            bundle = PreKeyBundle(
                registrationId,
                1,
                oneTimeId,
                oneTime.publicKey,
                signedId,
                signed.publicKey,
                signedSignature,
                identity.publicKey,
                kyberId,
                kyber.publicKey,
                kyberSignature,
            )
        }

        fun bundleWithoutOneTimePreKey(): PreKeyBundle {
            val signedId = 4
            val kyberId = 5
            val signed = ECKeyPair.generate()
            val signedSignature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
            val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
            store.storeSignedPreKey(
                signedId,
                SignedPreKeyRecord(signedId, System.currentTimeMillis(), signed, signedSignature),
            )
            store.storeKyberPreKey(
                kyberId,
                KyberPreKeyRecord(kyberId, System.currentTimeMillis(), kyber, kyberSignature),
            )
            return PreKeyBundle(
                registrationId,
                1,
                PreKeyBundle.NULL_PRE_KEY_ID,
                null,
                signedId,
                signed.publicKey,
                signedSignature,
                identity.publicKey,
                kyberId,
                kyber.publicKey,
                kyberSignature,
            )
        }
    }
}
