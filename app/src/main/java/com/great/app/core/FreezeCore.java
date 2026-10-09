package com.great.app.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final int MIN_RELEASE_BURST = 1;
    private static final int MAX_RELEASE_BURST = 4;
    private static final int MIN_RELEASE_DELAY_MS = 5;
    private static final int MAX_RELEASE_DELAY_MS = 45;
    private static final int MIN_RELEASE_DROP_PERCENT = 12;
    private static final int MAX_RELEASE_DROP_PERCENT = 23;

    private static final int MIN_RAMP_DURATION_MS = 70;
    private static final int MAX_RAMP_DURATION_MS = 130;
    private static final int MIN_RAMP_STAGE1_HOLD_PERCENT = 20;
    private static final int MAX_RAMP_STAGE1_HOLD_PERCENT = 40;
    private static final int MIN_RAMP_STAGE2_HOLD_PERCENT = 55;
    private static final int MAX_RAMP_STAGE2_HOLD_PERCENT = 75;

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
    // The visual monitor is a second independent source and never changes the manual button state.
    private boolean visualMonitorHold;
    private boolean closed;
    private long generation;

    // A new ramp is created only when effective Freeze transitions OFF -> ON. It does not alter
    // packet eligibility rules; it only decides whether an otherwise eligible packet is held during
    // the first 70..130 ms. After the window expires, normal full Freeze resumes automatically.
    private long rampStartedNanos;
    private long rampDurationNanos;
    private int rampStage1HoldPercent;
    private int rampStage2HoldPercent;

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
            if (!rampAllowsHoldLocked(System.nanoTime())) return PacketDecision.PASS;
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
                startRampLocked();
            } else if (wasEffective && !nowEffective) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    /**
     * Visual-monitor source only. FIRED sets this true; returning to the ARMED/green state sets it
     * false. It never changes CapabilityController, so manual Freeze keeps its own state and timer.
     */
    public void setHoldTrigger(boolean active) {
        synchronized (lock) {
            if (closed || visualMonitorHold == active) return;
            boolean wasEffective = effectiveLocked();
            visualMonitorHold = active;
            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
                startRampLocked();
            } else if (wasEffective && !nowEffective) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    public boolean holdTriggerActive() {
        synchronized (lock) { return visualMonitorHold; }
    }

    public boolean effectiveActive() {
        synchronized (lock) { return effectiveLocked(); }
    }

    private boolean effectiveLocked() {
        return manualEnabled || visualMonitorHold;
    }

    private void startRampLocked() {
        int durationMs = MIN_RAMP_DURATION_MS
                + rng.nextInt(MAX_RAMP_DURATION_MS - MIN_RAMP_DURATION_MS + 1);
        rampStartedNanos = System.nanoTime();
        rampDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        rampStage1HoldPercent = MIN_RAMP_STAGE1_HOLD_PERCENT
                + rng.nextInt(MAX_RAMP_STAGE1_HOLD_PERCENT - MIN_RAMP_STAGE1_HOLD_PERCENT + 1);
        rampStage2HoldPercent = MIN_RAMP_STAGE2_HOLD_PERCENT
                + rng.nextInt(MAX_RAMP_STAGE2_HOLD_PERCENT - MIN_RAMP_STAGE2_HOLD_PERCENT + 1);
    }

    private void clearRampLocked() {
        rampStartedNanos = 0L;
        rampDurationNanos = 0L;
        rampStage1HoldPercent = 0;
        rampStage2HoldPercent = 0;
    }

    private boolean rampAllowsHoldLocked(long nowNanos) {
        if (rampDurationNanos <= 0L || rampStartedNanos <= 0L) return true;
        long elapsed = nowNanos - rampStartedNanos;
        if (elapsed < 0L || elapsed >= rampDurationNanos) {
            clearRampLocked();
            return true;
        }
        long firstHalf = rampDurationNanos / 2L;
        int holdPercent = elapsed < firstHalf ? rampStage1HoldPercent : rampStage2HoldPercent;
        return rng.nextInt(100) < holdPercent;
    }

    private void releaseBufferedLocked() {
        if (buffer.isEmpty()) return;
        ArrayList<PacketEnvelope> all = new ArrayList<>(buffer);
        buffer.clear();
        long session = generation;
        releases.execute(() -> release(all, session));
    }

    private void release(ArrayList<PacketEnvelope> all, long session) {
        if (all.isEmpty()) return;

        int dropPercent = MIN_RELEASE_DROP_PERCENT
                + rng.nextInt(MAX_RELEASE_DROP_PERCENT - MIN_RELEASE_DROP_PERCENT + 1);
        int dropCount = Math.round(all.size() * (dropPercent / 100f));
        dropCount = Math.max(0, Math.min(dropCount, all.size()));
        if (dropCount > 0) {
            int maxStart = all.size() - dropCount;
            int dropStart = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
            all.subList(dropStart, dropStart + dropCount).clear();
        }

        if (all.size() > 1) Collections.shuffle(all, rng);

        int index = 0;
        while (index < all.size()) {
            int burstSize = MIN_RELEASE_BURST + rng.nextInt(MAX_RELEASE_BURST - MIN_RELEASE_BURST + 1);
            int burstEnd = Math.min(all.size(), index + burstSize);

            while (index < burstEnd) {
                OutputSink output;
                synchronized (lock) {
                    if (closed || session != generation || sink == null) return;
                    output = sink;
                }
                try {
                    output.emit(all.get(index));
                    diagnostics.released();
                } catch (Exception e) {
                    return;
                }
                index++;
            }

            if (index < all.size()) {
                int delayMs = MIN_RELEASE_DELAY_MS
                        + rng.nextInt(MAX_RELEASE_DELAY_MS - MIN_RELEASE_DELAY_MS + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
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
            visualMonitorHold = false;
            clearRampLocked();
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
