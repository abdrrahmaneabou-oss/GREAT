# GREAT

Clean-room Android packet-control engine designed from final behavior backward rather than inherited from FOX.

GREAT keeps one live packet spine and one source of truth for capability state. FOX is used only as a behavioral reference; no FOX source, DEX, native library, resource or compatibility shim is reused.

## Current status

Working and verified on-device:
- Secure AmneziaWG `.conf` import and AES-GCM storage backed by Android Keystore.
- Official AmneziaWG userspace engine integration.
- Android VPN/TUN lifecycle.
- Live packet path: `Android TUN -> GREAT PacketPipeline -> local packet bridge -> AmneziaWG -> network`.
- arm64-v8a-only build for a substantially smaller APK.

Build 2A foundation now adds:
- Minimal IPv4/IPv6 packet parser with TCP/UDP/ICMP metadata.
- Protocol classifier and immutable `PacketContext`.
- Shared capability policy engine.
- Bounded shared packet scheduler.
- Lock-free diagnostics counters without packet-content logging.
- Generic configurable packet selectors with no hard-coded application assumptions.
- Ghost selective-policy implementation behind that selector boundary.
- Floating Freeze / Ghost / Teleport control overlay foundation.
- Deterministic parser, policy and scheduler tests.

## Next

Build 2B will define the exact live targeting semantics explicitly, then complete Freeze hold/release, Ghost live selection and Teleport capture/transition/replay on the same packet spine and scheduler.

Build 3 is production hardening and final UI/performance work. Build 4 remains bugfix-only.

See `docs/ARCHITECTURE.md` and `docs/ROADMAP.md`.
