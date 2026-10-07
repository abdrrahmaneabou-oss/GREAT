# GREAT

Clean-room Android packet-control engine, designed from the final behavior backward rather than inherited from FOX.

This repository intentionally starts small. Build 1 establishes one packet path, secure AmneziaWG `.conf` import and a transport boundary. The three capabilities are not copied from FOX; they will be implemented as independent policies in Build 2.

## Current status

Implemented in this bootstrap:
- Minimal native Android UI with `Import .conf`.
- Strict, extension-preserving AmneziaWG config parser.
- AES-GCM encrypted config store backed by Android Keystore.
- VPN service lifecycle boundary.
- `TunnelTransport` abstraction and an explicit AmneziaWG adapter seam.
- Pure PASS-only packet pipeline and single capability state store.
- Unit tests for parser behavior and Build 1 pipeline invariants.

Not yet wired:
- Official AmneziaWG native/userspace backend. This is deliberately not copied from the old FOX APK.
- Real TUN forwarding; Build 1 is not complete until the official backend is connected and tested on-device.
- Freeze/Ghost/Teleport; scheduled for Build 2.

See `docs/ARCHITECTURE.md` and `docs/ROADMAP.md`.
