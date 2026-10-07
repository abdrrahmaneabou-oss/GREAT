package com.great.app.core;

/** Build 1 invariant: every classified packet passes unchanged. */
public final class PassPolicy implements PacketPolicy {
    @Override public PacketDecision decide(PacketEnvelope packet, EngineSnapshot state) {
        return PacketDecision.PASS;
    }
}
