# Android push setup

The signaling service can send Firebase Cloud Messaging (FCM) data-only wake-up hints for queued messages and incoming calls. FCM is optional: the encrypted-message mailbox works without Firebase, but push cannot wake an Android installation until both server credentials and the matching client integration are configured. Configure/import the Android app's public Firebase settings as described in [`ANDROID-PUSH.md`](ANDROID-PUSH.md); the server service-account key is separate and must never be imported by the app.

## 1. Configure Firebase

1. In Firebase Console, create or select the Firebase project for this Android app and add an Android app with the package ID `app.line`.
2. In **Project settings → Cloud Messaging**, enable the Firebase Cloud Messaging API.
3. Create a dedicated service account for the signaling backend. Grant it the **Firebase Cloud Messaging API Admin** role on the target Firebase project. Do not put its key in the Android app.
4. Create a JSON key and copy it directly to a protected file on the backend host, for example `/etc/line/fcm-service-account.json`. Set its owner to the API service user and permissions to `0600`. Keep it outside the repository, backups shared with clients, and application images.

The server uses the official Firebase Admin SDK for Node.js (`firebase-admin` is pinned in `server/package.json`). Firebase's Admin SDK sends through the FCM HTTP v1 API; see [Firebase Admin SDK send](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk).

## 2. Configure the signaling service

Set both variables in the server's protected environment file (for example `/etc/line/api.env`):

```ini
FCM_PROJECT_ID=your-firebase-project-id
GOOGLE_APPLICATION_CREDENTIALS=/etc/line/fcm-service-account.json
```

Restart the existing signaling service using its normal deployment procedure. No new API host or public endpoint is required. The service validates the credential file at startup. Leave **both** variables unset to disable FCM while retaining the persistent mailbox; setting only one or supplying an invalid credential file prevents startup rather than silently claiming push is available.

FCM registration tokens are stored in `PUSH_TOKEN_FILE` (default `${DATA_FILE}.push.json`) with atomic replacement and mode `0600`. Treat this file and its backups as credentials. Push registration is per account and is cleared when that account is blocked. The Firebase private key and FCM tokens must never be committed, copied into an APK, printed to logs, or pasted into support messages.

## 3. Version-7 Android client contract

The server changes in this release are backend-only. The Android client must also integrate Firebase Messaging before users can see OS notifications:

1. Obtain the FCM registration token using the Android Firebase Messaging SDK (`FirebaseMessaging.getToken()`), not the shorter Firebase Installation ID (FID), and refresh it when Firebase rotates it. The signaling service accepts registration tokens from 25 to 4,096 bytes and rejects whitespace.
2. After registering its normal authenticated signaling session with `protocolVersion: 7`, send `{ "type": "push_register", "token": "<FCM token>" }` over `/signal`. The server binds the token to that session's account; a client cannot set another account's token.
3. Handle generic FCM `data` payloads in the app and post an appropriate local notification. The only payload fields are `kind` and `id`: `message` identifies a queued envelope, `call` identifies an incoming call, and `call_ended` asks the client to dismiss an ended incoming-call notification. Payloads deliberately omit message text, sender numbers, and call authorization data.
4. For a message wake-up, reconnect to `/signal`, receive queued `envelope` events, decrypt locally, save the message, and only then send `{ "type": "delivery_ack", "id": "<message-uuid>" }`. After verifying a peer, request `{ "type": "inbox_sync" }` to retrieve any ciphertext the client previously deferred; process the serial WebSocket event queue through `{ "type": "inbox_complete" }` before reporting the inbox synchronized. The server keeps ciphertext until acknowledgement or the seven-day TTL. Delivery is at-least-once, so deduplicate by message ID.
5. For a call wake-up, reconnect within the 45-second ringing window, receive the pending `incoming` event, and use the normal authenticated `join_call` flow. FCM is only a wake-up hint; it does not recreate calls after a signaling-process restart or bypass LiveKit authorization. A `call_ended` hint dismisses a stale incoming-call notification.

The version-7 signaling registration response includes `pushEnabled`, and a successful token update returns `{ "type": "push_registered", "pushEnabled": true }`. `pushEnabled: false` means the server has no usable push configuration/token. Version-6 clients retain the legacy `sent` response but do not acknowledge recipient delivery; update both app installations to protocol version 7 to use delivery receipts and clean up queued ciphertext promptly.

## Delivery and privacy notes

- Messages are ciphertext-only in the durable mailbox. FCM never receives their ciphertext, plaintext, sender, or recipient number.
- Offline message pushes use high priority and have a 24-hour FCM TTL. Offline call pushes use high priority and a 45-second TTL. FCM delivery is best-effort, not a substitute for retrying the authenticated WebSocket connection.
- Android decides how notifications are displayed and whether they are visible on the lock screen. Configure notification channels and privacy-safe notification text in the client; do not put plaintext chat content in push payloads.
- The server stores account-bound FCM tokens in a separate `0600` file and removes tokens rejected by FCM as invalid/unregistered. Other provider errors are intentionally not logged with token or message data.
- No live Firebase credentials are part of the backend test suite. The tests inject a push sender to verify payload minimization, queueing, offline-call wake-ups, TTLs, and token ownership without sending a real notification.
