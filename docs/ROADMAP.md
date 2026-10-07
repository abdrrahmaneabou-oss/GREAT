# GREAT: four-build contract

## Build 1 — Foundation
- Clean Android app and UI shell.
- Secure `.conf` import and storage.
- Official AmneziaWG transport adapter boundary.
- VPN lifecycle boundary.
- Pure packet pipeline with PASS-only policy.
- Capability state store and tests.

### Exit criteria
Build 1 is complete only after the official AmneziaWG backend is wired and real-device traffic passes through the pipeline without modification, leaks or recursion.

## Build 2 — Capabilities
- Freeze, Ghost and Teleport expressed as policies/state machines.
- Shared queue/scheduler only where a capability actually needs it.
- Overlay sends intents to `CapabilityController`; it never edits packets.
- Deterministic tests for every state transition.

## Build 3 — Production
- Performance profiling and allocation cleanup.
- Network/lifecycle recovery.
- Final overlay and UI.
- Diagnostics, regression tests and release hardening.
- Delete any class without a justified production responsibility.

## Build 4 — Bugfix only
No new architecture or features. Fix only bugs found during real-device testing and add a regression test for each.
