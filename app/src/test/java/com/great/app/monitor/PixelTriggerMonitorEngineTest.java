package com.great.app.monitor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class PixelTriggerMonitorEngineTest {
    private static PixelTriggerMonitorEngine.Sample sample(int... rgb) {
        return new PixelTriggerMonitorEngine.Sample(rgb, rgb.length);
    }

    @Test public void whiteClassifierMatchesPixelTriggerThresholds() {
        assertTrue(PixelTriggerMonitorEngine.isPackedWhite(0x00ffffff));
        assertTrue(PixelTriggerMonitorEngine.isPackedWhite(0x00d0d0d0));
        assertFalse(PixelTriggerMonitorEngine.isPackedWhite(0x00a9ffff));
        assertFalse(PixelTriggerMonitorEngine.isPackedWhite(0x00ffaaaa));
        assertFalse(PixelTriggerMonitorEngine.isPackedWhite(0x00b0b0b0));
    }

    @Test public void threeOfFiveWhiteProbesQualifyForArming() {
        PixelTriggerMonitorEngine.Sample s = sample(
                0xffffff, 0xffffff, 0xffffff, 0x202020, 0x202020);
        assertEquals(0.60f, s.whiteRatio(), 0.001f);
        assertTrue(s.isArmingWhite());
    }

    @Test public void twoOfFiveWhiteProbesDoNotArm() {
        PixelTriggerMonitorEngine.Sample s = sample(
                0xffffff, 0xffffff, 0x202020, 0x202020, 0x202020);
        assertEquals(0.40f, s.whiteRatio(), 0.001f);
        assertFalse(s.isArmingWhite());
    }

    @Test public void requiresThreeConsecutiveWhiteFramesToArm() {
        List<PixelTriggerMonitorEngine.State> states = new ArrayList<>();
        PixelTriggerMonitorEngine engine = new PixelTriggerMonitorEngine(states::add);
        PixelTriggerMonitorEngine.Sample white = sample(0xffffff, 0xffffff, 0xffffff, 0xffffff, 0xffffff);
        engine.process(white);
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.WAITING_FOR_WHITE, engine.state());
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.ARMED, engine.state());
        assertEquals(List.of(PixelTriggerMonitorEngine.State.ARMED), states);
    }

    @Test public void missingWhiteImmediatelyEntersFiredState() {
        List<PixelTriggerMonitorEngine.State> states = new ArrayList<>();
        PixelTriggerMonitorEngine engine = new PixelTriggerMonitorEngine(states::add);
        PixelTriggerMonitorEngine.Sample dark = sample(0x101010, 0x101010, 0x101010, 0x101010, 0x101010);
        engine.process(dark);
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
        assertEquals(List.of(PixelTriggerMonitorEngine.State.FIRED), states);
    }

    @Test public void fireRequiresProbeDepartureAndDarkAverageLuminance() {
        PixelTriggerMonitorEngine engine = new PixelTriggerMonitorEngine(null);
        PixelTriggerMonitorEngine.Sample white = sample(0xffffff, 0xffffff, 0xffffff, 0xffffff, 0xffffff);
        engine.process(white); engine.process(white); engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.ARMED, engine.state());

        engine.process(sample(0x101010, 0x101010, 0xffffff, 0xffffff, 0xffffff));
        assertEquals(PixelTriggerMonitorEngine.State.ARMED, engine.state());

        engine.process(sample(0x101010, 0x101010, 0x101010, 0x101010, 0xffffff));
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
    }

    @Test public void firedStateStaysUntilThreeWhiteFramesReturn() {
        PixelTriggerMonitorEngine engine = new PixelTriggerMonitorEngine(null);
        PixelTriggerMonitorEngine.Sample white = sample(0xffffff, 0xffffff, 0xffffff, 0xffffff, 0xffffff);
        PixelTriggerMonitorEngine.Sample dark = sample(0x101010, 0x101010, 0x101010, 0x101010, 0x101010);
        engine.process(white); engine.process(white); engine.process(white);
        engine.process(dark);
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
        engine.process(white);
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.ARMED, engine.state());
    }

    @Test public void whiteAfterInitialFiredStateRearmsAfterThreeFrames() {
        PixelTriggerMonitorEngine engine = new PixelTriggerMonitorEngine(null);
        PixelTriggerMonitorEngine.Sample white = sample(0xffffff, 0xffffff, 0xffffff, 0xffffff, 0xffffff);
        PixelTriggerMonitorEngine.Sample dark = sample(0x101010, 0x101010, 0x101010, 0x101010, 0x101010);
        engine.process(dark);
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
        engine.process(white);
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.FIRED, engine.state());
        engine.process(white);
        assertEquals(PixelTriggerMonitorEngine.State.ARMED, engine.state());
    }
}
