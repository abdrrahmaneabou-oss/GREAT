package com.great.app.transport;

import com.great.app.core.GreatEngine;
import com.great.app.core.PacketDecision;
import com.great.app.core.PacketDirection;
import com.great.app.core.PacketEnvelope;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Global OUTBOUND IPv4/UDP throttle for Robot Freeze cycles, including fragmented datagrams. */
public final class GlobalRobotOutboundThrottle {
    public interface OutputSink { void emit(PacketEnvelope packet) throws Exception; }

    public static final int MIN_LIGHT_DELAY_MS = 30;
    public static final int MAX_LIGHT_DELAY_MS = 60;
    public static final int MIN_START_DELAY_MS = 75;
    public static final int MAX_START_DELAY_MS = 175;
    public static final int MIN_DURATION_MS = 200;
    public static final int MAX_DURATION_MS = 350;
    public static final int POST_FREEZE_SKIP_PERCENT = 30;

    private enum Phase { NONE, LIGHT, POST_FREEZE }
    private static final GlobalRobotOutboundThrottle INSTANCE = new GlobalRobotOutboundThrottle(new Random());

    private final Object lock = new Object();
    private final Random rng;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GREAT-Robot-Global-Outbound");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();

    private boolean featureEnabled;
    private OutputSink sink;
    private long transportGeneration;
    private Phase phase = Phase.NONE;
    private long lightDelayNanos;
    private long cycleEndsNanos;
    private long cycleDurationNanos;
    private long startDelayNanos;
    private long previousDelayNanos;

    private GlobalRobotOutboundThrottle(Random rng) { this.rng = Objects.requireNonNull(rng); }
    public static GlobalRobotOutboundThrottle instance() { return INSTANCE; }

    public void setFeatureEnabled(boolean enabled) {
        synchronized (lock) {
            featureEnabled = enabled;
            if (!enabled) clearCycleLocked();
        }
    }
    public boolean featureEnabled() { synchronized (lock) { return featureEnabled; } }
    public boolean lightActive() { synchronized (lock) { return featureEnabled && phase == Phase.LIGHT; } }
    public long seen() { return seen.get(); }
    public long delayed() { return delayed.get(); }

    public boolean active() {
        synchronized (lock) {
            if (!featureEnabled || phase == Phase.NONE) return false;
            if (phase == Phase.POST_FREEZE && System.nanoTime() >= cycleEndsNanos) {
                clearCycleLocked();
                return false;
            }
            return true;
        }
    }

    public void attach(OutputSink output) {
        synchronized (lock) { sink = Objects.requireNonNull(output); transportGeneration++; }
    }
    public void detach(OutputSink output) {
        synchronized (lock) {
            if (sink == output) { sink = null; transportGeneration++; clearCycleLocked(); }
        }
    }

    /** Pick one fixed 30..60 ms delay and keep it for the whole active Robot Freeze. */
    public boolean startLightForFreeze() {
        synchronized (lock) {
            if (!featureEnabled || sink == null) { clearCycleLocked(); return false; }
            phase = Phase.LIGHT;
            lightDelayNanos = TimeUnit.MILLISECONDS.toNanos(randomInclusiveLocked(MIN_LIGHT_DELAY_MS, MAX_LIGHT_DELAY_MS));
            clearPostFreezeFieldsLocked();
            return true;
        }
    }

    /** Finish light throttling, then apply the existing 30% skip + random decaying post throttle. */
    public boolean finishFreezeAndStartPostThrottle() {
        synchronized (lock) { return startPostThrottleLocked(); }
    }

    /** Kept for the detector's old callback; lifecycle ownership now lives in RobotFreezeOrchestrator. */
    public boolean startFromRobotWhiteReturn() { return false; }
    public void cancelActiveCycle() { synchronized (lock) { clearCycleLocked(); } }

