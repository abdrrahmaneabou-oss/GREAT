# GREAT architecture

GREAT is a clean-room application. FOX is a behavioral reference only; no FOX source, DEX, native library, resource or compatibility shim is part of this project.

## Live packet path

GREAT owns the packet boundary instead of handing Android's TUN directly to AmneziaWG:

`Android TUN -> GREAT PacketPipeline -> AF_UNIX packet bridge -> official amneziawg-go -> network`

Inbound traffic follows the reverse path and is evaluated by the same pipeline with `PacketDirection.INBOUND`.

The bridge is intentionally tiny: it implements the upstream `tun.Device` contract over a packet-preserving local socket. Cryptography, handshakes, peer state, obfuscation and UDP transport remain in the pinned upstream AmneziaWG engine.

## Build 2A packet engine

The hot path is now:

`PacketEnvelope -> PacketParser -> PacketMetadata -> PacketClassifier -> PacketContext -> CapabilityPolicyEngine`

The parser extracts only the metadata required by policy decisions. IPv4, IPv6, TCP, UDP, ICMP and common IPv6 extension headers are handled without copying the packet payload.

`EngineDiagnostics` records counters only. Packet contents, endpoint secrets and configuration keys are never logged.

`PacketScheduler` is one bounded shared queue primitive for future delay/release/replay work. Payload bytes are copied only when a packet must outlive the transport hot-path buffer.

## Dependency direction

UI -> VPN lifecycle -> transport boundary

UI/overlay -> GreatEngine -> capability controller -> packet pipeline

The packet pipeline does not depend on Android UI, AmneziaWG configuration syntax, or a concrete transport.

## Invariants

1. One process-wide source of truth for capability state (`GreatEngine`).
2. One packet decision pipeline for both directions.
3. No capability logic in Activity, VPN Service or overlay code.
4. Raw `.conf` is validated, encrypted with AES-GCM and stored under `noBackupFilesDir`; the AES key remains in Android Keystore.
5. Secret material is never logged and raw config buffers are zeroed after use where practical.
6. AmneziaWG remains upstream; GREAT adds only its own packet-device adapter around the official engine.
7. Capability targeting is independent from capability state. The default selector matches nothing until an explicit targeting rule is supplied.
8. Internal transport failure is fail-closed: the Android TUN remains claimed while the AWG bridge is stopped, preventing accidental direct fallback.
9. The Android package is currently arm64-v8a only by design.
10. Queues are bounded; unbounded packet retention is forbidden.

## Build 2 sequence

1. Prove `TUN -> PacketPipeline(PASS) -> AWG` on-device without breaking connectivity. Completed.
2. Add parser, protocol classifier, diagnostics, bounded scheduler and overlay state controls. Build 2A.
3. Define the exact targeting rule for live capability decisions without assuming an application or use-case.
4. Complete Freeze, Ghost and Teleport state machines over the same spine and scheduler. Build 2B.
