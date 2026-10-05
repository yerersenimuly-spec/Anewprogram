# LINE signaling and encrypted relay

Node 24 service for registration, public Signal key-bundle discovery, a persistent encrypted-message mailbox, optional UnifiedPush/APNs wake-ups, public profile names, missed-call records, a temporary encrypted attachment relay, and fixed-roster LiveKit audio rooms. The server never receives private identity/session keys, plaintext chat, attachment keys, SDP, or media. It does retain ciphertext for up to seven days, public bundles, account-to-number mappings, profile names, push endpoints, and routing metadata; it is not anonymous or zero-metadata. The complete wire protocol (6, 7 and 8), data formats and the upgrade procedure are in [`../docs/API.md`](../docs/API.md).

## Run and test

```sh
cd server
npm ci
npm test
npm start
```

The service listens on `0.0.0.0:3000`. `GET /` and `/health` return only `{"status":"ok"}`. WebSocket signaling is served at `/signal`; terminate TLS at a trusted reverse proxy and use `wss://` from clients.

## Configuration

| Variable | Meaning |
| --- | --- |
| `DATA_FILE` | Persistent versioned JSON store. Defaults to `server/data/identities.json`; contains SHA-256 installation-token hashes, 8-digit numbers, and public key bundles only. |
| `MAILBOX_FILE` | Atomic mode-`0600` encrypted-message queue; defaults to `${DATA_FILE}.mailbox.json`. Holds at most 1,000 messages total and 100 per recipient for up to seven days, plus at most 5,000 delivery receipts. |
| `PUSH_TOKEN_FILE` | Legacy FCM token store, default `${DATA_FILE}.push.json`. 0.8 neither reads, rewrites nor deletes it; the name only reserves the path (rollback to 0.7.1 keeps working). |
| `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS` | Ignored without any message. Firebase is no longer used; leaving them in the environment is harmless. |
| `PORT`, `HOST` | HTTP listen port and interface; defaults to `3000` and `0.0.0.0`. |
| `LIVEKIT_URL` | Public LiveKit WebSocket URL, for example `wss://rtc.example.org`. Plain `ws://` is rejected. |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | Server-only LiveKit credentials. Keep the secret off clients. If any media setting is missing or invalid, registration and messaging still work but joining returns `media_not_configured`. |
| `ADMIN_PASSWORD_HASH` | Optional scrypt verifier (`scrypt$<salt-base64>$<digest-base64>`). Admin access is disabled when missing or malformed; never configure a plaintext admin code. |
| `ADMIN_DATA_FILE` | Persistent admin settings and blocklist. Defaults to `${DATA_FILE}.admin.json`; atomic writes use mode `0600`, and an invalid existing file stops startup rather than silently resetting controls. |
| `PUSH_ENDPOINT_FILE` | Atomic mode-`0600` per-account UnifiedPush/APNs registrations; defaults to `${DATA_FILE}.push-endpoints.json`, created on the first registration. Holds secret topic URLs: protect it like the identity store. |
| `PUSH_ALLOW_PRIVATE_ENDPOINTS` | `true` lets UnifiedPush endpoints point at private, loopback or single-label hosts. Off by default (SSRF guard). |
| `APNS_KEY_FILE`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_TOPIC` | Optional APNs credentials (P-256 `.p8` key). All four or none; a partial or unreadable configuration prevents startup. |
| `PROFILE_FILE`, `MISSED_FILE` | Atomic mode-`0600` stores for profile names and undelivered missed calls; default `${DATA_FILE}.profiles.json` and `${DATA_FILE}.missed.json`, created on first write. |
| `BLOB_DIR` | Directory for encrypted attachments (mode `0700`, files `0600`); defaults to `${DATA_FILE}.blobs`, created on the first upload. |
| `BLOB_MAX_BYTES`, `BLOB_TTL_MS`, `BLOB_QUOTA_BYTES`, `BLOB_SENDER_PENDING_BYTES` | Attachment limits: 26 MiB per blob (max 64 MiB), 7 days after completion (max 14 days), 1 GiB in total, 200 MiB per sender. |
| `CALL_RESUME_GRACE_MS` | How long a v8 call participant may be offline before the call ends; default `15000`, clamped to `0`–`60000` (`0` disables it). A non-numeric value stops startup. |

Room access tokens are signed by `livekit-server-sdk` 2.19.1 with a 120-second TTL. Each grant is restricted to one roster room, allows microphone publishing and subscription, and disallows data publishing. LiveKit API secrets are never returned to clients. Chat and signaling work without LiveKit being configured; the backend will not issue placeholder media tokens.

With media configured, the API explicitly creates rooms before announcing calls and limits each room to its roster size. Set `room.auto_create: false` in LiveKit as in `deploy/livekit.yaml`; otherwise an unexpired participant token can recreate a deleted room. Creation failures return `media_unavailable` without invitations. Deletion is best-effort, not immediate token revocation: if deletion fails, an existing room may remain joinable until its grants expire.

Each persistent JSON store is written by an atomic rename with mode `0600`. Version-1 identity stores containing `{ "version": 1, "identities": { "<token hash>": "<number>" } }` are accepted. The next successful registration with a public bundle writes version 2 and preserves that installation's existing number. The stores are single-process files; do not run multiple API replicas against them. Back them up securely. Mailbox backups contain encrypted message bodies and routing metadata; delete them according to your retention policy. The profile, push-endpoint and missed-call stores and the blob directory are new in 0.8 and are created only when first needed; the identity, admin and mailbox formats are unchanged, and `scripts/check-data-compat.mjs` (`npm run check:data -- <DATA_FILE>`) validates a data directory read-only with the server's own loaders.

### Admin bootstrap and access

Create a code with at least 12 characters and set only its scrypt hash in the service environment. The helper reads the code from standard input (hidden for terminal input) and prints only the hash:

```sh
cd server
read -r -s -p 'Admin passphrase: ' ADMIN_CODE; printf '\n'
export ADMIN_PASSWORD_HASH="$(printf '%s' "$ADMIN_CODE" | node scripts/hash-admin-password.mjs)"
unset ADMIN_CODE
npm start
```

Store the resulting hash in the server's protected environment configuration, not in source control or a client. Put the service behind HTTPS/WSS at a trusted reverse proxy; mobile clients must use the pinned `wss://` endpoint. Admin login is sent over the existing `/signal` WebSocket as `{ "type": "admin_login", "requestId": "550e8400-e29b-41d4-a716-446655440000", "code": "<passphrase>" }`. A valid login replies with `admin_result`, `ok: true`, and `expiresAt` (milliseconds since epoch). Sessions expire after five minutes and are tied to that socket; reconnecting requires a new login. Five failed attempts per peer address in one minute trigger a one-minute lock. Do not trust `X-Forwarded-For` for this limit.

