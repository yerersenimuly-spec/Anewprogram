# Legacy 0.7.1 data fixtures

Frozen examples of every file the 0.7.1 server leaves on disk. `legacy-data.test.js` starts the current server on
copies of them and requires that the files are served unchanged and are not rewritten unless an event writes them.

| File | Server name (relative to `DATA_FILE`) | Origin |
| --- | --- | --- |
| `identities.v2.json` | `DATA_FILE` | Written by the 0.7.1 server (commit `1358f68`) while registering three accounts. |
| `identities.v1.json` | `DATA_FILE` | The version-1 layout (`hash -> number`) for the same three accounts. |
| `admin.v1.json` | `${DATA_FILE}.admin.json` | Version-1 admin store: `maxParticipants` 6, third account blocked. |
| `mailbox.v1.json` | `${DATA_FILE}.mailbox.json` | Written by 0.7.1: one pending envelope and one delivery receipt. Timestamps were moved to the year 2100 so the fixture never expires. |
| `push.json` | `${DATA_FILE}.push.json` | Written by 0.7.1 after two `push_register` calls with FCM-style tokens (one belongs to the blocked account). |
| `accounts.json` | – | Installation tokens, bundle seeds and numbers of the fixture accounts (test values, no real credentials). |

The bundles are `bundle(seed, 2)` from `test-support/harness.js`.
