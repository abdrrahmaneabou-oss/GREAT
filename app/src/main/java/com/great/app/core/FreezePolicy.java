package com.great.app.core;

import java.util.Objects;

/** Target gate for the Freeze engine. */
public final class FreezePolicy implements PacketPolicy {
    private final PacketSelector targetSelector;
    private final FreezeCore core;

    public FreezePolicy(PacketSelector targetSelector, FreezeCore core) {
        this.targetSelector = Objects.requireNonNull(targetSelector, "targetSelector");
        this.core = Objects.requireNonNull(core, "core");
    }

    @Override public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        // Target selection is deliberately external to the Freeze
        // code. Until GREAT receives an explicit target rule, nothing is matched.
        if (!targetSelector.matches(context)) return PacketDecision.PASS;
        return core.decide(context, state);
    }
}
