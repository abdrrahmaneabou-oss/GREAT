package com.great.app.core;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Regression checks for the verified GREAT engine audit findings. */
public final class FreezeCoreAuditRegressionTest {
    private static final class Fixture implements AutoCloseable {
        final CapabilityController controller = new CapabilityController();
        final FreezeCore core = new FreezeCore(controller, new EngineDiagnostics(), new Random(13));
        Fixture() { controller.setListener(core::onCapabilityChanged); }
        PacketDecision decide() {
            PacketEnvelope packet = TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1);
            return core.decide(TestPackets.context(packet), controller.snapshot());
        }
        @Override public void close() { core.close(); }
    }

    @Test public void rampOffCancelsAnActiveRampImmediately() {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(5000, 5000, 0, 0, 0, 0);
            f.controller.set(Capability.FREEZE, true);
            assertEquals(PacketDecision.PASS, f.decide());
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            assertEquals(PacketDecision.HOLD, f.decide());
        }
    }

    @Test public void visualArmingCannotCancelPeriodicHold() {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setPeriodicHoldTrigger(true);
            f.core.setHoldTrigger(true);
            f.core.setHoldTrigger(false);
            assertTrue(f.core.effectiveActive());
            assertEquals(PacketDecision.HOLD, f.decide());
            f.core.setPeriodicHoldTrigger(false);
            assertFalse(f.core.effectiveActive());
        }
    }

    @Test public void deadlineExpiresWithoutAnyUiHandler() throws Exception {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setHoldTrigger(true);
            f.core.setVisualFreezeDeadlineMillis(20);
            assertEquals(PacketDecision.HOLD, f.decide());
            Thread.sleep(45);
            assertEquals(PacketDecision.PASS, f.decide());
            assertFalse(f.core.holdTriggerActive());
        }
    }

    @Test public void fixedPayloadRangeChangesTheActiveCycleWithoutWaiting() {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setHoldTrigger(true);
            assertEquals(PacketDecision.HOLD, f.decide());
            f.core.setPayloadRange(200, 201);
            assertEquals(PacketDecision.PASS, f.decide());
            assertEquals(200, f.core.payloadMin());
        }
    }
    @Test public void previousReleaseWaitsUntilNextFreezeEnds() throws Exception {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setReleaseDropEnabled(false);
            f.core.setReleaseBurstEnabled(false);
            f.core.setReleaseDelayEnabled(false);
            java.util.concurrent.CountDownLatch firstWrite =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch continueWrite =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch secondWrite =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger emitted =
                    new java.util.concurrent.atomic.AtomicInteger();
            f.core.attach(packet -> {
                int number = emitted.incrementAndGet();
                if (number == 1) {
                    firstWrite.countDown();
                    if (!continueWrite.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("Blocked emission not released");
                    }
                }
                if (number == 2) secondWrite.countDown();
            });
            f.core.setHoldTrigger(true);
            assertEquals(PacketDecision.HOLD, f.decide());
            assertEquals(PacketDecision.HOLD, f.decide());
            f.core.setHoldTrigger(false);
            assertTrue(firstWrite.await(2, java.util.concurrent.TimeUnit.SECONDS));
            f.core.setHoldTrigger(true);
            continueWrite.countDown();
            Thread.sleep(60);
            assertEquals("Old packet emitted while new freeze is active", 1, emitted.get());
            f.core.setHoldTrigger(false);
            assertTrue(secondWrite.await(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }


    @Test public void shorteningTimeoutMidCycleExpiresFromOriginalStart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setHoldTrigger(true);
            f.core.setVisualFreezeDeadlineMillis(1000);
            Thread.sleep(60);
            f.core.setVisualFreezeDeadlineMillis(20);
            assertEquals(PacketDecision.PASS, f.decide());
            assertFalse(f.core.holdTriggerActive());
        }
    }

    @Test public void extendingTimeoutDoesNotRestartTheClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.core.setHoldTrigger(true);
            f.core.setVisualFreezeDeadlineMillis(100);
            Thread.sleep(50);
            f.core.setVisualFreezeDeadlineMillis(140);
            assertEquals(PacketDecision.HOLD, f.decide());
            Thread.sleep(115);
            assertEquals(PacketDecision.PASS, f.decide());
        }
    }

    @Test public void historyMarksMixedPayloadRangesInsteadOfClaimingOneRange() throws Exception {
        try (Fixture f = new Fixture()) {
            java.util.concurrent.CountDownLatch completed =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<FreezeCore.CycleStats> recorded =
                    new java.util.concurrent.atomic.AtomicReference<>();
            f.core.setCycleListener(stats -> { recorded.set(stats); completed.countDown(); });
            f.core.setPayloadRange(20, 50);
            f.core.setHoldTrigger(true);
            f.core.setPayloadRange(120, 150);
            f.core.setHoldTrigger(false);
            assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(20, recorded.get().payloadMin());
            assertEquals(50, recorded.get().payloadMax());
            assertTrue(recorded.get().payloadRangeChanged());
        }
    }

    @Test public void changingRampHoldAndDurationUpdatesCurrentFreeze() {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(5000, 5000, 0, 0, 0, 0);
            f.controller.set(Capability.FREEZE, true);
            assertEquals(PacketDecision.PASS, f.decide());
            f.core.setRampProfile(120, 120, 100, 100, 100, 100);
            assertEquals(PacketDecision.HOLD, f.decide());
        }
    }

    @Test public void enablingRampMidCycleActuallyStartsIt() {
        try (Fixture f = new Fixture()) {
            f.core.setRampProfile(0, 0, 100, 100, 100, 100);
            f.controller.set(Capability.FREEZE, true);
            assertEquals(PacketDecision.HOLD, f.decide());
            f.core.setRampProfile(5000, 5000, 0, 0, 0, 0);
            assertEquals(PacketDecision.PASS, f.decide());
        }
    }
}
