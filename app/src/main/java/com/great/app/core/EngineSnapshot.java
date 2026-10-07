package com.great.app.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable engine state. UI and networking read the same snapshot. */
public final class EngineSnapshot {
    private final Set<Capability> enabled;
    private final long revision;

    EngineSnapshot(EnumSet<Capability> enabled, long revision) {
        this.enabled = Collections.unmodifiableSet(EnumSet.copyOf(enabled));
        this.revision = revision;
    }

    public boolean enabled(Capability capability) { return enabled.contains(capability); }
    public Set<Capability> enabledCapabilities() { return enabled; }
    public long revision() { return revision; }
}
