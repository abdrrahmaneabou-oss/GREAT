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

/** Global OUTBOUND IPv4/UDP throttle for Robot Freeze cycles, including fragmented datagrams. */
public final class GlobalRobotOutboundThrottle {
    public interface OutputSink { void emit(PacketEnvelope packet) throws Exception; }

    public static final int DEFAULT_LIGHT_START_MIN_MS = 30;
    public static final int DEFAULT_LIGHT_START_MAX_MS = 60;
    public static final int DEFAULT_LIGHT_DURATION_MIN_MS = 300;
    public static final int DEFAULT_LIGHT_DURATION_MAX_MS = 500;
    public static final int MIN_CONFIGURABLE_LIGHT_MS = 1;
    public static final int MAX_CONFIGURABLE_LIGHT_MS = 500;
    public static final int MIN_LIGHT_DECAY_PERCENT = 15;
    public static final int MAX_LIGHT_DECAY_PERCENT = 30;

    /** Fixed periodic post-Freeze profile. It is intentionally not controlled by the UI card. */
    public static final int MIN_START_DELAY_MS = 75;
    public static final int MAX_START_DELAY_MS = 175;
    public static final int MIN_DURATION_MS = 200;
    public static final int MAX_DURATION_MS = 350;

    public static final int DEFAULT_VISUAL_POST_START_MIN_MS = 75;
    public static final int DEFAULT_VISUAL_POST_START_MAX_MS = 175;
    public static final int DEFAULT_VISUAL_POST_DURATION_MIN_MS = 200;
    public static final int DEFAULT_VISUAL_POST_DURATION_MAX_MS = 350;
    public static final int MIN_CONFIGURABLE_POST_MS = 1;
    public static final int MAX_CONFIGURABLE_POST_MS = 5000;
    public static final int POST_FREEZE_SKIP_PERCENT = 30;

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

    private boolean featureEnabled;

    private boolean lightEnabled = true;
    private boolean lightStopOnWhiteReturn = true;
    private int lightStartMinMs = DEFAULT_LIGHT_START_MIN_MS;
    private int lightStartMaxMs = DEFAULT_LIGHT_START_MAX_MS;
    private int lightDurationMinMs = DEFAULT_LIGHT_DURATION_MIN_MS;
    private int lightDurationMaxMs = DEFAULT_LIGHT_DURATION_MAX_MS;

    private boolean visualPostEnabled = true;
    private int visualPostStartMinMs = DEFAULT_VISUAL_POST_START_MIN_MS;
    private int visualPostStartMaxMs = DEFAULT_VISUAL_POST_START_MAX_MS;
    private int visualPostDurationMinMs = DEFAULT_VISUAL_POST_DURATION_MIN_MS;
    private int visualPostDurationMaxMs = DEFAULT_VISUAL_POST_DURATION_MAX_MS;

    private OutputSink sink;
    private long transportGeneration;

    private boolean lightActive;
    private LightOwner lightOwner = LightOwner.NONE;
    private boolean lightWhiteLinkedForCycle;
    private long lightEndsNanos;
    private long lightStartedNanos;
    private long lightDurationNanos;
    private long lightStartDelayNanos;
    private long lightPreviousDelayNanos;
    private boolean lightFirstDecision;

    private boolean postActive;
    private boolean postVisualOwner;
    private long postEndsNanos;
    private long postDurationNanos;
    private long postStartDelayNanos;
    private long postPreviousDelayNanos;

    private GlobalRobotOutboundThrottle(Random rng) { this.rng = Objects.requireNonNull(rng); }
    static GlobalRobotOutboundThrottle forTests(Random rng) {
        return new GlobalRobotOutboundThrottle(rng);
    }
    public static GlobalRobotOutboundThrottle instance() { return INSTANCE; }

    public void setFeatureEnabled(boolean enabled) {
        synchronized (lock) {
            featureEnabled = enabled;
            if (!enabled) clearAllLocked();
        }
    }

    public boolean featureEnabled() { synchronized (lock) { return featureEnabled; } }
    public long seen() { return seen.get(); }
    public long delayed() { return delayed.get(); }

