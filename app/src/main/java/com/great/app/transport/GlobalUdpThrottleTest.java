package com.great.app.transport;

import com.great.app.core.PacketDecision;
import com.great.app.core.PacketDirection;
import com.great.app.core.PacketEnvelope;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent diagnostic throttle for proving that GREAT can delay global OUTBOUND UDP traffic.
 *
 * Scope is deliberately global: every OUTBOUND IPv4 packet whose IP protocol byte is UDP is
 * delayed by exactly 100 ms while enabled. It does not consult target packages, connection owner,
 * ports, payload range, Freeze state, Robot state, or the IPv4 fragmentation flags. Fragments are
 * therefore delayed too when their IPv4 protocol field is UDP.
 */
public final class GlobalUdpThrottleTest {
    public interface OutputSink { void emit(PacketEnvelope packet) throws Exception; }

    public static final int DELAY_MS = 100;
    private static final GlobalUdpThrottleTest INSTANCE = new GlobalUdpThrottleTest();

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GREAT-Global-UDP-Test-Throttle");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();

    private boolean enabled;
    private OutputSink sink;
    private long transportGeneration;

    private GlobalUdpThrottleTest() { }

    public static GlobalUdpThrottleTest instance() { return INSTANCE; }

    public void setEnabled(boolean enabled) {
        synchronized (lock) { this.enabled = enabled; }
    }

    public boolean enabled() {
        synchronized (lock) { return enabled; }
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
            }
        }
    }

    /** Called before GREAT's target-app Freeze policy. */
    public PacketDecision decide(PacketEnvelope packet) {
        if (packet.direction() != PacketDirection.OUTBOUND || !isIpv4Udp(packet)) {
            return PacketDecision.PASS;
        }
        seen.incrementAndGet();

        final OutputSink output;
        final long generation;
        synchronized (lock) {
            if (!enabled || sink == null) return PacketDecision.PASS;
            output = sink;
            generation = transportGeneration;
        }

        byte[] copy = new byte[packet.length()];
        System.arraycopy(packet.data(), 0, copy, 0, copy.length);
        PacketEnvelope held = new PacketEnvelope(copy, copy.length,
                PacketDirection.OUTBOUND, packet.monotonicNanos());
        delayed.incrementAndGet();
        scheduler.schedule(() -> emitIfCurrent(output, held, generation), DELAY_MS, TimeUnit.MILLISECONDS);
        return PacketDecision.HOLD;
    }

    private void emitIfCurrent(OutputSink output, PacketEnvelope packet, long generation) {
        synchronized (lock) {
            if (sink != output || transportGeneration != generation) return;
        }
        try { output.emit(packet); } catch (Exception ignored) { }
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
