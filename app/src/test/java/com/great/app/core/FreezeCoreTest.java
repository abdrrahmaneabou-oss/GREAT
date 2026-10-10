package com.great.app.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class FreezeCoreTest {
    private static final class Harness implements AutoCloseable {
        final CapabilityController state = new CapabilityController();
        final EngineDiagnostics diagnostics = new EngineDiagnostics();
        final FreezeCore core = new FreezeCore(state, diagnostics, new Random(123));

        Harness() {
            state.setListener(core::onCapabilityChanged);
            core.setFreezeDurationSeconds(10);
        }

        PacketDecision decide(PacketEnvelope p) {
            return core.decide(TestPackets.context(p), state.snapshot());
        }

        void enableAndSettleRamp() throws InterruptedException {
            state.set(Capability.FREEZE, true);
            Thread.sleep(150);
        }

        @Override public void close() { core.close(); }
    }

    @Test public void onlyInboundIpv4UdpIsHeldAfterRamp() throws Exception {
        try (Harness h = new Harness()) {
            PacketEnvelope p = TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1);
            assertEquals(PacketDecision.PASS, h.decide(p));
            h.enableAndSettleRamp();
            assertEquals(PacketDecision.HOLD, h.decide(p));
            assertEquals(PacketDecision.PASS,
                    h.decide(TestPackets.udp(PacketDirection.OUTBOUND, 443, 100, 1)));
            p.data()[9] = 6;
            assertEquals(PacketDecision.PASS, h.decide(p));
            p.data()[9] = 17;
            p.data()[6] = 0x20;
            assertEquals(PacketDecision.PASS, h.decide(p));
        }
    }

    @Test public void inclusiveRemotePortBypass() throws Exception {
        try (Harness h = new Harness()) {
            h.enableAndSettleRamp();
            for (int port : new int[]{7000, 8500, 10000}) {
                assertEquals(PacketDecision.PASS,
                        h.decide(TestPackets.udp(PacketDirection.INBOUND, port, 100, 0)));
            }
            for (int port : new int[]{6999, 10001}) {
                assertEquals(PacketDecision.HOLD,
                        h.decide(TestPackets.udp(PacketDirection.INBOUND, port, 100, 0)));
            }
        }
    }

    @Test public void inclusiveConfiguredSizeThresholdsAndExpandedLimits() {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics(), new Random(1))) {
            assertTrue(core.shouldHold(20));
            assertTrue(core.shouldHold(500));

            core.setPayloadRange(35, 75);
            assertFalse(core.shouldHold(34));
            assertTrue(core.shouldHold(35));
            assertTrue(core.shouldHold(75));
            assertFalse(core.shouldHold(76));
            assertEquals(35, core.payloadMin());
            assertEquals(75, core.payloadMax());

            core.setPayloadRange(1, 600);
            assertTrue(core.shouldHold(1));
            assertTrue(core.shouldHold(600));
        }
    }

    @Test public void queueCapsAt10000AndEvictsOldestOwnedCopy() throws Exception {
        try (Harness h = new Harness()) {
            h.enableAndSettleRamp();
            for (int i = 0; i <= FreezeCore.CAPACITY; i++) {
                assertEquals(PacketDecision.HOLD,
                        h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, i)));
            }
            assertEquals(FreezeCore.CAPACITY, h.core.freezeQueueSize());
            assertEquals(1, h.diagnostics.snapshot().schedulerRejected());

            CountDownLatch emitted = new CountDownLatch(1);
            h.core.attach(p -> {
                emitted.countDown();
                throw new Exception("stop after first");
            });
            h.state.set(Capability.FREEZE, false);
            assertTrue(emitted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test public void manualOffDropsClusterAndReleasesRemainingPackets() throws Exception {
        try (Harness h = new Harness()) {
            List<Integer> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
            h.core.attach(p -> emitted.add(p.data()[28] & 255));
            h.enableAndSettleRamp();

            for (int i = 0; i < 20; i++) {
                assertEquals(PacketDecision.HOLD,
                        h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, i)));
            }
            h.state.set(Capability.FREEZE, false);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (h.diagnostics.snapshot().released() < 13 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            int released = emitted.size();
            assertTrue("release count=" + released, released >= 13 && released <= 17);
            assertEquals(0, h.core.freezeQueueSize());
        }
    }

    @Test public void completedCycleReportsExactObservedCounts() throws Exception {
        try (Harness h = new Harness()) {
            AtomicReference<FreezeCore.CycleStats> recorded = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            h.core.setCycleListener(stats -> {
                recorded.set(stats);
                completed.countDown();
            });
            h.core.attach(p -> { });
            h.enableAndSettleRamp();

            assertEquals(PacketDecision.PASS,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 7000, 100, 99)));
            for (int i = 0; i < 20; i++) {
                assertEquals(PacketDecision.HOLD,
                        h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, i)));
            }
            h.state.set(Capability.FREEZE, false);

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            FreezeCore.CycleStats stats = recorded.get();
            assertNotNull(stats);
            assertEquals("MANUAL", stats.source());
            assertEquals(21, stats.arrived());
            assertEquals(20, stats.frozen());
            assertTrue(stats.dropped() >= 3 && stats.dropped() <= 7);
            assertEquals(20 - stats.dropped(), stats.released());
            assertEquals(0, stats.evicted());
        }
    }

    @Test public void timerDisablesAndReleases() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch done = new CountDownLatch(1);
            h.core.attach(p -> done.countDown());
            h.core.setFreezeDurationSeconds(1);
            h.state.set(Capability.FREEZE, true);
            Thread.sleep(150);
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(h.state.snapshot().enabled(Capability.FREEZE));
        }
    }

    @Test public void resetCancelsRemainingReleaseWithoutBlockingQueue() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finishWrite = new CountDownLatch(1);
            CountDownLatch firstDone = new CountDownLatch(1);
            AtomicInteger writes = new AtomicInteger();
            h.core.attach(p -> {
                writes.incrementAndGet();
                entered.countDown();
                assertTrue(finishWrite.await(2, TimeUnit.SECONDS));
                firstDone.countDown();
            });

            h.enableAndSettleRamp();
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1)));
            h.state.set(Capability.FREEZE, false);
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            h.core.reset();
            h.state.reset();
            finishWrite.countDown();
            assertTrue(firstDone.await(2, TimeUnit.SECONDS));

            CountDownLatch nextDone = new CountDownLatch(1);
            h.core.attach(p -> nextDone.countDown());
            h.enableAndSettleRamp();
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 2)));
            h.state.set(Capability.FREEZE, false);
            assertTrue(nextDone.await(2, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
        }
    }

    @Test public void resetDropsHeldPackets() throws Exception {
        try (Harness h = new Harness()) {
            h.enableAndSettleRamp();
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
            h.core.reset();
            h.state.reset();
            assertEquals(0, h.core.freezeQueueSize());
            assertEquals(PacketDecision.PASS,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
        }
    }

    @Test public void policyNeverHoldsUnselectedTraffic() throws Exception {
        try (Harness h = new Harness()) {
            h.enableAndSettleRamp();
            ConfigurablePacketSelector targets = new ConfigurablePacketSelector();
            PacketPipeline pipeline = new PacketPipeline(new FreezePolicy(targets, h.core), h.state);
            PacketEnvelope packet = TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0);
            assertEquals(PacketDecision.PASS, pipeline.evaluate(packet));
            targets.set(PacketSelector.all());
            assertEquals(PacketDecision.HOLD, pipeline.evaluate(packet));
            targets.clear();
            assertEquals(PacketDecision.PASS, pipeline.evaluate(packet));
        }
    }

    @Test public void manualReleaseDoesNotStartOutboundThrottleYet() throws Exception {
        try (Harness h = new Harness()) {
            h.core.setOutboundThrottleEnabled(true);
            h.core.attach(p -> { });
            h.enableAndSettleRamp();
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
            h.state.set(Capability.FREEZE, false);
            assertFalse(h.core.outboundThrottleActive());
            assertEquals(PacketDecision.PASS,
                    h.decide(TestPackets.udp(PacketDirection.OUTBOUND, 443, 100, 1)));
        }
    }

    @Test public void robotWhiteReturnStartsAndExpiresOutboundThrottle() throws Exception {
        try (Harness h = new Harness()) {
            h.core.setOutboundThrottleEnabled(true);
            h.core.attach(p -> { });
            h.core.setHoldTrigger(true);
            Thread.sleep(150);
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 0)));
            h.core.startOutboundThrottleFromRobotWhiteReturn();
            h.core.setHoldTrigger(false);
            assertTrue(h.core.outboundThrottleActive());
            assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.OUTBOUND, 443, 100, 1)));
            Thread.sleep(FreezeCore.OUTBOUND_THROTTLE_WINDOW_MS + 60L);
            assertFalse(h.core.outboundThrottleActive());
        }
    }
}