    public boolean lightActive() {
        synchronized (lock) {
            expireTimedPhasesLocked(System.nanoTime());
            return featureEnabled && lightActive;
        }
    }

    public void setLightEnabled(boolean enabled) {
        synchronized (lock) {
            lightEnabled = enabled;
            if (!enabled) clearLightLocked();
        }
    }

    public boolean lightEnabled() { synchronized (lock) { return lightEnabled; } }

    /** Visual Robot only: when enabled, white return ends light throttle and duration is ignored. */
    public void setLightStopOnWhiteReturn(boolean enabled) {
        synchronized (lock) {
            if (lightStopOnWhiteReturn == enabled) return;
            lightStopOnWhiteReturn = enabled;
            if (lightActive && lightOwner == LightOwner.VISUAL) {
                // The switch is live. Keep this cycle's selected duration and
                // its original start instant; do not create an extra full window.
                lightWhiteLinkedForCycle = enabled;
                if (enabled) {
                    lightEndsNanos = 0L;
                } else {
                    lightEndsNanos = lightStartedNanos + lightDurationNanos;
                    expireTimedPhasesLocked(System.nanoTime());
                }
            }
        }
    }

    public boolean lightStopOnWhiteReturn() { synchronized (lock) { return lightStopOnWhiteReturn; } }

    public void setLightRange(int startMinMs, int startMaxMs, int durationMinMs, int durationMaxMs) {
        if (!validLightRange(startMinMs, startMaxMs, durationMinMs, durationMaxMs)) {
            throw new IllegalArgumentException("Light throttle ranges must be ordered and stay inside 1..500 ms");
        }
        synchronized (lock) {
            lightStartMinMs = startMinMs;
            lightStartMaxMs = startMaxMs;
            lightDurationMinMs = durationMinMs;
            lightDurationMaxMs = durationMaxMs;
        }
    }

    public static boolean validLightRange(int startMinMs, int startMaxMs,
                                          int durationMinMs, int durationMaxMs) {
        return startMinMs >= MIN_CONFIGURABLE_LIGHT_MS
                && startMinMs <= startMaxMs
                && startMaxMs <= MAX_CONFIGURABLE_LIGHT_MS
                && durationMinMs >= MIN_CONFIGURABLE_LIGHT_MS
                && durationMinMs <= durationMaxMs
                && durationMaxMs <= MAX_CONFIGURABLE_LIGHT_MS;
    }

    public void setVisualPostEnabled(boolean enabled) {
        synchronized (lock) {
            visualPostEnabled = enabled;
            if (!enabled && postVisualOwner) clearPostLocked();
        }
    }

