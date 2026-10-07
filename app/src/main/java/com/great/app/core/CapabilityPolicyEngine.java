package com.great.app.core;

import java.util.Objects;

/** Ordered capability policies. Build 2A wires Ghost first; Freeze and Teleport follow on the same boundary. */
public final class CapabilityPolicyEngine implements PacketPolicy {
    private final PacketPolicy ghost;
    private final PacketPolicy fallback;

    public CapabilityPolicyEngine(PacketPolicy ghost, PacketPolicy fallback) {
        this.ghost = Objects.requireNonNull(ghost, "ghost");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        PacketDecision ghostDecision = ghost.decide(context, state);
        if (ghostDecision != PacketDecision.PASS) return ghostDecision;
        return fallback.decide(context, state);
    }
}
