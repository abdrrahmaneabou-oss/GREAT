package com.great.app.core;

import org.junit.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FreezeCoreReleaseTuningTest {
    @Test public void disablingDropBurstAndDelayReleasesEveryHeldPacket() throws Exception {
        CapabilityController state = new CapabilityController();
        EngineDiagnostics diagnostics = new EngineDiagnostics();
        try (FreezeCore core = new FreezeCore(state, diagnostics, new Random(11))) {
            state.setListener(core::onCapabilityChanged);
            core.setFreezeDurationSeconds(10);
            core.setReleaseDropEnabled(false);
            core.setReleaseBurstEnabled(false);
            core.setReleaseDelayEnabled(false);
            CountDownLatch released = new CountDownLatch(8);
            core.attach(packet -> released.countDown());

            state.set(Capability.FREEZE, true);
            Thread.sleep(150);
            for (int i = 0; i < 8; i++) {
                PacketEnvelope packet = TestPackets.udp(PacketDirection.INBOUND, 443, 100, i);
                assertEquals(PacketDecision.HOLD, core.decide(TestPackets.context(packet), state.snapshot()));
            }
            state.set(Capability.FREEZE, false);
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertEquals(8, diagnostics.snapshot().released());
        }
    }

    @Test public void acceptedReleaseRangeBoundariesMatchUiContract() {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics(), new Random(12))) {
            core.setReleaseDropRange(0, 100);
            core.setReleaseBurstRange(1, 100);
            core.setReleaseDelayRangeMs(0, 5000);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBurstZero() {
        CapabilityController state = new CapabilityController();
        try (FreezeCore core = new FreezeCore(state, new EngineDiagnostics(), new Random(13))) {
            core.setReleaseBurstRange(0, 4);
        }
    }
}
