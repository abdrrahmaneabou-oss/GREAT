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

    public static final class CycleStats {
        private final long startedAtMillis;
        private final String source;
        private final int arrived;
        private final int frozen;
        private final int dropped;
        private final int released;
        private final int evicted;

        CycleStats(long startedAtMillis, String source, int arrived, int frozen,
                   int dropped, int released, int evicted) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.arrived = arrived;
            this.frozen = frozen;
            this.dropped = dropped;
            this.released = released;
            this.evicted = evicted;
        }

        public long startedAtMillis() { return startedAtMillis; }
        public String source() { return source; }
        public int arrived() { return arrived; }
        public int frozen() { return frozen; }
        public int dropped() { return dropped; }
        public int released() { return released; }
        public int evicted() { return evicted; }
    }

    public static final int CAPACITY = 10_000;
    public static final int DEFAULT_DURATION_SECONDS = 5;
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int MAX_DURATION_SECONDS = 10;
    public static final int MIN_PAYLOAD_LIMIT = 20;
    public static final int MAX_PAYLOAD_LIMIT = 500;
    public static final int DEFAULT_PAYLOAD_MIN = 20;
    public static final int DEFAULT_PAYLOAD_MAX = 500;
    public static final int DEFAULT_RANDOM_PAYLOAD_MIN_FROM = 20;
    public static final int DEFAULT_RANDOM_PAYLOAD_MIN_TO = 29;
    public static final int DEFAULT_RANDOM_PAYLOAD_MAX_FROM = 450;
    public static final int DEFAULT_RANDOM_PAYLOAD_MAX_TO = 499;
    public static final int OUTBOUND_THROTTLE_WINDOW_MS = 300;
    public static final int OUTBOUND_THROTTLE_MAX_DELAY_MS = 100;

    private static final int MIN_RELEASE_BURST = 1;
    private static final int MAX_RELEASE_BURST = 4;
    private static final int MIN_RELEASE_DELAY_MS = 30;
    private static final int MAX_RELEASE_DELAY_MS = 100;
    private static final int MIN_RELEASE_DROP_PERCENT = 15;
    private static final int MAX_RELEASE_DROP_PERCENT = 33;

    private static final int MIN_RAMP_DURATION_MS = 70;
    private static final int MAX_RAMP_DURATION_MS = 130;
    private static final int MIN_RAMP_STAGE1_HOLD_PERCENT = 20;
    private static final int MAX_RAMP_STAGE1_HOLD_PERCENT = 40;
    private static final int MIN_RAMP_STAGE2_HOLD_PERCENT = 55;
    private static final int MAX_RAMP_STAGE2_HOLD_PERCENT = 75;

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

    private boolean manualEnabled;
    private boolean visualMonitorHold;
    private boolean closed;
    private long generation;

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

        if (packet.direction() == PacketDirection.INBOUND) {
            synchronized (lock) {
                if (!closed && cycleActive && effectiveLocked()) cycleArrived++;
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
        synchronized (lock) {
            if (closed || !effectiveLocked() || !shouldHold(udpLength - 8)) return PacketDecision.PASS;
            if (!rampAllowsHoldLocked(System.nanoTime())) return PacketDecision.PASS;
            if (buffer.size() == CAPACITY) {
                buffer.removeFirst();
                cycleEvicted++;
                diagnostics.schedulerRejected();
            }
            byte[] copy = new byte[packet.length()];
            System.arraycopy(packet.data(), 0, copy, 0, copy.length);
            buffer.addLast(new PacketEnvelope(copy, copy.length, packet.direction(), packet.monotonicNanos()));
            cycleFrozen++;
            diagnostics.scheduled();
            return PacketDecision.HOLD;
        }
    }

    boolean shouldHold(int payloadLength) { return payloadLength > payloadMin && payloadLength < payloadMax; }

    public void setPayloadRange(int min, int max) {
        if (min < MIN_PAYLOAD_LIMIT || max > MAX_PAYLOAD_LIMIT || min >= max) {
            throw new IllegalArgumentException("Payload range must be 20..500 bytes and min must be less than max");
        }
        synchronized (lock) {
            fixedPayloadMin = min;
            fixedPayloadMax = max;
            if (!randomizedPayloadRangeEnabled) { payloadMin = min; payloadMax = max; }
        }
    }

    public void setRandomPayloadRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        if (!validRandomPayloadRange(minFrom, minTo, maxFrom, maxTo)) {
            throw new IllegalArgumentException("Random payload ranges must stay inside 20..500 and minimum range must remain below maximum range");
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
            if (!enabled) { payloadMin = fixedPayloadMin; payloadMax = fixedPayloadMax; }
        }
    }

    public boolean randomPayloadRangeEnabled() { synchronized (lock) { return randomizedPayloadRangeEnabled; } }
    public int payloadMin() { synchronized (lock) { return payloadMin; } }
    public int payloadMax() { synchronized (lock) { return payloadMax; } }

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
                startCycleLocked("MANUAL");
                choosePayloadRangeForCycleLocked();
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
            boolean wasEffective = effectiveLocked();
            visualMonitorHold = active;
            boolean nowEffective = effectiveLocked();
            if (!wasEffective && nowEffective) {
                buffer.clear();
                startCycleLocked("ROBOT");
                choosePayloadRangeForCycleLocked();
                startRampLocked();
            } else if (wasEffective && !nowEffective) {
                clearRampLocked();
                releaseBufferedLocked();
            }
        }
    }

    public boolean holdTriggerActive() { synchronized (lock) { return visualMonitorHold; } }
    public boolean effectiveActive() { synchronized (lock) { return effectiveLocked(); } }
    private boolean effectiveLocked() { return manualEnabled || visualMonitorHold; }

    private void startCycleLocked(String source) {
        cycleActive = true;
        cycleStartedAtMillis = System.currentTimeMillis();
        cycleSource = source;
        cycleArrived = 0;
        cycleFrozen = 0;
        cycleEvicted = 0;
    }

    private CycleWork finishCycleLocked(ArrayList<PacketEnvelope> packets) {
        CycleWork work = new CycleWork(cycleStartedAtMillis, cycleSource, cycleArrived,
                cycleFrozen, cycleEvicted, packets);
        cycleActive = false;
        cycleStartedAtMillis = 0L;
        cycleSource = "UNKNOWN";
        cycleArrived = 0;
        cycleFrozen = 0;
        cycleEvicted = 0;
        return work;
    }

    private void choosePayloadRangeForCycleLocked() {
        if (!randomizedPayloadRangeEnabled) { payloadMin = fixedPayloadMin; payloadMax = fixedPayloadMax; return; }
        payloadMin = randomInclusiveLocked(randomPayloadMinFrom, randomPayloadMinTo);
        payloadMax = randomInclusiveLocked(randomPayloadMaxFrom, randomPayloadMaxTo);
    }

    private int randomInclusiveLocked(int from, int to) { return from == to ? from : from + rng.nextInt(to - from + 1); }

    private static boolean validRandomPayloadRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        return minFrom >= MIN_PAYLOAD_LIMIT && minFrom <= minTo && minTo < maxFrom
                && maxFrom <= maxTo && maxTo <= MAX_PAYLOAD_LIMIT;
    }

    private void startRampLocked() {
        int durationMs = MIN_RAMP_DURATION_MS + rng.nextInt(MAX_RAMP_DURATION_MS - MIN_RAMP_DURATION_MS + 1);
        rampStartedNanos = System.nanoTime();
        rampDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        rampStage1HoldPercent = MIN_RAMP_STAGE1_HOLD_PERCENT + rng.nextInt(MAX_RAMP_STAGE1_HOLD_PERCENT - MIN_RAMP_STAGE1_HOLD_PERCENT + 1);
        rampStage2HoldPercent = MIN_RAMP_STAGE2_HOLD_PERCENT + rng.nextInt(MAX_RAMP_STAGE2_HOLD_PERCENT - MIN_RAMP_STAGE2_HOLD_PERCENT + 1);
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
        try {
            if (!all.isEmpty()) {
                int dropPercent = MIN_RELEASE_DROP_PERCENT + rng.nextInt(MAX_RELEASE_DROP_PERCENT - MIN_RELEASE_DROP_PERCENT + 1);
                int dropCount = Math.round(all.size() * (dropPercent / 100f));
                dropCount = Math.max(0, Math.min(dropCount, all.size()));
                work.dropped = dropCount;
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
                            work.released++;
                        } catch (Exception e) { return; }
                        index++;
                    }
                    if (index < all.size()) {
                        int delayMs = MIN_RELEASE_DELAY_MS + rng.nextInt(MAX_RELEASE_DELAY_MS - MIN_RELEASE_DELAY_MS + 1);
                        try { Thread.sleep(delayMs); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
        } finally {
            publishCycle(work);
        }
    }

    private void publishCycle(CycleWork work) {
        CycleListener listener;
        synchronized (lock) { listener = cycleListener; }
        if (listener == null) return;
        try {
            listener.onCycleCompleted(new CycleStats(work.startedAtMillis, work.source, work.arrived,
                    work.frozen, work.dropped, work.released, work.evicted));
        } catch (RuntimeException ignored) { }
    }

    private static final class CycleWork {
        final long startedAtMillis;
        final String source;
        final int arrived;
        final int frozen;
        final int evicted;
        final ArrayList<PacketEnvelope> packets;
        int dropped;
        int released;

        CycleWork(long startedAtMillis, String source, int arrived, int frozen, int evicted,
                  ArrayList<PacketEnvelope> packets) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.arrived = arrived;
            this.frozen = frozen;
            this.evicted = evicted;
            this.packets = packets;
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
            cycleActive = false;
            cycleStartedAtMillis = 0L;
            cycleSource = "UNKNOWN";
            cycleArrived = 0;
            cycleFrozen = 0;
            cycleEvicted = 0;
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
