package com.great.app.core;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capability kernel surgically reconstructed from FOX V12 packet behavior.
 *
 * Only the packet-selection/hold/replay behavior lives here. FOX UI, service
 * actions, preferences, proxy sockets and package names are intentionally not
 * carried over. GREAT supplies the transport seam through OutputSink.
 */
public final class FoxCapabilityCore implements AutoCloseable {
    public interface OutputSink {
        void emit(PacketEnvelope packet) throws Exception;
    }

    public static final int DEFAULT_DURATION_SECONDS = 5;
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int MAX_DURATION_SECONDS = 10;

    private static final int PROTO_UDP = 17;
    private static final int FREEZE_CAPACITY = 10_000;
    private static final int TELEPORT_CAPACITY = 5_000;
    private static final int TELEPORT_DEFAULT_MODE = 1;

    private final CapabilityController controller;
    private final EngineDiagnostics diagnostics;
    private final Random rng;
    private final ConcurrentLinkedQueue<PacketEnvelope> freezeBuffer = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PacketEnvelope> teleportBuffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService timeoutExecutor;
    private final ExecutorService releaseExecutor;
    private final AtomicReference<OutputSink> sink = new AtomicReference<>();
    private final AtomicInteger freezeDurationSeconds = new AtomicInteger(DEFAULT_DURATION_SECONDS);
    private final AtomicInteger teleportDurationSeconds = new AtomicInteger(DEFAULT_DURATION_SECONDS);

    private final Object timeoutLock = new Object();
    private volatile ScheduledFuture<?> freezeTimeout;
    private volatile ScheduledFuture<?> teleportTimeout;
    private volatile boolean teleportReplayActive;
    private volatile int teleportMode = TELEPORT_DEFAULT_MODE;
    private volatile boolean closed;

    public FoxCapabilityCore(CapabilityController controller, EngineDiagnostics diagnostics) {
        this(controller, diagnostics, new Random());
    }