    public PacketDecision decide(PacketEnvelope packet) {
        if (packet.direction() != PacketDirection.OUTBOUND || !isIpv4Udp(packet)) return PacketDecision.PASS;
        seen.incrementAndGet();

        final OutputSink output;
        final long generation;
        final long delayNanos;
        synchronized (lock) {
            if (!featureEnabled || sink == null || phase == Phase.NONE) return PacketDecision.PASS;

            // Covers the visual monitor's existing safety timeout: once Robot hold is really OFF,
            // the light phase ends even if white has not returned yet.
            if (phase == Phase.LIGHT && !GreatEngine.instance().freezeCore().holdTriggerActive()) {
                if (!startPostThrottleLocked()) return PacketDecision.PASS;
            }

            if (phase == Phase.LIGHT) {
                delayNanos = lightDelayNanos;
            } else {
                long now = System.nanoTime();
                if (now >= cycleEndsNanos) { clearCycleLocked(); return PacketDecision.PASS; }
                delayNanos = nextPostFreezeDelayLocked(now);
            }
            if (delayNanos <= 0L) return PacketDecision.PASS;
            output = sink;
            generation = transportGeneration;
        }

        byte[] copy = new byte[packet.length()];
        System.arraycopy(packet.data(), 0, copy, 0, copy.length);
        PacketEnvelope held = new PacketEnvelope(copy, copy.length, PacketDirection.OUTBOUND, packet.monotonicNanos());
        delayed.incrementAndGet();
        scheduler.schedule(() -> emitIfCurrent(output, held, generation), delayNanos, TimeUnit.NANOSECONDS);
        return PacketDecision.HOLD;
    }

    private boolean startPostThrottleLocked() {
        if (!featureEnabled || sink == null) { clearCycleLocked(); return false; }
        lightDelayNanos = 0L;
        if (rng.nextInt(100) < POST_FREEZE_SKIP_PERCENT) { clearCycleLocked(); return false; }
        int startDelayMs = randomInclusiveLocked(MIN_START_DELAY_MS, MAX_START_DELAY_MS);
        int durationMs = randomInclusiveLocked(MIN_DURATION_MS, MAX_DURATION_MS);
        long now = System.nanoTime();
        phase = Phase.POST_FREEZE;
        cycleDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        cycleEndsNanos = now + cycleDurationNanos;
        startDelayNanos = TimeUnit.MILLISECONDS.toNanos(startDelayMs);
        previousDelayNanos = startDelayNanos + 1L;
        return true;
    }

    private long nextPostFreezeDelayLocked(long now) {
        long remaining = cycleEndsNanos - now;
        if (remaining <= 0L || cycleDurationNanos <= 0L || startDelayNanos <= 0L) { clearCycleLocked(); return 0L; }
        long ceiling = Math.max(1L, (startDelayNanos * remaining) / cycleDurationNanos);
        long maxAllowed = Math.min(ceiling, previousDelayNanos - 1L);
        if (maxAllowed <= 0L) return 0L;
        long floor = Math.max(1L, maxAllowed - TimeUnit.MILLISECONDS.toNanos(12));
        long span = maxAllowed - floor;
        long chosen = span <= 0L ? maxAllowed : floor + nextLongBoundedLocked(span + 1L);
        previousDelayNanos = chosen;
        return chosen;
    }

    private void emitIfCurrent(OutputSink output, PacketEnvelope packet, long generation) {
        synchronized (lock) { if (sink != output || transportGeneration != generation) return; }
        try { output.emit(packet); } catch (Exception ignored) { }
    }
    private void clearCycleLocked() { phase = Phase.NONE; lightDelayNanos = 0L; clearPostFreezeFieldsLocked(); }
    private void clearPostFreezeFieldsLocked() { cycleEndsNanos = cycleDurationNanos = startDelayNanos = previousDelayNanos = 0L; }
    private int randomInclusiveLocked(int from, int to) { return from == to ? from : from + rng.nextInt(to - from + 1); }
    private long nextLongBoundedLocked(long bound) {
        if (bound <= 1L) return 0L;
        return (rng.nextLong() & Long.MAX_VALUE) % bound;
    }

    private static boolean isIpv4Udp(PacketEnvelope packet) {
        if (packet.length() < 20) return false;
        byte[] data = packet.data();
        int version = (data[0] >>> 4) & 0x0f;
        int ihlBytes = (data[0] & 0x0f) * 4;
        return version == 4 && ihlBytes >= 20 && ihlBytes <= packet.length() && (data[9] & 0xff) == 17;
    }
}
