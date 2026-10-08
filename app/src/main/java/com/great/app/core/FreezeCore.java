package com.great.app.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Incoming IPv4/UDP hold and release, using the verified FOX Freeze rules. */
public final class FreezeCore implements AutoCloseable {
    public interface OutputSink { void emit(PacketEnvelope packet) throws Exception; }
    public static final int CAPACITY = 10_000;
    public static final int DEFAULT_DURATION_SECONDS = 5;
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int MAX_DURATION_SECONDS = 10;

    private final CapabilityController controller;
    private final EngineDiagnostics diagnostics;
    private final Random rng;
    private final Object lock = new Object();
    private final ArrayDeque<PacketEnvelope> buffer = new ArrayDeque<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GREAT-Freeze-Timer"));
    private final ExecutorService releases = Executors.newSingleThreadExecutor(r -> daemon(r, "GREAT-Freeze-Release"));
    private OutputSink sink;
    private ScheduledFuture<?> timeout;
    private int durationSeconds = DEFAULT_DURATION_SECONDS;

    // Manual Freeze is controlled only by CapabilityController / the visible Freeze button.
    private boolean manualEnabled;
    // Circle hold is a second independent source controlled only by the Shizuku touch trigger.
    private boolean circleHold;
    private boolean closed;
    private long generation;

    public FreezeCore(CapabilityController controller, EngineDiagnostics diagnostics) {
        this(controller, diagnostics, new Random());
    }

    FreezeCore(CapabilityController controller, EngineDiagnostics diagnostics, Random rng) {
        this.controller = Objects.requireNonNull(controller);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.rng = Objects.requireNonNull(rng);
    }

    public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        PacketMetadata m = context.metadata();
        PacketEnvelope packet = context.packet();
        if (packet.direction() != PacketDirection.INBOUND || !m.valid() || m.ipVersion() != 4
                || m.protocol() != PacketParser.PROTO_UDP || m.fragmented()) return PacketDecision.PASS;
        int offset = m.transportOffset();
        if (offset < 20 || offset + 8 > packet.length()) return PacketDecision.PASS;
        int udpLength = u16(packet.data(), offset + 4);
        int ipLength = u16(packet.data(), 2);
        if (udpLength < 8 || offset + udpLength > ipLength) return PacketDecision.PASS;
        int remotePort = m.sourcePort();
        if (remotePort >= 7000 && remotePort <= 10000) return PacketDecision.PASS;
        synchronized (lock) {
            if (closed || !effectiveLocked() || !shouldHold(udpLength - 8)) return PacketDecision.PASS;
            if (buffer.size() == CAPACITY) {
                buffer.removeFirst();
                diagnostics.schedulerRejected();
            }
            byte[] copy = new byte[packet.length()];
            System.arraycopy(packet.data(), 0, copy, 0, copy.length);
            buffer.addLast(new PacketEnvelope(copy, copy.length, packet.direction(), packet.monotonicNanos()));
            diagnostics.scheduled();
            return PacketDecision.HOLD;
        }
    }

    boolean shouldHold(int payloadLength) {
        int lower = 20 + rng.nextInt(10);
        int upper = 450 + rng.nextInt(50);
        if (lower >= upper) lower = upper - 1;
        return payloadLength > lower && payloadLength < upper;
    }

    public void onCapabilityChanged(Capability capability, boolean value) {
        if (capability != Capability.FREEZE) return;
        synchronized (lock) {
            if (closed) return;
            boolean wasEffective = effectiveLocked();
            manualEnabled = value;

            if (timeout != null) timeout.cancel(false);
            timeout = null;
            if (value) {
                long activation = controller.snapshot().revision();
                timeout = timers.schedule(() -> controller.setIfRevision(
                        Capability.FREEZE, false, activation), durationSeconds, TimeUnit.SECONDS);
            }

            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
            } else if (wasEffective && !nowEffective) {
                releaseBufferedLocked();
            }
        }
    }

    /**
     * Circle source only. It never changes CapabilityController, so the manual Freeze button keeps
     * its own state and timer. A circle DOWN sets this true; lifting the tracked finger sets it false.
     */
    public void setHoldTrigger(boolean active) {
        synchronized (lock) {
            if (closed || circleHold == active) return;
            boolean wasEffective = effectiveLocked();
            circleHold = active;
            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
            } else if (wasEffective && !nowEffective) {
                releaseBufferedLocked();
            }
        }
    }

    public boolean holdTriggerActive() {
        synchronized (lock) { return circleHold; }
    }

    public boolean effectiveActive() {
        synchronized (lock) { return effectiveLocked(); }
    }

    private boolean effectiveLocked() {
        return manualEnabled || circleHold;
    }

    private void releaseBufferedLocked() {
        if (buffer.isEmpty()) return;
        ArrayList<PacketEnvelope> all = new ArrayList<>(buffer);
        buffer.clear();
        long session = generation;
        releases.execute(() -> release(all, session));
    }

    private void release(ArrayList<PacketEnvelope> all, long session) {
        for (int i = 0; i < all.size(); i++) {
            OutputSink output;
            synchronized (lock) {
                if (closed || session != generation || sink == null) return;
                output = sink;
            }
            try { output.emit(all.get(i)); diagnostics.released(); }
            catch (Exception e) { return; }
            if (i > 0 && (i & 1) == 0) {
                try { Thread.sleep(1 + rng.nextInt(3)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    public void attach(OutputSink output) { synchronized (lock) { sink = Objects.requireNonNull(output); } }

    public void detach(OutputSink output) {
        synchronized (lock) { if (sink == output) { sink = null; generation++; } }
    }

    public int freezeQueueSize() { synchronized (lock) { return buffer.size(); } }
    public int freezeDurationSeconds() { synchronized (lock) { return durationSeconds; } }

    public void setFreezeDurationSeconds(int seconds) {
        if (seconds < MIN_DURATION_SECONDS || seconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("duration must be 1..10 seconds");
        }
        synchronized (lock) { durationSeconds = seconds; }
    }

    public void reset() {
        synchronized (lock) {
            manualEnabled = false;
            circleHold = false;
            generation++;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            buffer.clear();
        }
    }

    @Override public void close() {
        synchronized (lock) { reset(); closed = true; sink = null; }
        timers.shutdownNow();
        releases.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static int u16(byte[] d, int i) {
        return ((d[i] & 255) << 8) | (d[i + 1] & 255);
    }
}
