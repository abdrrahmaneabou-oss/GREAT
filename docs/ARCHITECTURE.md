# GREAT architecture

GREAT is a clean-room application. FOX is a behavioral reference only; no FOX source, DEX, native library, resource or compatibility shim is part of this project.

## Dependency direction

UI -> VPN lifecycle -> transport boundary

UI -> capability controller -> packet pipeline

The packet pipeline does not depend on Android UI, AmneziaWG configuration syntax, or a concrete transport.

## Build 1 invariants

1. One source of truth for capability state.
2. One packet decision pipeline.
3. No capability logic in Activity, Service or overlay code.
4. Raw `.conf` is validated, encrypted with AES-GCM and stored under `noBackupFilesDir`; the AES key remains in Android Keystore.
5. Secret material is never logged and raw config buffers are zeroed after use where practical.
6. The AmneziaWG backend is a replaceable `TunnelTransport`. No FOX native binary is reused.
7. Build 1 packet policy is PASS only. Freeze/Ghost/Teleport arrive in Build 2 as policies/state machines, not new networking stacks.
