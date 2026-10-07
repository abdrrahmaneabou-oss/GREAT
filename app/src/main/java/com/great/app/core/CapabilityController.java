package com.great.app.core;

import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Single source of truth for capability state. */
public final class CapabilityController {
    public interface Listener {
        void onChanged(Capability capability, boolean enabled);
    }

    private static final Listener NOOP = (capability, enabled) -> { };

    private final AtomicReference<EnumSet<Capability>> enabled =
            new AtomicReference<>(EnumSet.noneOf(Capability.class));
    private final AtomicLong revision = new AtomicLong();
    private volatile Listener listener = NOOP;

    public EngineSnapshot snapshot() {
        return new EngineSnapshot(enabled.get(), revision.get());
    }

    public void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void set(Capability capability, boolean value) {
        Objects.requireNonNull(capability, "capability");
        while (true) {
            EnumSet<Capability> before = enabled.get();
            EnumSet<Capability> after = EnumSet.copyOf(before);
            if (value) after.add(capability); else after.remove(capability);
            if (after.equals(before)) return;
            if (enabled.compareAndSet(before, after)) {
                revision.incrementAndGet();
                listener.onChanged(capability, value);
                return;
            }
        }
    }

    public boolean toggle(Capability capability) {
        while (true) {
            EnumSet<Capability> before = enabled.get();
            boolean next = !before.contains(capability);
            EnumSet<Capability> after = EnumSet.copyOf(before);
            if (next) after.add(capability); else after.remove(capability);
            if (enabled.compareAndSet(before, after)) {
                revision.incrementAndGet();
                listener.onChanged(capability, next);
                return next;
            }
        }
    }

    /** Lifecycle reset intentionally does not trigger release/replay callbacks. */
    public void reset() {
        enabled.set(EnumSet.noneOf(Capability.class));
        revision.incrementAndGet();
    }
}