After login, send `{ "type": "admin", "requestId": "550e8400-e29b-41d4-a716-446655440000", "action": "status" }`. The response contains settings, aggregate online/registered/call metrics, blocked numbers, active call IDs and participant counts, and a maximum of 100 recent event records. Logs contain only fixed event/outcome labels and timestamps—no message bodies, credentials, numbers, addresses, or media data. Other actions are `update_settings` with a partial object of `callsEnabled`, `chatEnabled`, `registrationEnabled`, and/or `maxParticipants` (2–8); `block`/`unblock` with an existing 8-digit `number`; `end_call` with a `callId`; `clear_events`; and `logout`. Successful setting changes are persisted and broadcast as `capabilities`; turning calls off immediately ends active calls, chat-off blocks key publication/lookups and ciphertext relay, registration-off prevents new installations only, and blocking persists and disconnects that account. A blocked account cannot be blocked by its own session.


Admin login is available only on a normally registered WebSocket connection; admin authorization then applies only to that same live socket.

The defaults preserve existing service behavior. Admin controls and state are single-process, as is the identity store. Protect backups and server files; changing or deleting the admin JSON directly is an operator recovery action.

## WebSocket protocol

All messages are JSON text. Registration is required within 10 seconds. Maximum WebSocket message size is 64 KiB; requests are limited to 40 per socket per second. Store operations are serialized through a bounded queue. No client content or credentials are logged.

### Register and publish keys

```json
{
  "type": "register",
  "token": "<64 lowercase hex characters>",
  "bundle": {
    "identityKey": "<base64>",
    "registrationId": 123,
    "signedPreKey": { "id": 1, "publicKey": "<base64>", "signature": "<base64>" },
    "kyberPreKey": { "id": 2, "publicKey": "<base64>", "signature": "<base64>" },
    "preKeys": [{ "id": 3, "publicKey": "<base64>" }]
  }
}
```

