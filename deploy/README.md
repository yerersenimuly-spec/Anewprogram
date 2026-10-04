# LiveKit and signaling deployment

This is a native-process deployment template; it does not require Docker or Compose. Replace every `example.org` hostname and certificate path before starting services. The public endpoints are `wss://api.example.org/signal` for signaling and `wss://rtc.example.org` for LiveKit. The signaling process holds the LiveKit API secret and issues short-lived, room-scoped participant JWTs.

## LiveKit

Use an official LiveKit Server binary and the configuration in [`livekit.yaml`](livekit.yaml). The locally smoke-tested version is **1.13.7**; pin this version for a reproducible deployment, then test upgrades separately:

```sh
livekit-server --config /etc/livekit/livekit.yaml
```

Keep `room.auto_create: false`: the API explicitly provisions each fixed-roster room before inviting participants. Otherwise a still-valid room token can recreate a deleted room. Room deletion is best-effort; a failed deletion can leave an existing room accessible until its grants expire. Monitor and resolve media-service failures rather than treating signaling hangup as token revocation.

Install the configuration as `/etc/livekit/livekit.yaml` and create `/etc/livekit/api-keys.yaml` as a YAML map whose key and secret match the corresponding Node environment variables:

```yaml
replace-with-api-key: replace-with-long-random-secret
```

Generate independent high-entropy values, keep this file owned by the LiveKit service account, and set permissions to `0600`. Do not commit the key file. Set these environment variables for both the LiveKit server credentials and the Node API process:

```text
LIVEKIT_API_KEY=<key from api-keys.yaml>
LIVEKIT_API_SECRET=<matching secret>
LIVEKIT_URL=wss://rtc.example.org
```

For signaling, run `cd server && npm ci && npm start` under a process manager with the environment above and `DATA_FILE` set to a protected persistent path. Configure service restart-on-failure and graceful stop; do not print environment variables or WebSocket message bodies in logs.

The provided Caddyfile terminates trusted public TLS for the API and LiveKit signaling/WebSocket endpoints, then proxies those HTTP/WebSocket connections to loopback ports 3000 and 7880. WebRTC media and embedded TURN do not pass through this HTTP proxy. The LiveKit host needs publicly routable UDP/TCP ports from `livekit.yaml`: TCP 7881, UDP 50000–60000, TURN UDP 3478 and TURN/TLS TCP 5349. Keep 7880 private behind Caddy. `use_external_ip: true` assumes the host has a public address; adjust it and firewall/NAT mappings for the actual network.

The TURN certificate in the template must be a trusted certificate for `turn.example.org` and readable by LiveKit. Provide it via your certificate manager at the configured paths. The default TURN/TLS port 5349 is not allowed through every restrictive firewall. LiveKit documents that without a layer-4 load balancer, TURN/TLS should advertise port 443; when Caddy already owns TCP 443, use a dedicated TURN public IP/443 or a TCP-pass-through load balancer rather than trying to proxy TURN through the HTTP Caddy site. The UDP media/TURN ports must also be opened at the host and cloud firewall.

## TLS pinning and privacy

Caddy obtains and renews TLS certificates for the API and RTC hostnames. In the Android client, pin the SPKI public key separately for each TLS hostname, include a tested backup pin, and stage key changes in an app release before rotating the server certificate key. Avoid a single short-lived leaf-certificate pin that can lock all clients out on renewal. TURN/TLS uses the separate trusted certificate configured for the TURN hostname. TLS protects links to services; it is not SAS verification or end-to-end encryption.

LiveKit room tokens do not enable media E2EE. Clients still have to configure LiveKit E2EE/Insertable Streams and hold a per-call key on participants' devices; clients likewise implement Signal Protocol Double Ratchet and SAS verification for chat. The API stores public key bundles and call/routing state. LiveKit/TURN and the API can observe participant, IP, timing, and routing metadata. Do not claim anonymity, zero metadata, or privacy from participants' endpoint compromise. Disable recording/egress and do not add untrusted agents if the privacy model excludes them.

This template grants audio microphone publishing only. Opus publication bitrate is controlled by the client SDK's publish options and network congestion control, not by this signaling service; selecting an aggressive maximum cannot guarantee low latency or avoid packet loss. Tune and verify it in the mobile client on representative networks.

Optional push wake-ups use UnifiedPush instead of Firebase. [`ntfy-server.yml.example`](ntfy-server.yml.example) and the commented `ntfy.example.org` block in [`Caddyfile`](Caddyfile) show a self-hosted ntfy on the same VPS; setup, privacy and limits are in [`../docs/PUSH-SETUP.md`](../docs/PUSH-SETUP.md). Updating an existing 0.7.x server without touching its data is described in [`../docs/API.md`](../docs/API.md#9-обновление-действующего-vps-без-потери-данных).
