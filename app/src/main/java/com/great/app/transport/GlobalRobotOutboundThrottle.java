package com.great.app.transport;

import com.great.app.core.PacketDecision;
import com.great.app.core.PacketDirection;
import com.great.app.core.PacketEnvelope;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global OUTBOUND IPv4/UDP throttle driven by Robot Freeze cycles.
 *
 * This path deliberately runs before target-app ownership and Freeze filtering. It therefore
 * applies to every OUTBOUND IPv4 packet whose IP protocol byte is UDP, including fragmented
 * datagrams. It never drops packets.
 *
 * Robot uses two phases:
 *  - LIGHT while Robot Freeze is actually active: one random fixed 30..60 ms delay per cycle.
 *  - POST_FREEZE after Robot Freeze ends: 30% skipped; otherwise a random 75..175 ms starting
 *    delay decays irregularly to zero across a random 200..350 ms window.
 */
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

    private static final GlobalRobotOutboundThrottle INSTANCE =
            new GlobalRobotOutboundThrottle(new Random());

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
    private long cycleStartedNanos;
    private long cycleEndsNanos;
    private long cycleDurationNanos;
    private long startDelayNanos;
    private long previousDelayNanos;

    private GlobalRobotOutboundThrottle(Random rng) {
        this.rng = Objects.requireNonNull(rng);
    }

    public static GlobalRobotOutboundThrottle instance() { return INSTANCE; }

    public void setFeatureEnabled(boolean enabled) {
        synchronized (lock) {
            featureEnabled = enabled;
            if (!enabled) clearCycleLocked();
        }
    }

    public boolean featureEnabled() {
        synchronized (lock) { return featureEnabled; }
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

    public boolean lightActive() {
        synchronized (lock) { return featureEnabled && phase == Phase.LIGHT; }
    }

    public long seen() { return seen.get(); }
    public long delayed() { return delayed.get(); }

    public void attach(OutputSink output) {
        synchronized (lock) {
            sink = Objects.requireNonNull(output);
            transportGeneration++;
        }
    }

    public void detach(OutputSink output) {
        synchronized (lock) {
            if (sink == output) {
                sink = null;
                transportGeneration++;
                clearCycleLocked();
            }
        }
    }

    /** Starts the light global throttle for an actual Robot Freeze cycle. */
    public boolean startLightForFreeze() {
        synchronized (lock) {
            if (!featureEnabled || sink == null) {
                clearCycleLocked();
                return false;
            }
            phase = Phase.LIGHT;
            lightDelayNanos = TimeUnit.MILLISECONDS.toNanos(
                    randomInclusiveLocked(MIN_LIGHT_DELAY_MS, MAX_LIGHT_DELAY_MS));
            clearPostFreezeFieldsLocked();
            return true;
        }
    }

    /**
     * Ends the light phase and starts the normal post-Freeze decay. 30% of these post-Freeze
     * throttle cycles are intentionally skipped.
     */
    public boolean finishFreezeAndStartPostThrottle() {
        synchronized (lock) {
            if (!featureEnabled || sink == null) {
                clearCycleLocked();
                return false;
            }
            lightDelayNanos = 0L;
            if (rng.nextInt(100) < POST_FREEZE_SKIP_PERCENT) {
                clearCycleLocked();
                return false;
            }

            int startDelayMs = randomInclusiveLocked(MIN_START_DELAY_MS, MAX_START_DELAY_MS);
            int durationMs = randomInclusiveLocked(MIN_DURATION_MS, MAX_DURATION_MS);
            long now = System.nanoTime();
            phase = Phase.POST_FREEZE;
            cycleStartedNanos = now;
            cycleDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
            cycleEndsNanos = now + cycleDurationNanos;
            startDelayNanos = TimeUnit.MILLISECONDS.toNanos(startDelayMs);
            previousDelayNanos = startDelayNanos + 1L;
            return true;
        }
    }

    /** Cancels Robot throttle state without changing the user's feature setting. */
    public void cancelActiveCycle() {
        synchronized (lock) { clearCycleLocked(); }
    }

    /** Called before GREAT's target-app Freeze policy. */
    public PacketDecision decide(PacketEnvelope packet) {
        if (packet.direction() != PacketDirection.OUTBOUND || !isIpv4Udp(packet)) {
            return PacketDecision.PASS;
        }
        seen.incrementAndGet();

        final OutputSink output;
        final long generation;
        final long delayNanos;
        synchronized (lock) {
            if (!featureEnabled || sink == null || phase == Phase.NONE) return PacketDecision.PASS;

            if (phase == Phase.LIGHT) {
                delayNanos = lightDelayNanos;
            } else {
                long now = System.nanoTime();
                if (now >= cycleEndsNanos) {
                    clearCycleLocked();
                    return PacketDecision.PASS;
                }
                delayNanos = nextPostFreezeDelayLocked(now);
            }

            if (delayNanos <= 0L) return PacketDecision.PASS;
            output = sink;
            generation = transportGeneration;
        }

        byte[] copy = new byte[packet.length()];
        System.arraycopy(packet.data(), 0, copy, 0, copy.length);
        PacketEnvelope held = new PacketEnvelope(copy, copy.length,
                PacketDirection.OUTBOUND, packet.monotonicNanos());
        delayed.incrementAndGet();
        scheduler.schedule(() -> emitIfCurrent(output, held, generation),
                delayNanos, TimeUnit.NANOSECONDS);
        return PacketDecision.HOLD;
    }

    private long nextPostFreezeDelayLocked(long now) {
        long remaining = cycleEndsNanos - now;
        if (remaining <= 0L || cycleDurationNanos <= 0L || startDelayNanos <= 0L) {
            clearCycleLocked();
            return 0L;
        }

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
        synchronized (lock) {
            if (sink != output || transportGeneration != generation) return;
        }
        try { output.emit(packet); } catch (Exception ignored) { }
    }

    private void clearCycleLocked() {
        phase = Phase.NONE;
        lightDelayNanos = 0L;
        clearPostFreezeFieldsLocked();
    }

    private void clearPostFreezeFieldsLocked() {
        cycleStartedNanos = 0L;
        cycleEndsNanos = 0L;
        cycleDurationNanos = 0L;
        startDelayNanos = 0L;
        previousDelayNanos = 0L;
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }

    private long nextLongBoundedLocked(long bound) {
        if (bound <= 1L) return 0L;
        long value = rng.nextLong() & Long.MAX_VALUE;
        return value % bound;
    }

    private static boolean isIpv4Udp(PacketEnvelope packet) {
        if (packet.length() < 20) return false;
        byte[] data = packet.data();
        int version = (data[0] >>> 4) & 0x0f;
        int ihlBytes = (data[0] & 0x0f) * 4;
        if (version != 4 || ihlBytes < 20 || ihlBytes > packet.length()) return false;
        return (data[9] & 0xff) == 17;
    }
}
