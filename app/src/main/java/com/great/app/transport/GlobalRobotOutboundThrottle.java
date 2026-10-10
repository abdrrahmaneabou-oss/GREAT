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

    /** Fixed periodic post-Freeze profile. It is intentionally not controlled by the UI card. */
    public static final int MIN_START_DELAY_MS = 75;
    public static final int MAX_START_DELAY_MS = 175;
    public static final int MIN_DURATION_MS = 200;
    public static final int MAX_DURATION_MS = 350;

    /** Defaults for visual Robot white-return post-Freeze throttle. */
    public static final int DEFAULT_VISUAL_POST_START_MIN_MS = 75;
    public static final int DEFAULT_VISUAL_POST_START_MAX_MS = 175;
    public static final int DEFAULT_VISUAL_POST_DURATION_MIN_MS = 200;
    public static final int DEFAULT_VISUAL_POST_DURATION_MAX_MS = 350;
    public static final int MIN_CONFIGURABLE_POST_MS = 1;
    public static final int MAX_CONFIGURABLE_POST_MS = 5000;

    public static final int POST_FREEZE_SKIP_PERCENT = 30;

    private enum Phase { NONE, LIGHT, POST_FREEZE }
    private enum LightOwner { NONE, VISUAL, PERIODIC }
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

    /** Overall Robot throttle runtime gate. Enabled while the VPN transport is active. */
    private boolean featureEnabled;
    /** User switch in the card: visual white-return post throttle only. */
    private boolean visualPostEnabled = true;
    private int visualPostStartMinMs = DEFAULT_VISUAL_POST_START_MIN_MS;
    private int visualPostStartMaxMs = DEFAULT_VISUAL_POST_START_MAX_MS;
    private int visualPostDurationMinMs = DEFAULT_VISUAL_POST_DURATION_MIN_MS;
    private int visualPostDurationMaxMs = DEFAULT_VISUAL_POST_DURATION_MAX_MS;

    private OutputSink sink;
    private long transportGeneration;
    private Phase phase = Phase.NONE;
    private LightOwner lightOwner = LightOwner.NONE;
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

    public void setVisualPostEnabled(boolean enabled) {
        synchronized (lock) { visualPostEnabled = enabled; }
    }

    public boolean visualPostEnabled() {
        synchronized (lock) { return visualPostEnabled; }
    }

    public void setVisualPostRange(int startMinMs, int startMaxMs,
                                   int durationMinMs, int durationMaxMs) {
        if (!validVisualPostRange(startMinMs, startMaxMs, durationMinMs, durationMaxMs)) {
            throw new IllegalArgumentException("Visual post throttle ranges must be ordered and stay inside 1..5000 ms");
        }
        synchronized (lock) {
            visualPostStartMinMs = startMinMs;
            visualPostStartMaxMs = startMaxMs;
            visualPostDurationMinMs = durationMinMs;
            visualPostDurationMaxMs = durationMaxMs;
        }
    }

    public static boolean validVisualPostRange(int startMinMs, int startMaxMs,
                                               int durationMinMs, int durationMaxMs) {
        return startMinMs >= MIN_CONFIGURABLE_POST_MS
                && startMinMs <= startMaxMs
                && startMaxMs <= MAX_CONFIGURABLE_POST_MS
                && durationMinMs >= MIN_CONFIGURABLE_POST_MS
                && durationMinMs <= durationMaxMs
                && durationMaxMs <= MAX_CONFIGURABLE_POST_MS;
    }

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

    /** Pick one fixed 30..60 ms delay and keep it for the whole active visual Robot Freeze. */
    public boolean startLightForVisualFreeze() {
        synchronized (lock) { return startLightLocked(LightOwner.VISUAL); }
    }

    /** Pick one fixed 30..60 ms delay and keep it for the whole active periodic Robot Freeze. */
    public boolean startLightForPeriodicFreeze() {
        synchronized (lock) { return startLightLocked(LightOwner.PERIODIC); }
    }

    /** Compatibility entry point; visual is the historical caller. */
    public boolean startLightForFreeze() { return startLightForVisualFreeze(); }

    private boolean startLightLocked(LightOwner owner) {
        if (!featureEnabled || sink == null) { clearCycleLocked(); return false; }
        phase = Phase.LIGHT;
        lightOwner = owner;
        lightDelayNanos = TimeUnit.MILLISECONDS.toNanos(randomInclusiveLocked(MIN_LIGHT_DELAY_MS, MAX_LIGHT_DELAY_MS));
        clearPostFreezeFieldsLocked();
        return true;
    }

    /** Visual white-return profile: user-configurable ranges, still with the existing 30% skip. */
    public boolean finishVisualFreezeAndStartPostThrottle() {
        synchronized (lock) {
            return startPostThrottleLocked(true,
                    visualPostStartMinMs, visualPostStartMaxMs,
                    visualPostDurationMinMs, visualPostDurationMaxMs);
        }
    }

    /** Periodic profile remains fixed and is not affected by the visual white-return card. */
    public boolean finishPeriodicFreezeAndStartPostThrottle() {
        synchronized (lock) {
            return startPostThrottleLocked(false,
                    MIN_START_DELAY_MS, MAX_START_DELAY_MS,
                    MIN_DURATION_MS, MAX_DURATION_MS);
        }
    }

    /** Compatibility entry point for older callers; behaves as the visual white-return path. */
    public boolean finishFreezeAndStartPostThrottle() {
        return finishVisualFreezeAndStartPostThrottle();
    }

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

            // Covers safety timeout: when Robot hold ends before an explicit finish callback,
            // transition using the correct owner profile instead of mixing visual and periodic settings.
            if (phase == Phase.LIGHT && !GreatEngine.instance().freezeCore().holdTriggerActive()) {
                boolean visual = lightOwner == LightOwner.VISUAL;
                boolean started = visual
                        ? startPostThrottleLocked(true,
                                visualPostStartMinMs, visualPostStartMaxMs,
                                visualPostDurationMinMs, visualPostDurationMaxMs)
                        : startPostThrottleLocked(false,
                                MIN_START_DELAY_MS, MAX_START_DELAY_MS,
                                MIN_DURATION_MS, MAX_DURATION_MS);
                if (!started) return PacketDecision.PASS;
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

    private boolean startPostThrottleLocked(boolean visual,
                                            int startMinMs, int startMaxMs,
                                            int durationMinMs, int durationMaxMs) {
        if (!featureEnabled || sink == null) { clearCycleLocked(); return false; }
        lightDelayNanos = 0L;
        lightOwner = LightOwner.NONE;
        if (visual && !visualPostEnabled) { clearCycleLocked(); return false; }
        if (rng.nextInt(100) < POST_FREEZE_SKIP_PERCENT) { clearCycleLocked(); return false; }
        int startDelayMs = randomInclusiveLocked(startMinMs, startMaxMs);
        int durationMs = randomInclusiveLocked(durationMinMs, durationMaxMs);
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

    private void clearCycleLocked() {
        phase = Phase.NONE;
        lightOwner = LightOwner.NONE;
        lightDelayNanos = 0L;
        clearPostFreezeFieldsLocked();
    }
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
