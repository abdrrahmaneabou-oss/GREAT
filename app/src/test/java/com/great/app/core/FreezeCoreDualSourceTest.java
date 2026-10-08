package com.great.app.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class FreezeCoreDualSourceTest {
    @Test public void circleOffDoesNotDisableManualFreeze() {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics())) {
            state.setListener(core::onCapabilityChanged);
            state.set(Capability.FREEZE, true);
            core.setHoldTrigger(true);
            core.setHoldTrigger(false);

            assertTrue(state.snapshot().enabled(Capability.FREEZE));
            assertTrue(core.effectiveActive());
        }
    }

    @Test public void manualOffDoesNotReleaseWhileCircleStillHeld() throws Exception {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics())) {
            state.setListener(core::onCapabilityChanged);
            CountDownLatch released = new CountDownLatch(1);
            core.attach(packet -> released.countDown());

            state.set(Capability.FREEZE, true);
            core.setHoldTrigger(true);
            assertEquals(PacketDecision.HOLD,
                    core.decide(TestPackets.context(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1)),
                            state.snapshot()));

            state.set(Capability.FREEZE, false);
            assertTrue(core.effectiveActive());
            assertFalse(released.await(100, TimeUnit.MILLISECONDS));

            core.setHoldTrigger(false);
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertFalse(core.effectiveActive());
        }
    }

    @Test public void circleAloneCanHoldAndReleasePackets() throws Exception {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics())) {
            state.setListener(core::onCapabilityChanged);
            CountDownLatch released = new CountDownLatch(1);
            core.attach(packet -> released.countDown());

            core.setHoldTrigger(true);
            assertFalse(state.snapshot().enabled(Capability.FREEZE));
            assertTrue(core.effectiveActive());
            assertEquals(PacketDecision.HOLD,
                    core.decide(TestPackets.context(TestPackets.udp(PacketDirection.INBOUND, 443, 100, 1)),
                            state.snapshot()));

            core.setHoldTrigger(false);
            assertTrue(released.await(2, TimeUnit.SECONDS));
        }
    }
}
