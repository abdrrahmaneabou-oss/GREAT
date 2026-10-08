# GREAT — Freeze

Android packet-control application with a single Freeze capability and a user-editable target list.

## Current build

- Official AmneziaWG engine and one live path: Android TUN → GREAT → AmneziaWG.
- Secure `.conf` import and Android Keystore-backed AES-GCM storage.
- Expandable target card: enter an installed package name and press Add; remove entries individually.
- Up to 15 unique packages, persisted across launches. No built-in game list.
- `ConnectivityManager.getConnectionOwnerUid` checks the connection owner; GREAT's own UID and unknown owners are never targeted.
- A movable floating Freeze button and a persisted 1–10 second auto-release duration.
- arm64-v8a, Android 10+, version `0.3.0-freeze-only`.

## Freeze rules

Only unfragmented inbound IPv4 UDP packets belonging to selected applications are eligible.
Remote source ports 7000–10000 inclusive bypass Freeze. The UDP payload must be strictly greater than `20 + random(0..9)` and strictly less than `450 + random(0..49)`; thresholds are sampled per packet.

The global RAM queue holds at most 10,000 packets across all selected apps. Overflow evicts the oldest packet. Manual off or timeout releases retained packets in queue order to the Android TUN; after even indices starting at 2, release pauses for 1–3 ms. TCP, IPv6, outbound and unmatched traffic pass normally. There is no Jitter feature in GREAT.

Changing the target list ends the current Freeze and releases its held packets. VPN stop/reset discards retained packets and cancels pending release work. An empty list affects no app. Targeting changes Freeze eligibility only; normal VPN routing still follows the imported config.

Package names are resolved to installed UIDs when the VPN starts and when packages or the list change. Android apps sharing a UID cannot be distinguished by this API. Package visibility is declared for user-entered arbitrary package names. No packet payloads or config secrets are logged.

## Validation

GitHub Actions runs `gradle :app:testDebugUnitTest :app:assembleDebug` and publishes `GREAT-freeze-only-debug`.
Real-device verification is still required for connection ownership, background lifecycle and observable application behavior.
