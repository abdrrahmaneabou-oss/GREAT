package com.great.app.core;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Single source of truth for capability state. Build 2 will add capability-specific state machines behind this boundary. */
public final class CapabilityController {
    private final AtomicReference<EnumSet<Capability>> enabled = new AtomicReference<>(EnumSet.noneOf(Capability.class));
    private final AtomicLong revision = new AtomicLong();

    public EngineSnapshot snapshot() {
        return new EngineSnapshot(enabled.get(), revision.get());
    }

    public void set(Capability capability, boolean value) {
        while (true) {
            EnumSet<Capability> before = enabled.get();
            EnumSet<Capability> after = EnumSet.copyOf(before);
            if (value) after.add(capability); else after.remove(capability);
            if (after.equals(before)) return;
            if (enabled.compareAndSet(before, after)) {
                revision.incrementAndGet();
                return;
            }
        }
    }

    public void reset() {
        enabled.set(EnumSet.noneOf(Capability.class));
        revision.incrementAndGet();
    }
}