Version 7 clients register with the additional field `"protocolVersion": 7`. Their `registered` response includes `pushEnabled`, which is true only when this account has a usable push registration. Version-6 clients may omit the field and retain the legacy `sent` response. `mediaReady` reflects valid LiveKit configuration, not network availability. `keys` with the same bundle shape updates the registered user's public bundle; changing `identityKey` is rejected. Prekey IDs must increase monotonically: a persisted high-water mark prevents registration/key updates from republishing issued keys. The server validates structure/encoding, not signatures; clients verify signatures and compare SAS out of band. A `lookup` normally consumes exactly one prekey and returns it as `bundle.preKey` (also in a one-element legacy `preKeys` array). For SAS and existing sessions, send `consumePreKey: false`: only public identity/signed/Kyber keys are returned without consuming a key or requiring a nonempty pool. Consuming an empty pool returns `prekeys_exhausted`; unknown numbers return `not_found`.

### Encrypted messages

```json
{ "type": "envelope", "to": "12345678", "id": "550e8400-e29b-41d4-a716-446655440000", "cipherType": 2, "body": "<base64 ciphertext>" }
```

Only ciphertext bodies of up to 24 KiB decoded are accepted, and the recipient must be an existing, unblocked account. An online recipient is sent the envelope first; it is then atomically stored, and only after that does the sender receive `{ "type": "queued", "id": "550e8400-e29b-41d4-a716-446655440000" }`. Online recipients receive `{ "type": "envelope", "from": "87654321", "id": "550e8400-e29b-41d4-a716-446655440000", "cipherType": 2, "body": "<base64 ciphertext>" }`; offline recipients receive it after the next successful registration. Reconnect delivery is at-least-once until acknowledged, so clients must deduplicate by message ID and acknowledge only after saving the encrypted message locally:

```json
{ "type": "delivery_ack", "id": "550e8400-e29b-41d4-a716-446655440000" }
```

Only the addressed recipient can acknowledge a pending envelope. The server atomically removes it, stores a seven-day delivery receipt, and notifies an online version-7 sender with `{ "type": "delivered", "id": "550e8400-e29b-41d4-a716-446655440000" }`; receipts are also replayed to the sender after reconnect. `queued` means durable server acceptance, not recipient delivery. For legacy version-6 clients the server returns `sent` after durable acceptance, but they do not send delivery acknowledgements; upgrade both clients to version 7 for delivery status and eventual mailbox cleanup. Messages expire after seven days. Unknown recipients return `not_found`; full queues return `mailbox_full`.

When the recipient is offline and has a push registration, the server sends only the generic payload `{ "kind": "message", "id": "<envelope id>" }`; no sender number or ciphertext is included, and message pushes to one number are coalesced to one per 1.5 seconds. Push is a wake-up hint, not message storage: clients must reconnect and fetch the queued envelope. Protocol-8 senders may mark an envelope `"silent": true` (read receipts): it is delivered and stored like any other but never triggers a push. See [`../docs/PUSH-SETUP.md`](../docs/PUSH-SETUP.md).

Protocol-7 clients can request another authenticated inbox pass after verifying a peer:

```json
{ "type": "inbox_sync" }
```

The server re-announces any pending incoming call first, then sends unexpired delivery receipts and queued ciphertext, and finally `{ "type": "inbox_complete" }`. The same completion event follows the initial `registered` response after the server queues those pending events. WebSocket ordering lets the client process queued ciphertext serially before treating the sync as complete. Sync requests are limited to 10 per account per minute; older protocol versions cannot request a sync. Sync does not modify the account's push registration. Receipts expire after seven days and are capped at 5,000 records, so a later sync may replay a still-retained receipt but cannot reset its retention window.

### Fixed-roster group audio calls

```json
{ "type": "create_call", "members": ["12345678", "87654321"] }
```

One to seven distinct existing, unblocked numbers other than the caller are allowed, for a maximum room size of eight. Online invitees must not be in another call. Offline invitees are allowed only with a push registration (UnifiedPush or APNs) for their own account; the LiveKit room is created before invitations are sent. `call_created` goes to the owner, `incoming` to online invitees, and an opaque `{ "kind": "call", "id": "550e8400-e29b-41d4-a716-446655440000" }` push wake-up to offline invitees. The roster is fixed. A reconnecting invitee receives the pending invitation and can join before the 45-second ring timeout. A callee disconnect before accepting does not end the ring; owner or joined-member disconnect, `leave_call`, `decline_call`, or timeout ends the call and triggers best-effort LiveKit room deletion. An ended offline invitation may receive `{ "kind": "call_ended", "id": "550e8400-e29b-41d4-a716-446655440000" }` so the client can dismiss its incoming-call notification. Each member sends `{ "type": "join_call", "callId": "550e8400-e29b-41d4-a716-446655440000" }` and receives a room-scoped grant; outsiders cannot obtain a token. Without valid LiveKit configuration the server returns `media_not_configured` before it creates or pushes a call.

