# GREAT roadmap

## Build 1 — Foundation
Official AmneziaWG integration, encrypted config import and live TUN/GREAT/AWG forwarding. Connectivity was verified on-device in the prior build.

## Freeze-only build — 0.3.0
One Freeze capability; editable persisted package target card (maximum 15); Android connection-owner targeting; original Freeze size/port rules; 10,000-packet RAM queue; manual/timed release; movable single-button overlay; deterministic rule, owner, queue and lifecycle tests.

## Build 3 — Production
Verify ownership and isolation on a real Android device, including connected and unconnected UDP sockets. Exercise target edits, uninstall/reinstall, timeout, network changes and VPN shutdown. Profile performance and harden background lifecycle and the final UI.

## Build 4 — Bugfix only
Fix bugs identified by real-device testing and add regression coverage.
