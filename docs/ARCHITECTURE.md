# GREAT architecture

GREAT is a clean-room application. FOX is a behavioral reference only; no FOX source, DEX, native library, resource or compatibility shim is part of this project.

## Live packet path

Build 2 begins by making GREAT the owner of the packet boundary instead of handing Android's TUN directly to AmneziaWG:

`Android TUN -> GREAT PacketPipeline -> AF_UNIX packet bridge -> official amneziawg-go -> network`

Inbound traffic follows the reverse path and is evaluated by the same pipeline with `PacketDirection.INBOUND`.

The bridge is intentionally tiny: it implements the upstream `tun.Device` contract over a packet-preserving local socket. Cryptography, handshakes, peer state, obfuscation and UDP transport remain in the pinned upstream AmneziaWG engine.

## Dependency direction

UI -> VPN lifecycle -> transport boundary

UI/overlay -> GreatEngine -> capability controller -> packet pipeline

The packet pipeline does not depend on Android UI, AmneziaWG configuration syntax, or a concrete transport.

## Invariants

1. One process-wide source of truth for capability state (`GreatEngine`).
2. One packet decision pipeline for both directions.
3. No capability logic in Activity, Service or overlay code.
4. Raw `.conf` is validated, encrypted with AES-GCM and stored under `noBackupFilesDir`; the AES key remains in Android Keystore.
5. Secret material is never logged and raw config buffers are zeroed after use where practical.
6. AmneziaWG remains upstream; GREAT adds only its own packet-device adapter around the official engine.
7. The current live policy is still PASS only. HOLD/DELAY/REPLAY remain illegal on the live path until the shared Build 2 scheduler exists.
8. Internal transport failure is fail-closed: the Android TUN remains claimed while the AWG bridge is stopped, preventing accidental direct fallback.
9. The Android package is currently arm64-v8a only by design.

## Build 2 sequence

1. Prove `TUN -> PacketPipeline(PASS) -> AWG` on-device without breaking connectivity.
2. Add bounded game-flow classification and caching.
3. Add the shared scheduler/queue primitives.
4. Add Freeze, Ghost and Teleport as policies/state machines over that single spine.
