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
}
