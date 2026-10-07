# GREAT: four-build contract

## Build 1 — Foundation
- Clean Android app and UI shell.
- Secure `.conf` import and storage.
- Official AmneziaWG transport adapter boundary.
- VPN lifecycle boundary.
- Live GREAT packet spine between Android TUN and AmneziaWG.
- Capability state store and tests.

### Status
Completed and verified on-device: VPN connectivity works through `TUN -> GREAT -> AmneziaWG`.

## Build 2 — Capabilities

### Build 2A — capability foundation
- Minimal IPv4/IPv6 parser.
- Protocol classifier.
- Shared `PacketContext` and policy boundary.
- Lock-free diagnostics counters.
- Bounded shared scheduler.
- Generic selector boundary with no hard-coded application assumptions.
- Ghost selective-policy implementation behind that selector.
- Floating capability-control overlay foundation.
- Deterministic parser, policy and scheduler tests.

### Build 2B — complete capability behavior
- Final targeting semantics supplied explicitly rather than inferred.
- Freeze state machine and controlled hold/release.
- Ghost live selector wiring.
- Teleport capture/transition/replay state machine.
- Shared scheduler integration for every retained packet.
- Queue limits, cancellation and shutdown behavior.
- Deterministic tests for every state transition.

## Build 3 — Production
- Performance profiling and allocation cleanup.
- Network/lifecycle recovery.
- Final overlay and UI.
- Diagnostics, regression tests and release hardening.
- Delete any class without a justified production responsibility.

## Build 4 — Bugfix only
No new architecture or features. Fix only bugs found during real-device testing and add a regression test for each.