    FoxCapabilityCore(CapabilityController controller, EngineDiagnostics diagnostics, Random rng) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.rng = Objects.requireNonNull(rng, "rng");
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GREAT-Capability-Timeout");
            t.setDaemon(true);
            return t;
        });
        releaseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GREAT-Capability-Release");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
    }

    /** Evaluates only packets already accepted by GREAT's external target selector. */
    public PacketDecision decide(PacketContext context, EngineSnapshot state) {
        PacketMetadata metadata = context.metadata();
        if (!metadata.valid() || metadata.ipVersion() != 4 || metadata.protocol() != PROTO_UDP) {
            return PacketDecision.PASS;
        }

        UdpView udp = UdpView.parse(context.packet(), metadata);
        if (udp == null) return PacketDecision.PASS;

        if (context.packet().direction() == PacketDirection.INBOUND) {
            return decideInbound(context.packet(), udp, state);
        }
        return decideOutbound(context.packet(), udp, state);
    }

    private PacketDecision decideInbound(PacketEnvelope packet, UdpView udp, EngineSnapshot state) {
        if (!state.enabled(Capability.FREEZE)) return PacketDecision.PASS;

        // FOX RecvRunnable.dp is the original remote destination port. In the
        // rebuilt inbound IP packet this is the UDP source port.
        if (between(udp.sourcePort, 7000, 10000)) return PacketDecision.PASS;
        if (!shouldFreezeHold(udp.payloadLength)) return PacketDecision.PASS;

        offerBounded(freezeBuffer, owned(packet), FREEZE_CAPACITY);
        diagnostics.scheduled();
        return PacketDecision.HOLD;
    }

    private PacketDecision decideOutbound(PacketEnvelope packet, UdpView udp, EngineSnapshot state) {
        // FOX fast path: positive UDP payloads up to 45 bytes bypass every
        // capability branch and are forwarded immediately.
        if (udp.payloadLength > 0 && udp.payloadLength <= 45) return PacketDecision.PASS;

        // FOX bypass flag: destination UDP ports 7000..10000 skip Ghost,
        // Teleport and the rest of the legacy capability path.
        if (between(udp.destinationPort, 7000, 10000)) return PacketDecision.PASS;

        if (state.enabled(Capability.GHOST) && shouldGhostDrop(udp.payloadLength)) {
            return PacketDecision.DROP;
        }

        if (state.enabled(Capability.TELEPORT) && udp.payloadLength > 20) {
            if (shouldCaptureTeleport(udp.destinationPort, udp.payloadLength)) {
                offerBounded(teleportBuffer, owned(packet), TELEPORT_CAPACITY);
                diagnostics.scheduled();
            }

            int mode = teleportMode;
            if (mode == 1 || mode == 9) return PacketDecision.DROP;
            if (mode == 2 && rng.nextInt(100) < 70) return PacketDecision.DROP;
            if (mode == 4) return PacketDecision.DROP;
        }

        // FOX TeleInfinity blocks new outbound payloads while replay is active.
        if (teleportReplayActive && udp.payloadLength > 20) return PacketDecision.DROP;
        return PacketDecision.PASS;
    }

    /** Exact FOX V12 Ghost predicate after the earlier bypasses. */
    static boolean shouldGhostDrop(int udpPayloadLength) {
        return udpPayloadLength >= 50 && udpPayloadLength <= 200;
    }

    /** Exact FOX V12 Freeze predicate defaults: (20+0..9, 450+0..49), exclusive. */
    boolean shouldFreezeHold(int payloadLength) {
        int lower = 20 + rng.nextInt(10);
        int upper = 450 + rng.nextInt(50);
        if (lower >= upper) lower = upper - 1;
        return payloadLength > lower && payloadLength < upper;
    }

    /** FOX V12 Teleport capture window, before its mode-specific drop decision. */
    static boolean shouldCaptureTeleport(int destinationPort, int payloadLength) {
        if (payloadLength < 25) return false;
        return between(destinationPort, 10000, 10030)
                || between(destinationPort, 20000, 20030);
    }

    public void onCapabilityChanged(Capability capability, boolean enabled) {
        if (closed) return;
        switch (capability) {
            case FREEZE -> {
                if (enabled) enableFreeze();
                else disableFreezeAndRelease();
            }
            case TELEPORT -> {
                if (enabled) enableTeleport();
                else disableTeleportAndReplay();
            }
            case GHOST -> { /* FOX Ghost owns no queue and needs no timer. */ }
        }
    }

    private void enableFreeze() {
        cancelFreezeTimeout();
        freezeBuffer.clear();
        scheduleFreezeTimeout();
    }

    private void disableFreezeAndRelease() {
        cancelFreezeTimeout();
        final ArrayList<PacketEnvelope> all = drain(freezeBuffer);
        if (all.isEmpty()) return;
        releaseExecutor.execute(() -> {
            for (int i = 0; i < all.size(); i++) {
                if (!emit(all.get(i), false)) break;
                if (i > 0 && (i & 1) == 0) sleepQuietly(1 + rng.nextInt(3));
            }
        });
    }

    private void enableTeleport() {
        cancelTeleportTimeout();
        teleportReplayActive = false;
        teleportBuffer.clear();
        teleportMode = TELEPORT_DEFAULT_MODE;
        scheduleTeleportTimeout();
    }

    private void disableTeleportAndReplay() {
        cancelTeleportTimeout();
        final ArrayList<PacketEnvelope> all = drain(teleportBuffer);
        if (all.isEmpty()) {
            teleportReplayActive = false;
            return;
        }
        teleportReplayActive = true;
        releaseExecutor.execute(() -> replayTeleport(all));
    }

    private void replayTeleport(ArrayList<PacketEnvelope> all) {
        final int count = all.size();
        int baseDelay = 5 + rng.nextInt(6); // FOX: 5..10ms.
        if (count > 1500) baseDelay = 0;
        else if (count > 500) baseDelay = 1;

        try {
            for (int i = 0; i < count && teleportReplayActive; i++) {
                if (!emit(all.get(i), true)) break;

                int delay = baseDelay > 0 ? baseDelay + rng.nextInt(4) : 0;
                if (delay == 0 && i % 10 == 0) delay = 1 + rng.nextInt(2);
                if (delay > 0) sleepQuietly(delay);
            }
        } finally {
            teleportReplayActive = false;
            teleportBuffer.clear();
        }
    }

    private boolean emit(PacketEnvelope packet, boolean replay) {
        OutputSink current = sink.get();
        if (current == null) return false;
        try {
            current.emit(packet);
            if (replay) diagnostics.replayed(); else diagnostics.released();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void attach(OutputSink output) {
        sink.set(Objects.requireNonNull(output, "output"));
    }

    public void detach(OutputSink output) {
        sink.compareAndSet(output, null);
    }

    public int freezeDurationSeconds() { return freezeDurationSeconds.get(); }
    public int teleportDurationSeconds() { return teleportDurationSeconds.get(); }

    public void setFreezeDurationSeconds(int seconds) {
        freezeDurationSeconds.set(validateDuration(seconds));
    }

    public void setTeleportDurationSeconds(int seconds) {
        teleportDurationSeconds.set(validateDuration(seconds));
    }

    public int freezeQueueSize() { return freezeBuffer.size(); }
    public int teleportQueueSize() { return teleportBuffer.size(); }
    public boolean teleportReplayActive() { return teleportReplayActive; }

    /** Internal test hook; GREAT's user-facing Teleport uses FOX's default mode 1. */
    void setTeleportModeForTest(int mode) { teleportMode = mode; }

    public void reset() {
        cancelFreezeTimeout();
        cancelTeleportTimeout();
        teleportReplayActive = false;
        freezeBuffer.clear();
        teleportBuffer.clear();
        teleportMode = TELEPORT_DEFAULT_MODE;
    }

    private void scheduleFreezeTimeout() {
        synchronized (timeoutLock) {
            freezeTimeout = timeoutExecutor.schedule(
                    () -> controller.set(Capability.FREEZE, false),
                    freezeDurationSeconds.get(), TimeUnit.SECONDS);
        }
    }

    private void scheduleTeleportTimeout() {
        synchronized (timeoutLock) {
            teleportTimeout = timeoutExecutor.schedule(
                    () -> controller.set(Capability.TELEPORT, false),
                    teleportDurationSeconds.get(), TimeUnit.SECONDS);
        }
    }

    private void cancelFreezeTimeout() {
        synchronized (timeoutLock) {
            ScheduledFuture<?> f = freezeTimeout;
            freezeTimeout = null;
            if (f != null) f.cancel(false);
        }
    }

    private void cancelTeleportTimeout() {
        synchronized (timeoutLock) {
            ScheduledFuture<?> f = teleportTimeout;
            teleportTimeout = null;
            if (f != null) f.cancel(false);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        reset();
        sink.set(null);
        timeoutExecutor.shutdownNow();
        releaseExecutor.shutdownNow();
    }

    private static int validateDuration(int seconds) {
        if (seconds < MIN_DURATION_SECONDS || seconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("duration must be 1..10 seconds");
        }
        return seconds;
    }

    private static PacketEnvelope owned(PacketEnvelope packet) {
        byte[] copy = new byte[packet.length()];
        System.arraycopy(packet.data(), 0, copy, 0, packet.length());
        return new PacketEnvelope(copy, copy.length, packet.direction(), packet.monotonicNanos());
    }

    private void offerBounded(ConcurrentLinkedQueue<PacketEnvelope> queue,
                              PacketEnvelope packet, int capacity) {
        while (queue.size() >= capacity) {
            if (queue.poll() == null) break;
            diagnostics.schedulerRejected();
        }
        queue.add(packet);
    }

    private static ArrayList<PacketEnvelope> drain(ConcurrentLinkedQueue<PacketEnvelope> queue) {
        ArrayList<PacketEnvelope> all = new ArrayList<>(queue);
        queue.clear();
        return all;
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class UdpView {
        final int sourcePort;
        final int destinationPort;
        final int payloadLength;

        UdpView(int sourcePort, int destinationPort, int payloadLength) {
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.payloadLength = payloadLength;
        }

        static UdpView parse(PacketEnvelope packet, PacketMetadata metadata) {
            int offset = metadata.transportOffset();
            if (offset < 0 || offset + 8 > packet.length()) return null;
            byte[] data = packet.data();
            int udpLength = u16(data, offset + 4);
            if (udpLength < 8 || offset + udpLength > packet.length()) return null;
            int source = u16(data, offset);
            int destination = u16(data, offset + 2);
            return new UdpView(source, destination, udpLength - 8);
        }

        private static int u16(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        }
    }
}
