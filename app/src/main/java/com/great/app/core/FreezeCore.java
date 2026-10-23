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
    public interface CycleListener { void onCycleCompleted(CycleStats stats); }

    public static final int TRACE_PAYLOAD_KNOWN = 1 << 16;
    public static final int TRACE_REACHED_PAYLOAD = 1 << 17;
    public static final int TRACE_PAYLOAD_PASS = 1 << 18;
    public static final int TRACE_RAMP_PASS = 1 << 19;
    public static final int TRACE_FROZEN = 1 << 20;
    public static final int TRACE_DROPPED = 1 << 21;
    public static final int TRACE_RELEASED = 1 << 22;
    public static final int TRACE_EVICTED = 1 << 23;
    private static final int TRACE_PAYLOAD_MASK = 0xffff;
    private static final int INITIAL_TRACE_CAPACITY = 256;

    public static final class CycleStats {
        private final long startedAtMillis;
        private final String source;
        private final int arrived;
        private final int frozen;
        private final int dropped;
        private final int released;
        private final int evicted;
        private final int payloadMin;
        private final int payloadMax;
        private final int[] trace;
        private final int traceCount;

        CycleStats(long startedAtMillis, String source, int arrived, int frozen,
                   int dropped, int released, int evicted, int payloadMin, int payloadMax,
                   int[] trace, int traceCount) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.arrived = arrived;
            this.frozen = frozen;
            this.dropped = dropped;
            this.released = released;
            this.evicted = evicted;
            this.payloadMin = payloadMin;
            this.payloadMax = payloadMax;
            this.trace = trace;
            this.traceCount = traceCount;
        }

        public long startedAtMillis() { return startedAtMillis; }
        public String source() { return source; }
        public int arrived() { return arrived; }
        public int frozen() { return frozen; }
        public int dropped() { return dropped; }
        public int released() { return released; }
        public int evicted() { return evicted; }
        public int payloadMin() { return payloadMin; }
        public int payloadMax() { return payloadMax; }
        public int traceCount() { return traceCount; }
        public int traceValue(int index) { return trace[index]; }
        public int tracePayloadBytes(int index) { return trace[index] & TRACE_PAYLOAD_MASK; }
        public boolean traceHas(int index, int flag) { return (trace[index] & flag) != 0; }
    }

    public static final int CAPACITY = 10_000;
    public static final int DEFAULT_DURATION_SECONDS = 5;
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int MAX_DURATION_SECONDS = 10;
    public static final int MIN_PAYLOAD_LIMIT = 1;
    public static final int MAX_PAYLOAD_LIMIT = 600;
    public static final int DEFAULT_PAYLOAD_MIN = 20;
    public static final int DEFAULT_PAYLOAD_MAX = 500;
    public static final int DEFAULT_RANDOM_PAYLOAD_MIN_FROM = 20;
    public static final int DEFAULT_RANDOM_PAYLOAD_MIN_TO = 29;
    public static final int DEFAULT_RANDOM_PAYLOAD_MAX_FROM = 450;
    public static final int DEFAULT_RANDOM_PAYLOAD_MAX_TO = 499;
    public static final int OUTBOUND_THROTTLE_WINDOW_MS = 300;
    public static final int OUTBOUND_THROTTLE_MAX_DELAY_MS = 100;

    public static final int DEFAULT_RELEASE_BURST_MIN = 1;
    public static final int DEFAULT_RELEASE_BURST_MAX = 4;
    public static final int DEFAULT_RELEASE_DELAY_MIN_MS = 30;
    public static final int DEFAULT_RELEASE_DELAY_MAX_MS = 100;
    public static final int DEFAULT_RELEASE_DROP_MIN_PERCENT = 15;
    public static final int DEFAULT_RELEASE_DROP_MAX_PERCENT = 33;
    public static final int MIN_RELEASE_BURST_LIMIT = 1;
    public static final int MAX_RELEASE_BURST_LIMIT = 100;
    public static final int MIN_RELEASE_DELAY_LIMIT_MS = 0;
    public static final int MAX_RELEASE_DELAY_LIMIT_MS = 5000;
    public static final int MIN_RELEASE_DROP_LIMIT_PERCENT = 0;
    public static final int MAX_RELEASE_DROP_LIMIT_PERCENT = 100;

    public static final int DEFAULT_RAMP_DURATION_MIN_MS = 70;
    public static final int DEFAULT_RAMP_DURATION_MAX_MS = 130;
    public static final int DEFAULT_RAMP_STAGE1_HOLD_MIN_PERCENT = 20;
    public static final int DEFAULT_RAMP_STAGE1_HOLD_MAX_PERCENT = 40;
    public static final int DEFAULT_RAMP_STAGE2_HOLD_MIN_PERCENT = 55;
    public static final int DEFAULT_RAMP_STAGE2_HOLD_MAX_PERCENT = 75;
    public static final int MIN_RAMP_DURATION_LIMIT_MS = 0;
    public static final int MAX_RAMP_DURATION_LIMIT_MS = 5000;
    public static final int MIN_RAMP_HOLD_LIMIT_PERCENT = 0;
    public static final int MAX_RAMP_HOLD_LIMIT_PERCENT = 100;

    private static final long OUTBOUND_THROTTLE_WINDOW_NANOS =
            TimeUnit.MILLISECONDS.toNanos(OUTBOUND_THROTTLE_WINDOW_MS);
    private static final long OUTBOUND_THROTTLE_MAX_DELAY_NANOS =
            TimeUnit.MILLISECONDS.toNanos(OUTBOUND_THROTTLE_MAX_DELAY_MS);
    private static final long OUTBOUND_RANDOM_STEP_MAX_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

    private final CapabilityController controller;
    private final EngineDiagnostics diagnostics;
    private final Random rng;
    private final Object lock = new Object();
    private final ArrayDeque<PacketEnvelope> buffer = new ArrayDeque<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GREAT-Freeze-Timer"));
    private final ScheduledExecutorService outboundDelays = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "GREAT-Outbound-Throttle"));
    private final ExecutorService releases = Executors.newSingleThreadExecutor(r -> daemon(r, "GREAT-Freeze-Release"));
    private OutputSink sink;
    private CycleListener cycleListener;
    private volatile CycleStats latestCycleStats;
    private ScheduledFuture<?> timeout;
    private int durationSeconds = DEFAULT_DURATION_SECONDS;

    private int fixedPayloadMin = DEFAULT_PAYLOAD_MIN;
    private int fixedPayloadMax = DEFAULT_PAYLOAD_MAX;
    private int payloadMin = DEFAULT_PAYLOAD_MIN;
    private int payloadMax = DEFAULT_PAYLOAD_MAX;
    private boolean randomizedPayloadRangeEnabled;
    private int randomPayloadMinFrom = DEFAULT_RANDOM_PAYLOAD_MIN_FROM;
    private int randomPayloadMinTo = DEFAULT_RANDOM_PAYLOAD_MIN_TO;
    private int randomPayloadMaxFrom = DEFAULT_RANDOM_PAYLOAD_MAX_FROM;
    private int randomPayloadMaxTo = DEFAULT_RANDOM_PAYLOAD_MAX_TO;

    private volatile boolean releaseDropEnabled = true;
    private volatile boolean releaseBurstEnabled = true;
    private volatile boolean releaseDelayEnabled = true;
    private volatile int releaseDropMinPercent = DEFAULT_RELEASE_DROP_MIN_PERCENT;
    private volatile int releaseDropMaxPercent = DEFAULT_RELEASE_DROP_MAX_PERCENT;
    private volatile int releaseBurstMin = DEFAULT_RELEASE_BURST_MIN;
    private volatile int releaseBurstMax = DEFAULT_RELEASE_BURST_MAX;
    private volatile int releaseDelayMinMs = DEFAULT_RELEASE_DELAY_MIN_MS;
    private volatile int releaseDelayMaxMs = DEFAULT_RELEASE_DELAY_MAX_MS;

    private boolean outboundThrottleEnabled;
    private long outboundThrottleStartedNanos;
    private long outboundThrottleEndsNanos;
    private long outboundThrottlePreviousDelayNanos;

    private boolean cycleActive;
    private long cycleStartedAtMillis;
    private String cycleSource = "UNKNOWN";
    private int cycleArrived;
    private int cycleFrozen;
    private int cycleEvicted;
    private int cyclePayloadMin;
    private int cyclePayloadMax;
    private int[] cycleTrace = new int[INITIAL_TRACE_CAPACITY];
    private int cycleTraceCount;
    private int evictTraceScanIndex;
    private long cycleSerial;

    private boolean manualEnabled;
    private boolean visualMonitorHold;
    private boolean periodicMonitorHold;
    private long visualFreezeDeadlineNanos;
    private boolean closed;
    private long generation;

    private int rampDurationMinMs = DEFAULT_RAMP_DURATION_MIN_MS;
    private int rampDurationMaxMs = DEFAULT_RAMP_DURATION_MAX_MS;
    private int rampStage1MinPercent = DEFAULT_RAMP_STAGE1_HOLD_MIN_PERCENT;
    private int rampStage1MaxPercent = DEFAULT_RAMP_STAGE1_HOLD_MAX_PERCENT;
    private int rampStage2MinPercent = DEFAULT_RAMP_STAGE2_HOLD_MIN_PERCENT;
    private int rampStage2MaxPercent = DEFAULT_RAMP_STAGE2_HOLD_MAX_PERCENT;
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

        if (packet.direction() == PacketDirection.OUTBOUND) {
            synchronized (lock) {
                if (closed || !outboundThrottleEnabled) return PacketDecision.PASS;
                long delayNanos = nextOutboundThrottleDelayLocked(System.nanoTime());
                if (delayNanos <= 0L) return PacketDecision.PASS;
                byte[] copy = new byte[packet.length()];
                System.arraycopy(packet.data(), 0, copy, 0, copy.length);
                PacketEnvelope delayed = new PacketEnvelope(copy, copy.length,
                        PacketDirection.OUTBOUND, packet.monotonicNanos());
                long session = generation;
                diagnostics.scheduled();
                outboundDelays.schedule(() -> emitDelayedOutbound(delayed, session),
                        delayNanos, TimeUnit.NANOSECONDS);
                return PacketDecision.HOLD;
            }
        }

        int traceIndex = -1;
        long traceSerial = -1L;
        if (packet.direction() == PacketDirection.INBOUND) {
            synchronized (lock) {
                expireVisualHoldLocked(System.nanoTime());
                if (!closed && cycleActive && effectiveLocked()) {
                    cycleArrived++;
                    traceIndex = appendTraceLocked();
                    traceSerial = cycleSerial;
                }
            }
        }

        if (packet.direction() != PacketDirection.INBOUND || !m.valid() || m.ipVersion() != 4
                || m.protocol() != PacketParser.PROTO_UDP || m.fragmented()) return PacketDecision.PASS;
        int offset = m.transportOffset();
        if (offset < 20 || offset + 8 > packet.length()) return PacketDecision.PASS;
        int udpLength = u16(packet.data(), offset + 4);
        int ipLength = u16(packet.data(), 2);
        if (udpLength < 8 || offset + udpLength > ipLength) return PacketDecision.PASS;
        int remotePort = m.sourcePort();
        if (remotePort >= 7000 && remotePort <= 10000) return PacketDecision.PASS;
        int payloadLength = udpLength - 8;
        synchronized (lock) {
            expireVisualHoldLocked(System.nanoTime());
            if (closed || !effectiveLocked()) return PacketDecision.PASS;
            if (traceIndex >= 0 && traceSerial == cycleSerial && traceIndex < cycleTraceCount) {
                cycleTrace[traceIndex] = (payloadLength & TRACE_PAYLOAD_MASK)
                        | TRACE_PAYLOAD_KNOWN | TRACE_REACHED_PAYLOAD;
            }
            if (!shouldHold(payloadLength)) return PacketDecision.PASS;
            markTraceLocked(traceIndex, traceSerial, TRACE_PAYLOAD_PASS);
            if (!rampAllowsHoldLocked(System.nanoTime())) return PacketDecision.PASS;
            markTraceLocked(traceIndex, traceSerial, TRACE_RAMP_PASS);
            if (buffer.size() == CAPACITY) {
                buffer.removeFirst();
                markOldestBufferedTraceEvictedLocked();
                cycleEvicted++;
                diagnostics.schedulerRejected();
            }
            byte[] copy = new byte[packet.length()];
            System.arraycopy(packet.data(), 0, copy, 0, copy.length);
            buffer.addLast(new PacketEnvelope(copy, copy.length, packet.direction(), packet.monotonicNanos()));
            markTraceLocked(traceIndex, traceSerial, TRACE_FROZEN);
            cycleFrozen++;
            diagnostics.scheduled();
            return PacketDecision.HOLD;
        }
    }

    private int appendTraceLocked() {
        if (cycleTraceCount >= CAPACITY) return -1;
        if (cycleTraceCount == cycleTrace.length) {
            int nextLength = cycleTrace.length < 16_384 ? cycleTrace.length * 2 : cycleTrace.length + 16_384;
            int[] next = new int[nextLength];
            System.arraycopy(cycleTrace, 0, next, 0, cycleTraceCount);
            cycleTrace = next;
        }
        cycleTrace[cycleTraceCount] = 0;
        return cycleTraceCount++;
    }

    private void markTraceLocked(int index, long serial, int flag) {
        if (index >= 0 && serial == cycleSerial && index < cycleTraceCount) cycleTrace[index] |= flag;
    }

    private void markOldestBufferedTraceEvictedLocked() {
        while (evictTraceScanIndex < cycleTraceCount) {
            int value = cycleTrace[evictTraceScanIndex];
            if ((value & TRACE_FROZEN) != 0 && (value & TRACE_EVICTED) == 0) {
                cycleTrace[evictTraceScanIndex] = value | TRACE_EVICTED;
                evictTraceScanIndex++;
                return;
            }
            evictTraceScanIndex++;
        }
    }

    boolean shouldHold(int payloadLength) { return payloadLength >= payloadMin && payloadLength <= payloadMax; }

    public void setPayloadRange(int min, int max) {
        if (min < MIN_PAYLOAD_LIMIT || max > MAX_PAYLOAD_LIMIT || min >= max) {
            throw new IllegalArgumentException("Payload range must be 1..600 bytes and min must be less than max");
        }
        synchronized (lock) {
            fixedPayloadMin = min;
            fixedPayloadMax = max;
            if (!randomizedPayloadRangeEnabled) {
                payloadMin = min; payloadMax = max;
                if (cycleActive) { cyclePayloadMin = min; cyclePayloadMax = max; }
            }
        }
    }

    public void setRandomPayloadRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        if (!validRandomPayloadRange(minFrom, minTo, maxFrom, maxTo)) {
            throw new IllegalArgumentException("Random payload ranges must stay inside 1..600 and minimum range must remain below maximum range");
        }
        synchronized (lock) {
            randomPayloadMinFrom = minFrom;
            randomPayloadMinTo = minTo;
            randomPayloadMaxFrom = maxFrom;
            randomPayloadMaxTo = maxTo;
        }
    }

    public void setRandomPayloadRangeEnabled(boolean enabled) {
        synchronized (lock) {
            randomizedPayloadRangeEnabled = enabled;
            if (!enabled) {
                payloadMin = fixedPayloadMin; payloadMax = fixedPayloadMax;
                if (cycleActive) { cyclePayloadMin = payloadMin; cyclePayloadMax = payloadMax; }
            }
        }
    }

    public boolean randomPayloadRangeEnabled() { synchronized (lock) { return randomizedPayloadRangeEnabled; } }
    public int payloadMin() { synchronized (lock) { return payloadMin; } }
    public int payloadMax() { synchronized (lock) { return payloadMax; } }

    public void setReleaseDropEnabled(boolean enabled) { releaseDropEnabled = enabled; }
    public void setReleaseBurstEnabled(boolean enabled) { releaseBurstEnabled = enabled; }
    public void setReleaseDelayEnabled(boolean enabled) { releaseDelayEnabled = enabled; }

    public void setReleaseDropRange(int minPercent, int maxPercent) {
        if (minPercent < MIN_RELEASE_DROP_LIMIT_PERCENT
                || maxPercent > MAX_RELEASE_DROP_LIMIT_PERCENT || minPercent > maxPercent) {
            throw new IllegalArgumentException("Release drop range must be 0..100% and min must be <= max");
        }
        synchronized (lock) { releaseDropMinPercent = minPercent; releaseDropMaxPercent = maxPercent; }
    }

    public void setReleaseBurstRange(int minBurst, int maxBurst) {
        if (minBurst < MIN_RELEASE_BURST_LIMIT
                || maxBurst > MAX_RELEASE_BURST_LIMIT || minBurst > maxBurst) {
            throw new IllegalArgumentException("Release burst range must be 1..100 packets and min must be <= max");
        }
        synchronized (lock) { releaseBurstMin = minBurst; releaseBurstMax = maxBurst; }
    }

    public void setReleaseDelayRangeMs(int minMs, int maxMs) {
        if (minMs < MIN_RELEASE_DELAY_LIMIT_MS
                || maxMs > MAX_RELEASE_DELAY_LIMIT_MS || minMs > maxMs) {
            throw new IllegalArgumentException("Release delay range must be 0..5000 ms and min must be <= max");
        }
        synchronized (lock) { releaseDelayMinMs = minMs; releaseDelayMaxMs = maxMs; }
    }

    public void setRampProfile(int durationMinMs, int durationMaxMs,
                               int stage1MinPercent, int stage1MaxPercent,
                               int stage2MinPercent, int stage2MaxPercent) {
        if (durationMinMs < MIN_RAMP_DURATION_LIMIT_MS || durationMaxMs > MAX_RAMP_DURATION_LIMIT_MS
                || durationMinMs > durationMaxMs) {
            throw new IllegalArgumentException("Ramp duration must be 0..5000 ms and min <= max");
        }
        if (!validPercentRange(stage1MinPercent, stage1MaxPercent)
                || !validPercentRange(stage2MinPercent, stage2MaxPercent)) {
            throw new IllegalArgumentException("Ramp hold percentages must be 0..100 and min <= max");
        }
        synchronized (lock) {
            rampDurationMinMs = durationMinMs;
            rampDurationMaxMs = durationMaxMs;
            rampStage1MinPercent = stage1MinPercent;
            rampStage1MaxPercent = stage1MaxPercent;
            rampStage2MinPercent = stage2MinPercent;
            rampStage2MaxPercent = stage2MaxPercent;
            // A zero-duration OFF request must cancel the ramp of the current cycle too.
            if (durationMinMs == 0 && durationMaxMs == 0) clearRampLocked();
        }
    }

    private static boolean validPercentRange(int min, int max) {
        return min >= MIN_RAMP_HOLD_LIMIT_PERCENT && max <= MAX_RAMP_HOLD_LIMIT_PERCENT && min <= max;
    }

    public void setOutboundThrottleEnabled(boolean enabled) {
        synchronized (lock) {
            outboundThrottleEnabled = enabled;
            if (!enabled) clearOutboundThrottleLocked();
        }
    }

    public boolean outboundThrottleEnabled() { synchronized (lock) { return outboundThrottleEnabled; } }

    public boolean outboundThrottleActive() {
        synchronized (lock) {
            if (!outboundThrottleEnabled || outboundThrottleEndsNanos <= 0L) return false;
            if (System.nanoTime() >= outboundThrottleEndsNanos) {
                clearOutboundThrottleLocked();
                return false;
            }
            return true;
        }
    }

    /** Starts the OUTBOUND throttle only for a genuine ROBOT white-return release. */
    public void startOutboundThrottleFromRobotWhiteReturn() {
        synchronized (lock) {
            if (closed || !visualMonitorHold || manualEnabled) return;
            startOutboundThrottleLocked();
        }
    }

    public void setCycleListener(CycleListener listener) { synchronized (lock) { cycleListener = listener; } }
    public CycleStats latestCycleStats() { return latestCycleStats; }

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
                timeout = timers.schedule(() -> controller.setIfRevision(Capability.FREEZE, false, activation),
                        durationSeconds, TimeUnit.SECONDS);
            }
            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
                choosePayloadRangeForCycleLocked();
                startCycleLocked("MANUAL");
                startRampLocked();
            } else if (wasEffective && !nowEffective) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    public void setHoldTrigger(boolean active) {
        synchronized (lock) {
            if (closed || visualMonitorHold == active) return;
            if (!active) visualFreezeDeadlineNanos = 0L;
            boolean wasEffective = effectiveLocked();
            visualMonitorHold = active;
            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
                choosePayloadRangeForCycleLocked();
                startCycleLocked("ROBOT");
                startRampLocked();
            } else if (wasEffective && !nowEffective) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    /** Periodic requests are independent of visual detector transitions. */
    public void setPeriodicHoldTrigger(boolean active) {
        synchronized (lock) {
            if (closed || periodicMonitorHold == active) return;
            boolean before = effectiveLocked();
            periodicMonitorHold = active;
            boolean after = effectiveLocked();
            if (!before && after) {
                buffer.clear();
                choosePayloadRangeForCycleLocked();
                startCycleLocked("ROBOT_PERIODIC");
                startRampLocked();
            } else if (before && !after) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    /** Monotonic deadline enforced on the packet path, independently of the UI Handler. */
    public void setVisualFreezeDeadlineMillis(int durationMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        synchronized (lock) {
            if (visualMonitorHold) {
                visualFreezeDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
            }
        }
    }

    private void expireVisualHoldLocked(long now) {
        if (!visualMonitorHold || visualFreezeDeadlineNanos == 0L
                || now - visualFreezeDeadlineNanos < 0L) return;
        boolean before = effectiveLocked();
        visualMonitorHold = false;
        visualFreezeDeadlineNanos = 0L;
        if (before && !effectiveLocked()) {
            clearRampLocked();
            releaseBufferedLocked();
        }
    }

    public boolean holdTriggerActive() {
        synchronized (lock) {
            expireVisualHoldLocked(System.nanoTime());
            return visualMonitorHold || periodicMonitorHold;
        }
    }
    public boolean effectiveActive() {
        synchronized (lock) { expireVisualHoldLocked(System.nanoTime()); return effectiveLocked(); }
    }
    private boolean effectiveLocked() { return manualEnabled || visualMonitorHold || periodicMonitorHold; }

    private void startCycleLocked(String source) {
        cycleActive = true;
        cycleStartedAtMillis = System.currentTimeMillis();
        cycleSource = source;
        cycleArrived = 0;
        cycleFrozen = 0;
        cycleEvicted = 0;
        cyclePayloadMin = payloadMin;
        cyclePayloadMax = payloadMax;
        cycleTrace = new int[INITIAL_TRACE_CAPACITY];
        cycleTraceCount = 0;
        evictTraceScanIndex = 0;
        cycleSerial++;
    }

    private CycleWork finishCycleLocked(ArrayList<PacketEnvelope> packets) {
        int[] trace = cycleTrace;
        int traceCount = cycleTraceCount;
        int[] heldTraceIds = buildHeldTraceIdsLocked(packets.size());
        CycleWork work = new CycleWork(cycleStartedAtMillis, cycleSource, cycleArrived,
                cycleFrozen, cycleEvicted, cyclePayloadMin, cyclePayloadMax,
                packets, trace, traceCount, heldTraceIds);
        cycleActive = false;
        cycleStartedAtMillis = 0L;
        cycleSource = "UNKNOWN";
        cycleArrived = 0;
        cycleFrozen = 0;
        cycleEvicted = 0;
        cycleTrace = new int[INITIAL_TRACE_CAPACITY];
        cycleTraceCount = 0;
        evictTraceScanIndex = 0;
        return work;
    }

    private int[] buildHeldTraceIdsLocked(int expected) {
        int[] ids = new int[expected];
        int out = 0;
        for (int i = 0; i < cycleTraceCount && out < expected; i++) {
            int value = cycleTrace[i];
            if ((value & TRACE_FROZEN) != 0 && (value & TRACE_EVICTED) == 0) ids[out++] = i;
        }
        while (out < expected) ids[out++] = -1;
        return ids;
    }

    private void choosePayloadRangeForCycleLocked() {
        if (!randomizedPayloadRangeEnabled) { payloadMin = fixedPayloadMin; payloadMax = fixedPayloadMax; return; }
        payloadMin = randomInclusiveLocked(randomPayloadMinFrom, randomPayloadMinTo);
        payloadMax = randomInclusiveLocked(randomPayloadMaxFrom, randomPayloadMaxTo);
    }

    private int randomInclusiveLocked(int from, int to) { return from == to ? from : from + rng.nextInt(to - from + 1); }
    private int randomInclusive(int from, int to) { return from == to ? from : from + rng.nextInt(to - from + 1); }

    private static boolean validRandomPayloadRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        return minFrom >= MIN_PAYLOAD_LIMIT && minFrom <= minTo && minTo < maxFrom
                && maxFrom <= maxTo && maxTo <= MAX_PAYLOAD_LIMIT;
    }

    private void startRampLocked() {
        int durationMs = randomInclusiveLocked(rampDurationMinMs, rampDurationMaxMs);
        rampStartedNanos = System.nanoTime();
        rampDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        rampStage1HoldPercent = randomInclusiveLocked(rampStage1MinPercent, rampStage1MaxPercent);
        rampStage2HoldPercent = randomInclusiveLocked(rampStage2MinPercent, rampStage2MaxPercent);
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
        if (elapsed < 0L || elapsed >= rampDurationNanos) { clearRampLocked(); return true; }
        long firstHalf = rampDurationNanos / 2L;
        int holdPercent = elapsed < firstHalf ? rampStage1HoldPercent : rampStage2HoldPercent;
        return rng.nextInt(100) < holdPercent;
    }

    private void startOutboundThrottleLocked() {
        if (!outboundThrottleEnabled) { clearOutboundThrottleLocked(); return; }
        long now = System.nanoTime();
        outboundThrottleStartedNanos = now;
        outboundThrottleEndsNanos = now + OUTBOUND_THROTTLE_WINDOW_NANOS;
        outboundThrottlePreviousDelayNanos = OUTBOUND_THROTTLE_MAX_DELAY_NANOS + 1L;
    }

    private void clearOutboundThrottleLocked() {
        outboundThrottleStartedNanos = 0L;
        outboundThrottleEndsNanos = 0L;
        outboundThrottlePreviousDelayNanos = 0L;
    }

    private long nextOutboundThrottleDelayLocked(long nowNanos) {
        if (outboundThrottleStartedNanos <= 0L || outboundThrottleEndsNanos <= 0L || nowNanos >= outboundThrottleEndsNanos) {
            clearOutboundThrottleLocked();
            return 0L;
        }
        long remaining = outboundThrottleEndsNanos - nowNanos;
        long timeCeiling = (OUTBOUND_THROTTLE_MAX_DELAY_NANOS * remaining) / OUTBOUND_THROTTLE_WINDOW_NANOS;
        long strictCeiling = Math.min(timeCeiling, outboundThrottlePreviousDelayNanos - 1L);
        if (strictCeiling <= 0L) { outboundThrottlePreviousDelayNanos = 0L; return 0L; }
        long maxRandomStep = Math.min(OUTBOUND_RANDOM_STEP_MAX_NANOS, strictCeiling);
        long randomStep = 1L + (long) (rng.nextDouble() * maxRandomStep);
        long next = Math.max(1L, strictCeiling - randomStep + 1L);
        outboundThrottlePreviousDelayNanos = next;
        return next;
    }

    private void emitDelayedOutbound(PacketEnvelope packet, long session) {
        OutputSink output;
        synchronized (lock) {
            if (closed || session != generation || sink == null) return;
            output = sink;
        }
        try { output.emit(packet); diagnostics.released(); } catch (Exception ignored) { }
    }

    private void releaseBufferedLocked() {
        ArrayList<PacketEnvelope> all = new ArrayList<>(buffer);
        buffer.clear();
        CycleWork work = finishCycleLocked(all);
        long session = generation;
        releases.execute(() -> release(work, session));
    }

    private void release(CycleWork work, long session) {
        ArrayList<PacketEnvelope> all = work.packets;
        int[] traceIds = work.heldTraceIds;
        final boolean dropEnabled;
        final int dropMin;
        final int dropMax;
        synchronized (lock) {
            dropEnabled = releaseDropEnabled;
            dropMin = releaseDropMinPercent;
            dropMax = releaseDropMaxPercent;
        }
        try {
            if (!all.isEmpty()) {
                int dropPercent = dropEnabled ? randomInclusive(dropMin, dropMax) : 0;
                int dropCount = Math.round(all.size() * (dropPercent / 100f));
                dropCount = Math.max(0, Math.min(dropCount, all.size()));
                work.dropped = dropCount;
                if (dropCount > 0) {
                    int oldSize = all.size();
                    int maxStart = oldSize - dropCount;
                    int dropStart = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
                    for (int i = dropStart; i < dropStart + dropCount; i++) {
                        markWorkTrace(work, traceIds[i], TRACE_DROPPED);
                    }
                    all.subList(dropStart, dropStart + dropCount).clear();
                    int[] remaining = new int[oldSize - dropCount];
                    if (dropStart > 0) System.arraycopy(traceIds, 0, remaining, 0, dropStart);
                    int tail = oldSize - (dropStart + dropCount);
                    if (tail > 0) System.arraycopy(traceIds, dropStart + dropCount, remaining, dropStart, tail);
                    traceIds = remaining;
                }
                if (all.size() > 1) {
                    for (int i = all.size() - 1; i > 0; i--) {
                        int j = rng.nextInt(i + 1);
                        Collections.swap(all, i, j);
                        int tmp = traceIds[i];
                        traceIds[i] = traceIds[j];
                        traceIds[j] = tmp;
                    }
                }
                int index = 0;
                while (index < all.size()) {
                    final boolean burstEnabled;
                    final int burstMin;
                    final int burstMax;
                    synchronized (lock) {
                        burstEnabled = releaseBurstEnabled;
                        burstMin = releaseBurstMin;
                        burstMax = releaseBurstMax;
                    }
                    int burstSize = burstEnabled ? randomInclusive(burstMin, burstMax) : (all.size() - index);
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
                            markWorkTrace(work, traceIds[index], TRACE_RELEASED);
                            work.released++;
                        } catch (Exception e) { return; }
                        index++;
                    }
                    // OFF is checked between batches, rather than using stale task-local flags.
                    final boolean delayEnabled;
                    final int delayMin;
                    final int delayMax;
                    synchronized (lock) {
                        delayEnabled = releaseDelayEnabled;
                        delayMin = releaseDelayMinMs;
                        delayMax = releaseDelayMaxMs;
                    }
                    if (index < all.size() && delayEnabled) {
                        int delayMs = randomInclusive(delayMin, delayMax);
                        if (delayMs > 0) {
                            try {
                                // Recheck OFF quickly even if a long delay was selected.
                                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
                                while (releaseDelayEnabled && System.nanoTime() < until) {
                                    long ms = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
                                    Thread.sleep(Math.max(1L, Math.min(20L, ms)));
                                }
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                }
            }
        } finally {
            publishCycle(work);
        }
    }

    private static void markWorkTrace(CycleWork work, int index, int flag) {
        if (index >= 0 && index < work.traceCount) work.trace[index] |= flag;
    }

    private void publishCycle(CycleWork work) {
        CycleStats stats = new CycleStats(work.startedAtMillis, work.source, work.arrived,
                work.frozen, work.dropped, work.released, work.evicted,
                work.payloadMin, work.payloadMax, work.trace, work.traceCount);
        latestCycleStats = stats;
        CycleListener listener;
        synchronized (lock) { listener = cycleListener; }
        if (listener == null) return;
        try { listener.onCycleCompleted(stats); } catch (RuntimeException ignored) { }
    }

    private static final class CycleWork {
        final long startedAtMillis;
        final String source;
        final int arrived;
        final int frozen;
        final int evicted;
        final int payloadMin;
        final int payloadMax;
        final ArrayList<PacketEnvelope> packets;
        final int[] trace;
        final int traceCount;
        final int[] heldTraceIds;
        int dropped;
        int released;

        CycleWork(long startedAtMillis, String source, int arrived, int frozen, int evicted,
                  int payloadMin, int payloadMax, ArrayList<PacketEnvelope> packets,
                  int[] trace, int traceCount, int[] heldTraceIds) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.arrived = arrived;
            this.frozen = frozen;
            this.evicted = evicted;
            this.payloadMin = payloadMin;
            this.payloadMax = payloadMax;
            this.packets = packets;
            this.trace = trace;
            this.traceCount = traceCount;
            this.heldTraceIds = heldTraceIds;
        }
    }

    public void attach(OutputSink output) { synchronized (lock) { sink = Objects.requireNonNull(output); } }
    public void detach(OutputSink output) { synchronized (lock) { if (sink == output) { sink = null; generation++; } } }
    public int freezeQueueSize() { synchronized (lock) { return buffer.size(); } }
    public int freezeDurationSeconds() { synchronized (lock) { return durationSeconds; } }

    public void setFreezeDurationSeconds(int seconds) {
        if (seconds < MIN_DURATION_SECONDS || seconds > MAX_DURATION_SECONDS) throw new IllegalArgumentException("duration must be 1..10 seconds");
        synchronized (lock) { durationSeconds = seconds; }
    }

    public void reset() {
        synchronized (lock) {
            manualEnabled = false;
            visualMonitorHold = false;
            periodicMonitorHold = false;
            visualFreezeDeadlineNanos = 0L;
            cycleActive = false;
            cycleStartedAtMillis = 0L;
            cycleSource = "UNKNOWN";
            cycleArrived = 0;
            cycleFrozen = 0;
            cycleEvicted = 0;
            cycleTrace = new int[INITIAL_TRACE_CAPACITY];
            cycleTraceCount = 0;
            evictTraceScanIndex = 0;
            clearRampLocked();
            clearOutboundThrottleLocked();
            generation++;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            buffer.clear();
            if (!randomizedPayloadRangeEnabled) { payloadMin = fixedPayloadMin; payloadMax = fixedPayloadMax; }
        }
    }

    @Override public void close() {
        synchronized (lock) { reset(); closed = true; sink = null; }
        timers.shutdownNow();
        outboundDelays.shutdownNow();
        releases.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static int u16(byte[] d, int i) { return ((d[i] & 255) << 8) | (d[i + 1] & 255); }
}
