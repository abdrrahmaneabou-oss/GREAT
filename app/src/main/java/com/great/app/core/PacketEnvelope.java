package com.great.app.core;

import java.util.Objects;

/** Immutable metadata around a packet. The payload is never copied by the core unless a policy needs ownership. */
public final class PacketEnvelope {
    private final byte[] data;
    private final int length;
    private final PacketDirection direction;
    private final long monotonicNanos;

    public PacketEnvelope(byte[] data, int length, PacketDirection direction, long monotonicNanos) {
        this.data = Objects.requireNonNull(data, "data");
        this.direction = Objects.requireNonNull(direction, "direction");
        if (length < 0 || length > data.length) throw new IllegalArgumentException("invalid length");
        this.length = length;
        this.monotonicNanos = monotonicNanos;
    }

    public byte[] data() { return data; }
    public int length() { return length; }
    public PacketDirection direction() { return direction; }
    public long monotonicNanos() { return monotonicNanos; }
}
