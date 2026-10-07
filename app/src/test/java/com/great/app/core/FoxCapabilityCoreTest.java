package com.great.app.core;

import org.junit.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FoxCapabilityCoreTest {
    @Test public void ghostMatchesFoxPayloadWindowAndPortBypass() {
        Harness h = new Harness();
        try {
            h.controller.set(Capability.GHOST, true);
            assertEquals(PacketDecision.DROP, h.decide(packet(PacketDirection.OUTBOUND, 40000, 443, 100)));
            assertEquals(PacketDecision.PASS, h.decide(packet(PacketDirection.OUTBOUND, 40000, 443, 48)));
            assertEquals(PacketDecision.PASS, h.decide(packet(PacketDirection.OUTBOUND, 40000, 8500, 100)));
            assertEquals(PacketDecision.PASS, h.decide(packet(PacketDirection.OUTBOUND, 40000, 443, 201)));
        } finally {
            h.close();
        }
    }

    @Test public void freezeHoldsInboundAndManualOffReleases() throws Exception {
        Harness h = new Harness();
        try {
            CountDownLatch released = new CountDownLatch(1);
            AtomicReference<PacketEnvelope> emitted = new AtomicReference<>();
            h.core.attach(packet -> {
                emitted.set(packet);
                released.countDown();
            });

            h.controller.set(Capability.FREEZE, true);
            PacketEnvelope inbound = packet(PacketDirection.INBOUND, 443, 40000, 100);
            assertEquals(PacketDecision.HOLD, h.decide(inbound));
            assertEquals(1, h.core.freezeQueueSize());

            h.controller.set(Capability.FREEZE, false);
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertEquals(PacketDirection.INBOUND, emitted.get().direction());
            assertEquals(0, h.core.freezeQueueSize());
        } finally {
            h.close();
        }
    }

    @Test public void freezeBypassesLegacyRemotePortWindow() {
        Harness h = new Harness();
        try {
            h.controller.set(Capability.FREEZE, true);
            assertEquals(PacketDecision.PASS,
                    h.decide(packet(PacketDirection.INBOUND, 8500, 40000, 100)));
        } finally {
            h.close();
        }
    }

    @Test public void teleportCapturesFoxWindowAndReplaysOnOff() throws Exception {
        Harness h = new Harness();
        try {
            CountDownLatch replayed = new CountDownLatch(1);
            AtomicReference<PacketEnvelope> emitted = new AtomicReference<>();
            h.core.attach(packet -> {
                emitted.set(packet);
                replayed.countDown();
            });

            h.controller.set(Capability.TELEPORT, true);
            assertEquals(PacketDecision.DROP,
                    h.decide(packet(PacketDirection.OUTBOUND, 40000, 10020, 100)));
            assertEquals(1, h.core.teleportQueueSize());

            h.controller.set(Capability.TELEPORT, false);
            assertTrue(replayed.await(2, TimeUnit.SECONDS));
            assertEquals(PacketDirection.OUTBOUND, emitted.get().direction());
        } finally {
            h.close();
        }
    }

    @Test public void durationsAreRestrictedToOneThroughTenSeconds() {
        Harness h = new Harness();
        try {
            h.core.setFreezeDurationSeconds(1);
            h.core.setTeleportDurationSeconds(10);
            assertEquals(1, h.core.freezeDurationSeconds());
            assertEquals(10, h.core.teleportDurationSeconds());
        } finally {
            h.close();
        }
    }

    private static final class Harness implements AutoCloseable {
        final CapabilityController controller = new CapabilityController();
        final EngineDiagnostics diagnostics = new EngineDiagnostics();
        final FoxCapabilityCore core = new FoxCapabilityCore(controller, diagnostics, new Random(1234));
        final PacketParser parser = new PacketParser();

        Harness() {
            controller.setListener(core::onCapabilityChanged);
        }

        PacketDecision decide(PacketEnvelope packet) {
            PacketMetadata metadata = parser.parse(packet);
            return core.decide(new PacketContext(packet, metadata, TrafficClass.UDP), controller.snapshot());
        }

        @Override public void close() {
            core.close();
        }
    }

    private static PacketEnvelope packet(PacketDirection direction, int sourcePort,
                                         int destinationPort, int payloadLength) {
        int ipLength = 20 + 8 + payloadLength;
        byte[] p = new byte[ipLength];
        p[0] = 0x45;
        p[2] = (byte) (ipLength >>> 8);
        p[3] = (byte) ipLength;
        p[8] = 64;
        p[9] = 17;
        p[12] = 10; p[13] = 0; p[14] = 0; p[15] = 1;
        p[16] = 10; p[17] = 0; p[18] = 0; p[19] = 2;
        p[20] = (byte) (sourcePort >>> 8);
        p[21] = (byte) sourcePort;
        p[22] = (byte) (destinationPort >>> 8);
        p[23] = (byte) destinationPort;
        int udpLength = 8 + payloadLength;
        p[24] = (byte) (udpLength >>> 8);
        p[25] = (byte) udpLength;
        for (int i = 28; i < p.length; i++) p[i] = (byte) i;
        return new PacketEnvelope(p, p.length, direction, System.nanoTime());
    }
}
