# GREAT architecture

## Packet path

`Android TUN → PacketPipeline → AF_UNIX packet bridge → official AmneziaWG → network`

Inbound packets follow the reverse path and use the same pipeline. Freeze delays delivery to the app, never re-sends inbound packets to the remote server.

## Ownership and targets

`TargetAppsStore` persists up to 15 user-entered packages and resolves installed UIDs. `GreatVpnService` reloads those UIDs at startup, preference changes and package lifecycle broadcasts. Each reload disables Freeze and installs a fresh `ConnectionOwnerSelector`.

The selector checks IPv4 UDP flow ownership with `getConnectionOwnerUid`. Outbound packets use source as local and destination as remote; inbound packets reverse those roles. Known owners are cached for 30 seconds in a 256-entry full-address/protocol/port LRU. Unknown ownership and lookup errors never match. GREAT's process UID is always excluded. Shared Android UIDs are an unavoidable ownership boundary.

No per-app VPN allowlist is imposed: imported routes still control tunnel routing. Only Freeze eligibility uses the editable package list.

## Freeze

`GreatEngine` owns the single state controller, configurable target selector, `FreezeCore`, pipeline and diagnostics. The policy applies the ownership gate before the core's incoming UDP size/port rules. The only capability enum value and floating control are Freeze.

A locked FIFO copies eligible packets into RAM with a 10,000-packet global bound and O(1) size/eviction. A timer disables Freeze after the selected duration. Release writes retained packets through the same transport and preserves their queue order. Queue transitions are serialized; network writes never hold the queue lock. Session generations invalidate queued release work across reset/detach. An already in-flight write may complete during stop; transport closure prevents it from reaching a new tunnel.

The base thresholds are 20/450 with per-packet random additions 0..9/0..49 and strict comparisons. Remote ports 7000..10000 are exempt. Fragmented traffic is passed rather than interpreted as a complete UDP message. No protocol conversion, legacy proxy socket stack or Jitter system is copied from FOX.

## Storage and security

Configs use Android Keystore-backed AES-GCM in app-private storage. Target names, Freeze duration and overlay coordinates use private preferences. Packet buffers are RAM-only. Diagnostics contain counts, never packet bodies or credentials. The upstream AmneziaWG module remains pinned and unchanged.