Protocol-8 sessions get a grace period instead of an immediate end when the owner or a joined member loses its socket: if the same number registers again with protocol 8 within `CALL_RESUME_GRACE_MS`, it receives `{ "type": "call_resume", "callId", "room", "members", "owner", "joined" }` after `registered` and rejoins with `join_call`; otherwise the call ends with `disconnected`. Version-6/7 sessions end the call immediately, as before. Announced calls that end unanswered are recorded as missed for invitees who never joined and are delivered as `missed_call` after each v8 registration until `missed_ack` (20 per recipient, 7 days).

Protocol-8 clients register a UnifiedPush endpoint (or an APNs token) after WebSocket registration:

```json
{ "type": "push_register", "provider": "unifiedpush", "endpoint": "https://ntfy.example.org/up…", "pubKey": "<base64url P-256 point>", "auth": "<base64url 16 bytes>" }
```

The endpoint is bound to the authenticated account and stored separately with mode `0600`; the response is `{ "type": "push_registered", "pushEnabled": true, "provider": "unifiedpush" }`. Endpoints must be `https`, free of credentials and point at public hosts (the DNS answer is validated and the connection uses exactly the validated address); with `pubKey` and `auth` the body is encrypted per RFC 8291. `push_unregister` removes it. A legacy `{ "token": "<FCM token>" }` is answered with `pushEnabled: false` and stores nothing. Endpoints that answer `404` or `410` are deleted. Push data contains only `kind`, an opaque id and, for `call_ended`, a `reason`; clients must never depend on push payloads for message content or call authorization.

An unset/invalid LiveKit configuration makes `join_call` return `media_not_configured`; token-service errors return `media_unavailable`. Network changes on clients should close their WebSocket promptly; dead transports are also detected by heartbeat.

## Protocol 8 additions (0.8.0)

Everything below is gated on `"protocolVersion": 8` and is described message by message in [`../docs/API.md`](../docs/API.md): extra `registered` fields (`protocol`, `serverTime`, `features`, `pushProviders`, `name`); `profile_set` / `profile_get` and `fromName`, `sentAt`, `ownerName`, `bundle.profile`; `silent` envelopes; `push_register` with providers and `push_unregister`; `missed_call` / `missed_ack`; `call_resume`; and encrypted attachments through `blob_create`, `blob_get`, `blob_ack` plus `PUT`/`GET /blob/<uuid>` on the same port. Version-6 and version-7 clients are served exactly as in 0.7.1.

## Upgrading an existing server

Existing data files and environment variables stay as they are. Back up the data directory, check a copy with `npm run check:data -- <copy of DATA_FILE>`, install the new code next to the old one with `npm ci --omit=dev`, and switch; rolling back is swapping the code directory back. The full procedure is in [`../docs/API.md`](../docs/API.md#9-обновление-действующего-vps-без-потери-данных).

## Security boundaries

- Chat E2EE is a client responsibility: clients must implement the Signal Protocol Double Ratchet, generate and keep private keys on-device, verify published key signatures, and use SAS verification. This service stores public bundles and forwards opaque ciphertext only; it cannot attest that client messages are actually encrypted.
- Media encryption is also a client responsibility. LiveKit's room token does **not** enable E2EE by itself. Every client must configure LiveKit E2EE/Insertable Streams with a fresh call key held only by participants and verified key/SAS exchange. Do not enable recording, egress, or untrusted room agents if the privacy model forbids them.
- WebRTC transport encryption alone is not end-to-end encryption through an SFU. LiveKit and TURN still observe connection/participant metadata and IP addresses; the signaling service sees installation numbers, call rosters, online status, and message-routing timing. The service is not untrackable and must not be described as hiding all metadata.
- Use TLS for both API and LiveKit WebSocket endpoints. Mobile clients should pin SPKI public keys for each host with a staged backup pin and a tested rotation/release plan; do not pin short-lived leaf certificates without an overlap strategy. See [`deploy/README.md`](../deploy/README.md).

The process defaults to a single Node instance with limits of 1,000 sockets, 100,000 identities, 30 registration attempts per source IP per minute, and a 1,000-operation persistence queue. Mailboxes and delivery receipts survive restart; live calls and rate limits do not. Scale-out requires coordinated identity storage, one-time-prekey consumption, mailbox/receipt updates, call state, push-endpoint storage, and rate limits.
