package com.great.app.core;

import org.junit.Test;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class FreezeCoreTest {
    private static final class Harness implements AutoCloseable {
        final CapabilityController state = new CapabilityController();
        final EngineDiagnostics diagnostics = new EngineDiagnostics();
        final FreezeCore core = new FreezeCore(state, diagnostics, new Random(123));
        Harness() { state.setListener(core::onCapabilityChanged); core.setFreezeDurationSeconds(10); }
        PacketDecision decide(PacketEnvelope p) { return core.decide(TestPackets.context(p), state.snapshot()); }
        public void close() { core.close(); }
    }
    @Test public void onlyInboundIpv4UdpIsHeld() {
        try (Harness h = new Harness()) {
            PacketEnvelope p = TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1);
            assertEquals(PacketDecision.PASS, h.decide(p));
            h.state.set(Capability.FREEZE, true);
            assertEquals(PacketDecision.HOLD, h.decide(p));
            assertEquals(PacketDecision.PASS, h.decide(TestPackets.udp(PacketDirection.OUTBOUND, 443, 100, 1)));
            p.data()[9] = 6;
            assertEquals(PacketDecision.PASS, h.decide(p));
            p.data()[9] = 17; p.data()[6] = 0x20;
            assertEquals(PacketDecision.PASS, h.decide(p));
        }
    }
    @Test public void inclusiveRemotePortBypass() {
        try (Harness h = new Harness()) {
            h.state.set(Capability.FREEZE, true);
            for (int port : new int[]{7000,8500,10000}) assertEquals(PacketDecision.PASS, h.decide(TestPackets.udp(PacketDirection.INBOUND,port,100,0)));
            for (int port : new int[]{6999,10001}) assertEquals(PacketDecision.HOLD, h.decide(TestPackets.udp(PacketDirection.INBOUND,port,100,0)));
        }
    }
    @Test public void strictRandomSizeThresholds() {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics(), new Random() {
            @Override public int nextInt(int bound) { return 0; }
        })) {
            assertFalse(core.shouldHold(20)); assertTrue(core.shouldHold(21));
            assertTrue(core.shouldHold(449)); assertFalse(core.shouldHold(450));
        }
        try (Harness h = new Harness()) {
            for (int i = 0; i < 100; i++) {
                assertFalse(h.core.shouldHold(20)); assertTrue(h.core.shouldHold(30));
                assertTrue(h.core.shouldHold(449)); assertFalse(h.core.shouldHold(499));
            }
        }
    }
    @Test public void queueCapsAt10000AndEvictsOldestOwnedCopy() throws Exception {
        try (Harness h = new Harness()) {
            h.state.set(Capability.FREEZE, true);
            for (int i = 0; i <= FreezeCore.CAPACITY; i++) assertEquals(PacketDecision.HOLD,
                    h.decide(TestPackets.udp(PacketDirection.INBOUND, 443, 100, i)));
            assertEquals(FreezeCore.CAPACITY, h.core.freezeQueueSize());
            assertEquals(1, h.diagnostics.snapshot().schedulerRejected());
            List<Integer> first = new ArrayList<>();
            CountDownLatch emitted = new CountDownLatch(1);
            h.core.attach(p -> { first.add(p.data()[28] & 255); emitted.countDown(); throw new Exception("stop after first"); });
            h.state.set(Capability.FREEZE, false);
            assertTrue(emitted.await(2, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(1), first.get(0));
        }
    }
    @Test public void manualOffReleasesOwnedPacketsInOrder() throws Exception {
        try (Harness h = new Harness()) {
            List<Integer> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(4);
            h.core.attach(p -> { emitted.add(p.data()[28] & 255); done.countDown(); });
            h.state.set(Capability.FREEZE, true);
            for (int i = 0; i < 4; i++) {
                PacketEnvelope p = TestPackets.udp(PacketDirection.INBOUND,443,100,i);
                h.decide(p); p.data()[28] = 99;
            }
            h.state.set(Capability.FREEZE, false);
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList(0,1,2,3), emitted);
            assertEquals(0, h.core.freezeQueueSize());
        }
    }
    @Test public void timerDisablesAndReleases() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch done = new CountDownLatch(1);
            h.core.attach(p -> done.countDown()); h.core.setFreezeDurationSeconds(1);
            h.state.set(Capability.FREEZE, true); h.decide(TestPackets.udp(PacketDirection.INBOUND,443,100,0));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(h.state.snapshot().enabled(Capability.FREEZE));
        }
    }
    @Test public void resetDropsHeldPackets() {
        try (Harness h = new Harness()) {
            h.state.set(Capability.FREEZE, true); h.decide(TestPackets.udp(PacketDirection.INBOUND,443,100,0));
            h.core.reset(); h.state.reset();
            assertEquals(0,h.core.freezeQueueSize());
            assertEquals(PacketDecision.PASS,h.decide(TestPackets.udp(PacketDirection.INBOUND,443,100,0)));
        }
    }
    @Test public void policyNeverHoldsUnselectedTraffic() {
        try (Harness h = new Harness()) {
            h.state.set(Capability.FREEZE,true);
            ConfigurablePacketSelector targets = new ConfigurablePacketSelector();
            PacketPipeline pipeline = new PacketPipeline(new FreezePolicy(targets,h.core),h.state);
            PacketEnvelope packet = TestPackets.udp(PacketDirection.INBOUND,443,100,0);
            assertEquals(PacketDecision.PASS,pipeline.evaluate(packet));
            targets.set(PacketSelector.all()); assertEquals(PacketDecision.HOLD,pipeline.evaluate(packet));
            targets.clear(); assertEquals(PacketDecision.PASS,pipeline.evaluate(packet));
        }
    }
}
