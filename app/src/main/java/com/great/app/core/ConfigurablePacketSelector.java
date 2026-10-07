package com.great.app.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-selectable target boundary. Defaults to matching nothing. */
public final class ConfigurablePacketSelector implements PacketSelector {
    private final AtomicReference<PacketSelector> delegate = new AtomicReference<>(PacketSelector.none());

    @Override public boolean matches(PacketContext context) {
        return delegate.get().matches(context);
    }

    public void set(PacketSelector selector) {
        delegate.set(Objects.requireNonNull(selector, "selector"));
    }

    public void clear() { delegate.set(PacketSelector.none()); }
}
