# Client cryptography and persistence

`SecureStore` wraps the official Signal `libsignal-client` and `libsignal-android` 0.104.0 APIs for
PQXDH and Double Ratchet sessions. Its synchronous calls must run on an IO dispatcher. Pin a fetched
peer identity with `rememberPeer`, compare its displayed Signal numeric safety code out of band,
then call `verifyPeer`; session setup, encryption, and decryption require that verification.

Public identity and prekeys are generated on-device. Session setup accepts a selected server
`preKey` object, a one-item `preKeys` array, or an exhausted bundle with no one-time key. The
server must preserve one-time-key consumption when accepting later public-bundle updates; these
updates must not make already-issued keys available again.

Ratchet records, local messages, and queued ciphertext are AES-256-GCM encrypted in SQLite using a
non-exportable Android Keystore key and table/row-bound associated data. Ratchet advancement and
outbox insertion share one SQLite transaction. Retries use the stored ciphertext; the outbox is
bounded to 100 entries. `encryptAndQueue` takes optional `displayText` for chat UI, while call-key
payloads can be queued without creating a chat-history row. `decryptAndStore` commits the ratchet
advance and validated chat message together; call-key payloads are returned to the service without
being stored as chat messages.

This scoped module covers client chat crypto and local persistence only. It does not provide voice
media E2EE or conceal transport metadata, and it is not a security audit. Signal artifacts declare
**AGPLv3**; review and meet the license's applicable notice and source-availability requirements
before distributing an APK that includes them. The libsignal project states that use outside Signal
is unsupported and its APIs may change without notice.
