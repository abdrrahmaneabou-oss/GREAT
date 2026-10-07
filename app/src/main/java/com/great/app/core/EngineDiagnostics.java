package com.great.app.core;

import java.util.concurrent.atomic.AtomicLong;

/** Lock-free counters only; packet contents and secrets are never recorded. */
public final class EngineDiagnostics {
    private final AtomicLong inbound = new AtomicLong();
    private final AtomicLong outbound = new AtomicLong();
    private final AtomicLong parsed = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong scheduled = new AtomicLong();
    private final AtomicLong schedulerRejected = new AtomicLong();

    public void packetSeen(PacketDirection direction) {
        if (direction == PacketDirection.INBOUND) inbound.incrementAndGet();
        else outbound.incrementAndGet();
    }

    public void parsed(boolean valid) {
        parsed.incrementAndGet();
        if (!valid) malformed.incrementAndGet();
    }

    public void decided(PacketDecision decision) {
        if (decision == PacketDecision.PASS) passed.incrementAndGet();
        else if (decision == PacketDecision.DROP) dropped.incrementAndGet();
    }

    public void scheduled() { scheduled.incrementAndGet(); }
    public void schedulerRejected() { schedulerRejected.incrementAndGet(); }

    public Snapshot snapshot() {
        return new Snapshot(inbound.get(), outbound.get(), parsed.get(), malformed.get(),
                passed.get(), dropped.get(), scheduled.get(), schedulerRejected.get());
    }

    public void reset() {
        inbound.set(0); outbound.set(0); parsed.set(0); malformed.set(0);
        passed.set(0); dropped.set(0); scheduled.set(0); schedulerRejected.set(0);
    }

    public record Snapshot(long inbound, long outbound, long parsed, long malformed,
                           long passed, long dropped, long scheduled, long schedulerRejected) { }
}
