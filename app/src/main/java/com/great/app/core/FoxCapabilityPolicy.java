package com.great.app.core;

import java.util.Objects;

/** Target gate + the surgically recovered FOX capability kernel. */
public final class FoxCapabilityPolicy implements PacketPolicy {
    private final PacketSelector targetSelector;
    private final FoxCapabilityCore core;

    public FoxCapabilityPolicy(PacketSelector targetSelector, FoxCapabilityCore core) {
        this.targetSelector = Objects.requireNonNull(targetSelector, "targetSelector");
        this.core = Objects.requireNonNull(core, "core");
    }

    @Override public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        // Target selection is deliberately external to the transplanted capability
        // code. Until GREAT receives an explicit target rule, nothing is matched.
        if (!targetSelector.matches(context)) return PacketDecision.PASS;
        return core.decide(context, state);
    }
}
