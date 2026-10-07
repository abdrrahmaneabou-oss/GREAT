package com.great.app.core;

import java.util.Objects;

/** Ghost is a selective suppression policy; selection is supplied independently from capability state. */
public final class GhostPolicy implements PacketPolicy {
    private final PacketSelector selector;

    public GhostPolicy(PacketSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    @Override public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        if (!state.enabled(Capability.GHOST)) return PacketDecision.PASS;
        return selector.matches(context) ? PacketDecision.DROP : PacketDecision.PASS;
    }
}
