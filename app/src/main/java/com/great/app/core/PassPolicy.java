package com.great.app.core;

/** Explicit pass-through policy used when no capability rule applies. */
public final class PassPolicy implements PacketPolicy {
    @Override public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        return PacketDecision.PASS;
    }
}