    public boolean visualPostEnabled() { synchronized (lock) { return visualPostEnabled; } }

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
            expireTimedPhasesLocked(System.nanoTime());
            return featureEnabled && (lightActive || postActive);
        }
    }

    public void attach(OutputSink output) {
        synchronized (lock) { sink = Objects.requireNonNull(output); transportGeneration++; }
    }

    public void detach(OutputSink output) {
        synchronized (lock) {
            if (sink == output) {
                sink = null;
                transportGeneration++;
                clearAllLocked();
            }
        }
    }

    public boolean startLightForVisualFreeze() {
        synchronized (lock) { return startLightLocked(LightOwner.VISUAL); }
    }

    public boolean startLightForPeriodicFreeze() {
        synchronized (lock) { return startLightLocked(LightOwner.PERIODIC); }
    }

    public boolean startLightForFreeze() { return startLightForVisualFreeze(); }

    private boolean startLightLocked(LightOwner owner) {
        // A new Robot Freeze supersedes the previous cycle's post phase.
        clearPostLocked();
        clearLightLocked();
        if (!featureEnabled || sink == null || !lightEnabled) return false;

        int chosenStartMs = randomInclusiveLocked(lightStartMinMs, lightStartMaxMs);
        int chosenDurationMs = randomInclusiveLocked(lightDurationMinMs, lightDurationMaxMs);
        long now = System.nanoTime();

        lightActive = true;
        lightStartedNanos = now;
        lightOwner = owner;
        lightWhiteLinkedForCycle = owner == LightOwner.VISUAL && lightStopOnWhiteReturn;
        lightStartDelayNanos = TimeUnit.MILLISECONDS.toNanos(chosenStartMs);
        lightPreviousDelayNanos = lightStartDelayNanos;
        lightFirstDecision = true;

        // Always retain the sampled duration so changing the link switch while
        // the visual cycle runs can apply immediately without random re-sampling.
        lightDurationNanos = TimeUnit.MILLISECONDS.toNanos(chosenDurationMs);
        lightEndsNanos = lightWhiteLinkedForCycle ? 0L : now + lightDurationNanos;
        return true;
    }

    /** White return ends light throttle only when the current visual cycle opted into white linking. */
    public boolean finishVisualFreezeAndStartPostThrottle() {
        synchronized (lock) {
            if (lightActive && lightOwner == LightOwner.VISUAL && lightWhiteLinkedForCycle) {
                clearLightLocked();
            }
            return startPostThrottleLocked(true,
                    visualPostStartMinMs, visualPostStartMaxMs,
                    visualPostDurationMinMs, visualPostDurationMaxMs);
        }
    }

    /** Periodic post profile is fixed. Its light phase remains duration-driven if still active. */
    public boolean finishPeriodicFreezeAndStartPostThrottle() {
        synchronized (lock) {
            return startPostThrottleLocked(false,
                    MIN_START_DELAY_MS, MAX_START_DELAY_MS,
                    MIN_DURATION_MS, MAX_DURATION_MS);
        }
    }

    public boolean finishFreezeAndStartPostThrottle() {
        return finishVisualFreezeAndStartPostThrottle();
    }

    public boolean startFromRobotWhiteReturn() { return false; }
    public void cancelActiveCycle() { synchronized (lock) { clearAllLocked(); } }

    public PacketDecision decide(PacketEnvelope packet) {
        if (packet.direction() != PacketDirection.OUTBOUND || !isIpv4Udp(packet)) return PacketDecision.PASS;
        seen.incrementAndGet();

        final OutputSink output;
        final long generation;
        final long delayNanos;
        synchronized (lock) {
            if (!featureEnabled || sink == null) return PacketDecision.PASS;
            long now = System.nanoTime();
            expireTimedPhasesLocked(now);
            if (!lightActive && !postActive) return PacketDecision.PASS;

            long lightDelay = lightActive ? nextLightDelayLocked(now) : 0L;
            long postDelay = postActive ? nextPostFreezeDelayLocked(now) : 0L;
            // If both phases overlap, one packet is scheduled once using the stronger delay.
            delayNanos = Math.max(lightDelay, postDelay);
            if (delayNanos <= 0L) return PacketDecision.PASS;
            output = sink;
            generation = transportGeneration;
        }

        byte[] copy = new byte[packet.length()];
        System.arraycopy(packet.data(), 0, copy, 0, copy.length);
        PacketEnvelope held = new PacketEnvelope(copy, copy.length,
                PacketDirection.OUTBOUND, packet.monotonicNanos());
        delayed.incrementAndGet();
        scheduler.schedule(() -> emitIfCurrent(output, held, generation), delayNanos, TimeUnit.NANOSECONDS);
        return PacketDecision.HOLD;
    }

    private long nextLightDelayLocked(long now) {
        if (!lightActive || lightStartDelayNanos <= 0L) return 0L;

        if (lightWhiteLinkedForCycle) {
            if (lightFirstDecision) {
                lightFirstDecision = false;
                return lightPreviousDelayNanos;
            }
            int decayPercent = randomInclusiveLocked(MIN_LIGHT_DECAY_PERCENT, MAX_LIGHT_DECAY_PERCENT);
            long next = (lightPreviousDelayNanos * (100L - decayPercent)) / 100L;
            lightPreviousDelayNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), next);
            return lightPreviousDelayNanos;
        }

        long remaining = lightEndsNanos - now;
        if (remaining <= 0L || lightDurationNanos <= 0L) {
            clearLightLocked();
            return 0L;
        }

        if (lightFirstDecision) {
            lightFirstDecision = false;
            long ceiling = Math.max(1L, (lightStartDelayNanos * remaining) / lightDurationNanos);
            lightPreviousDelayNanos = Math.min(lightPreviousDelayNanos, ceiling);
            return lightPreviousDelayNanos;
        }

        int decayPercent = randomInclusiveLocked(MIN_LIGHT_DECAY_PERCENT, MAX_LIGHT_DECAY_PERCENT);
        long percentageStep = (lightPreviousDelayNanos * (100L - decayPercent)) / 100L;
        long timeCeiling = Math.max(1L, (lightStartDelayNanos * remaining) / lightDurationNanos);
        lightPreviousDelayNanos = Math.max(1L, Math.min(percentageStep, timeCeiling));
        return lightPreviousDelayNanos;
    }

    private boolean startPostThrottleLocked(boolean visual,
                                            int startMinMs, int startMaxMs,
                                            int durationMinMs, int durationMaxMs) {
        clearPostLocked();
        if (!featureEnabled || sink == null) return false;
        if (visual && !visualPostEnabled) return false;
        if (rng.nextInt(100) < POST_FREEZE_SKIP_PERCENT) return false;

        int startDelayMs = randomInclusiveLocked(startMinMs, startMaxMs);
        int durationMs = randomInclusiveLocked(durationMinMs, durationMaxMs);
        long now = System.nanoTime();
        postActive = true;
        postVisualOwner = visual;
        postDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        postEndsNanos = now + postDurationNanos;
        postStartDelayNanos = TimeUnit.MILLISECONDS.toNanos(startDelayMs);
        postPreviousDelayNanos = postStartDelayNanos + 1L;
        return true;
    }

    private long nextPostFreezeDelayLocked(long now) {
        if (!postActive) return 0L;
        long remaining = postEndsNanos - now;
        if (remaining <= 0L || postDurationNanos <= 0L || postStartDelayNanos <= 0L) {
            clearPostLocked();
            return 0L;
        }
        long ceiling = Math.max(1L, (postStartDelayNanos * remaining) / postDurationNanos);
        long maxAllowed = Math.min(ceiling, postPreviousDelayNanos - 1L);
        if (maxAllowed <= 0L) {
            clearPostLocked();
            return 0L;
        }
        long floor = Math.max(1L, maxAllowed - TimeUnit.MILLISECONDS.toNanos(12));
        long span = maxAllowed - floor;
        long chosen = span <= 0L ? maxAllowed : floor + nextLongBoundedLocked(span + 1L);
        postPreviousDelayNanos = chosen;
        return chosen;
    }

    private void expireTimedPhasesLocked(long now) {
        if (lightActive && !lightWhiteLinkedForCycle && lightEndsNanos > 0L && now >= lightEndsNanos) {
            clearLightLocked();
        }
        if (postActive && postEndsNanos > 0L && now >= postEndsNanos) {
            clearPostLocked();
        }
    }

    private void emitIfCurrent(OutputSink output, PacketEnvelope packet, long generation) {
        synchronized (lock) { if (sink != output || transportGeneration != generation) return; }
        try { output.emit(packet); } catch (Exception ignored) { }
    }

    private void clearAllLocked() {
        clearLightLocked();
        clearPostLocked();
    }

    private void clearLightLocked() {
        lightActive = false;
        lightOwner = LightOwner.NONE;
        lightWhiteLinkedForCycle = false;
        lightEndsNanos = 0L;
        lightStartedNanos = 0L;
        lightDurationNanos = 0L;
        lightStartDelayNanos = 0L;
        lightPreviousDelayNanos = 0L;
        lightFirstDecision = false;
    }

    private void clearPostLocked() {
        postActive = false;
        postVisualOwner = false;
        postEndsNanos = 0L;
        postDurationNanos = 0L;
        postStartDelayNanos = 0L;
        postPreviousDelayNanos = 0L;
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }

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
