# Android push notifications

Line 0.7 includes Firebase Cloud Messaging (FCM) delivery and a bounded background wake-up path. Push is deliberately limited to a generic alert plus a request for the existing `CallService` to reconnect and process its encrypted inbox; message text, peer numbers, URLs, and call media are never included in an FCM payload. The background worker binds the existing service, waits up to 20 seconds for its normal socket registration and up to 10 more seconds for its inbox-complete acknowledgement. It does not start a microphone foreground service or answer a call in the background.

## Firebase Android configuration

1. Create or select the Firebase project and register an Android app with package name `app.line`; enable Firebase Cloud Messaging.
2. Download that Android app's `google-services.json` from Firebase Console.
3. In the administrator configuration UI, import the **text contents** of that file through `PushConfiguration.import(context, json)`. The app verifies `project_info`, the Android package, `mobilesdk_app_id`, and `api_key.current_key`, then stores those client values in private `line-push` preferences. Re-importing a different configuration requires restarting Line to reinitialize Firebase.

Only the Android client configuration is accepted. A service-account JSON, `private_key`, or `client_email` is rejected. Do not put Firebase Admin SDK credentials or an FCM server key in the APK, the Android preferences, or the import UI. The client API key is not a server credential; protect it with the restrictions supported for the Firebase project.

`PushConfiguration.status(context)` reports the Firebase client setup state without returning the API key or registration token; client setup alone does not verify server delivery. `PushConfiguration.token(context)` is suspendable and returns the FCM registration token expected by Firebase Admin. Firebase Messaging's `onNewToken` callback stores refreshed tokens privately and enqueues a worker to bring the normal service online. The service registers that token with the authenticated signaling socket after it connects.

## Backend and payload contract

The backend must accept the service's `push_register` WebSocket message and associate that device's FCM registration token with the authenticated Line account. It must send Android data-only FCM messages at high priority, with exactly these data keys:

| `kind` | `id` | Effect |
| --- | --- | --- |
| `message` | UUID | Generic private “New message” notification, then reconnect and sync |
| `call` | UUID | Generic incoming-call alert, then reconnect and sync |
| `call_ended` | UUID | Cancel the matching generic call alert, then sync |

Do not include `notification`, `title`, `body`, `peer`, `url`, message text, registration IDs, or any other data key. Push alerts are hints only; the authenticated socket and existing end-to-end encrypted inbox remain authoritative. When the service processes an incoming message or call with that UUID, its integration should call `PushAlerts.dismissMessage(context, id)` or `PushAlerts.dismissCall(context, id)` to clear the matching generic push alert.

## Delivery limits

Users must allow Android notifications and leave the app's notification channels enabled. Android may delay or suppress background work, and force-stop, reboot-before-first-unlock, missing Google Play services, network loss, or manufacturer battery restrictions can prevent prompt delivery. FCM cannot guarantee delivery after a force-stop. Push delivery also remains inactive until a Firebase Android configuration is imported and the backend has push credentials and token registration/delivery support; this repository change does not provide cloud credentials or configure a Firebase project.

## Pinned Android artifacts

- Firebase Messaging `25.0.1`.
- AndroidX WorkManager KTX `2.12.0`.

The artifacts are pinned to exact versions from [Google Maven](https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-messaging/maven-metadata.xml) and [AndroidX WorkManager release information](https://developer.android.com/jetpack/androidx/releases/work). The [Firebase Messaging API reference](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessaging) documents `getToken()` and its service callback is documented in the [FirebaseMessagingService reference](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService).
