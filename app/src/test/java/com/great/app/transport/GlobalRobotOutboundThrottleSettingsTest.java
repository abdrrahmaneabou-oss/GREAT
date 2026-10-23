package com.great.app.transport;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Guard the ON/OFF semantics of independent throttle cards and live switches. */
public final class GlobalRobotOutboundThrottleSettingsTest {
    private static Random highRolls() {
        return new Random(1) {
            @Override public int nextInt(int bound) { return bound - 1; }
        };
    }

    private GlobalRobotOutboundThrottle configured() {
        GlobalRobotOutboundThrottle engine =
                GlobalRobotOutboundThrottle.forTests(highRolls());
        engine.attach(packet -> { });
        engine.setFeatureEnabled(true);
        engine.setLightEnabled(true);
        engine.setLightRange(1, 1, 25, 25);
        return engine;
    }

    @Test public void disablingLightStopsActiveLightImmediately() {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightStopOnWhiteReturn(true);
        assertTrue(engine.startLightForVisualFreeze());
        assertTrue(engine.lightActive());
        engine.setLightEnabled(false);
        assertFalse(engine.lightActive());
    }

    @Test public void disablingWhiteLinkDuringCycleUsesOriginalDuration() throws Exception {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightStopOnWhiteReturn(true);
        assertTrue(engine.startLightForVisualFreeze());
        Thread.sleep(55);
        assertTrue("White-linked light has no duration timeout", engine.lightActive());
        engine.setLightStopOnWhiteReturn(false);
        assertFalse("The saved duration already passed", engine.lightActive());
    }

    @Test public void enablingWhiteLinkInCurrentVisualCycleStopsDurationExpiry()
            throws Exception {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightStopOnWhiteReturn(false);
        assertTrue(engine.startLightForVisualFreeze());
        engine.setLightStopOnWhiteReturn(true);
        Thread.sleep(55);
        assertTrue(engine.lightActive());
        engine.finishVisualFreezeAndStartPostThrottle();
        assertFalse(engine.lightActive());
    }

    @Test public void disablingVisualPostNeverCancelsIndependentPeriodicPost() {
        GlobalRobotOutboundThrottle engine = configured();
        assertTrue(engine.finishPeriodicFreezeAndStartPostThrottle());
        assertTrue(engine.active());
        engine.setVisualPostEnabled(false);
        assertTrue("Periodic post remains active when visual card is OFF", engine.active());
        engine.setFeatureEnabled(false);
        assertFalse(engine.active());
    }

    @Test public void disabledVisualPostRefusesVisualTrigger() {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setVisualPostEnabled(false);
        assertFalse(engine.finishVisualFreezeAndStartPostThrottle());
    }

    @Test public void unchangedExactRangesStayValid() {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightRange(120, 120, 250, 250);
        engine.setVisualPostRange(90, 90, 500, 500);
        assertTrue(GlobalRobotOutboundThrottle.validLightRange(120, 120, 250, 250));
        assertTrue(GlobalRobotOutboundThrottle.validVisualPostRange(90, 90, 500, 500));
    }

    private static com.great.app.core.PacketEnvelope outboundUdp() {
        byte[] data = new byte[28];
        data[0] = 0x45;
        data[9] = 17;
        return new com.great.app.core.PacketEnvelope(data, data.length,
                com.great.app.core.PacketDirection.OUTBOUND, System.nanoTime());
    }

    @Test public void switchingLightOffFlushesHeldPacketWithoutDuplicate() throws Exception {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightRange(300, 300, 500, 500);
        engine.setLightStopOnWhiteReturn(true);
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        engine.attach(packet -> { count.incrementAndGet(); delivered.countDown(); });
        assertTrue(engine.startLightForVisualFreeze());
        assertEquals(com.great.app.core.PacketDecision.HOLD, engine.decide(outboundUdp()));
        engine.setLightEnabled(false);
        assertTrue("OFF must flush the queued packet", delivered.await(150,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        Thread.sleep(330);
        assertEquals("The canceled old timer must never emit again", 1, count.get());
    }

    @Test public void disablingWhiteLinkFlushesExpiredDurationPacket() throws Exception {
        GlobalRobotOutboundThrottle engine = configured();
        engine.setLightRange(300, 300, 25, 25);
        engine.setLightStopOnWhiteReturn(true);
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        engine.attach(packet -> delivered.countDown());
        assertTrue(engine.startLightForVisualFreeze());
        assertEquals(com.great.app.core.PacketDecision.HOLD, engine.decide(outboundUdp()));
        Thread.sleep(45);
        engine.setLightStopOnWhiteReturn(false);
        assertTrue(delivered.await(150, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(engine.lightActive());
    }

    @Test public void periodicPendingPacketNotFlushedByVisualPostOff() throws Exception {
        GlobalRobotOutboundThrottle engine = configured();
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        engine.attach(packet -> delivered.countDown());
        assertTrue(engine.finishPeriodicFreezeAndStartPostThrottle());
        assertEquals(com.great.app.core.PacketDecision.HOLD, engine.decide(outboundUdp()));
        engine.setVisualPostEnabled(false);
        assertFalse("Independent periodic post delay must remain", delivered.await(20,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        assertTrue(delivered.await(400, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
