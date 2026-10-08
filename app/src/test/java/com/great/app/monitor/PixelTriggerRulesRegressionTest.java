package com.great.app.monitor;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.great.app.monitor.PixelTriggerMonitorEngine.State.*;

public final class PixelTriggerRulesRegressionTest {
    private static PixelSample gray(int v) { return new PixelSample((v << 16) | (v << 8) | v); }
    private static PixelTriggerMonitorEngine armed() {
        PixelTriggerMonitorEngine d = new PixelTriggerMonitorEngine();
        for (int i = 0; i < 3; i++) d.process(gray(255));
        assertEquals(ARMED, d.state()); return d;
    }
    @Test public void exactRgbWhiteBoundaries() {
        assertTrue(PixelSample.isWhite(0xbebebe)); // luma 190
        assertFalse(PixelSample.isWhite(0xbdbdbd));
        assertTrue(PixelSample.isWhite(0xe6d2aa)); // min 170, chroma 60
        assertFalse(PixelSample.isWhite(0xe7d2aa)); // chroma 61
        assertFalse(PixelSample.isWhite(0xe5d2a9)); // min 169
        assertFalse(PixelSample.isWhite(0xffe080)); // yellow is NOT the right detector's extra ON rule
    }
    @Test public void coverageUsesFloatingPointAndIncludesHalf() {
        assertTrue(new PixelSample(0xffffff, 0).armingWhite());
        assertFalse(new PixelSample(0xffffff, 0xffffff, 0, 0, 0).armingWhite());
        assertTrue(new PixelSample(0xffffff, 0xffffff, 0xffffff, 0, 0).armingWhite());
    }
    @Test public void startupDarkDoesNotFireBeforeArming() {
        PixelTriggerMonitorEngine d = new PixelTriggerMonitorEngine();
        for (int i = 0; i < 10; i++) assertEquals(WAITING_FOR_WHITE, d.process(gray(0)));
        assertFalse(d.triggered());
    }
    @Test public void threeConsecutiveWhiteFramesRequired() {
        PixelTriggerMonitorEngine d = new PixelTriggerMonitorEngine();
        assertEquals(WAITING_FOR_WHITE, d.process(gray(255)));
        assertEquals(WAITING_FOR_WHITE, d.process(gray(255)));
        d.process(gray(0));
        d.process(gray(255)); d.process(gray(255));
        assertEquals(WAITING_FOR_WHITE, d.state());
        assertEquals(ARMED, d.process(gray(255)));
    }
    @Test public void oneDarkDepartureFiresAtInclusiveLuma90() {
        PixelTriggerMonitorEngine d = armed();
        assertEquals(ARMED, d.process(gray(91)));
        assertEquals(FIRED, d.process(gray(90)));
        assertTrue(d.triggered());
    }
    @Test public void brightNonWhiteDoesNotFire() {
        PixelTriggerMonitorEngine d = armed();
        assertEquals(ARMED, d.process(new PixelSample(0xffee00)));
        assertEquals(ARMED, d.process(gray(150)));
    }
    @Test public void holdsUntilThreeWhiteFramesReturnAndInterruptedRearmRestarts() {
        PixelTriggerMonitorEngine d = armed();
        d.process(gray(0));
        for (int i = 0; i < 20; i++) assertEquals(FIRED, d.process(gray(0)));
        d.process(gray(255)); d.process(gray(255));
        assertTrue(d.triggered());
        d.process(gray(0));
        d.process(gray(255)); d.process(gray(255));
        assertEquals(FIRED, d.state());
        assertEquals(ARMED, d.process(gray(255)));
        assertFalse(d.triggered());
    }
    @Test public void changedProbeCountRestartsWhiteSequence() {
        PixelTriggerMonitorEngine d = new PixelTriggerMonitorEngine();
        d.process(gray(255)); d.process(gray(255));
        PixelSample three = new PixelSample(0xffffff, 0xffffff, 0xffffff);
        assertEquals(WAITING_FOR_WHITE, d.process(three));
        assertEquals(WAITING_FOR_WHITE, d.process(three));
        assertEquals(ARMED, d.process(three));
    }
    @Test public void exactProbeChangeThresholdAndQuorum() {
        assertFalse(PixelSample.probeChanged(0x646464, 0x756464)); // delta 17, brighter
        assertTrue(PixelSample.probeChanged(0x646464, 0x766464));
        assertFalse(new PixelSample(0, 0xffffff, 0xffffff).departedFrom(new int[]{0xffffff,0xffffff,0xffffff}));
        assertTrue(new PixelSample(0, 0, 0xffffff).departedFrom(new int[]{0xffffff,0xffffff,0xffffff}));
        int[] baseline = {0xffffff,0xffffff,0xffffff,0xffffff,0xffffff};
        assertFalse(new PixelSample(0,0,0xffffff,0xffffff,0xffffff).departedFrom(baseline));
        assertTrue(new PixelSample(0,0,0,0xffffff,0xffffff).departedFrom(baseline));
        assertTrue(new PixelSample(0,0xffffff).departedFrom(new int[]{0xffffff,0xffffff}));
    }
    @Test public void baselineIsThreeFrameChannelAverageNotLastFrame() {
        PixelTriggerMonitorEngine d = new PixelTriggerMonitorEngine();
        d.process(new PixelSample(0xbebebe,0xbebebe,0xbebebe,0,0));
        d.process(new PixelSample(0xbebebe,0xbebebe,0,0xbebebe,0));
        d.process(new PixelSample(0xbebebe,0xbebebe,0,0,0xbebebe));
        // Baseline = [190,190,63,63,63]. Only two probes changed (quorum=3).
        // Comparing with the last frame instead would incorrectly fire on this dark sample.
        assertEquals(ARMED, d.process(new PixelSample(0x5f5f5f,0x5f5f5f,0x3f3f3f,0x3f3f3f,0x3f3f3f)));
        assertEquals(FIRED, d.process(new PixelSample(0x5f5f5f,0x5f5f5f,0x2d2d2d,0x3f3f3f,0x3f3f3f)));
    }
    @Test public void invalidFrameIsNotDarkAndResetRequiresFreshArming() {
        PixelTriggerMonitorEngine d = armed();
        assertEquals(ARMED, d.process((PixelSample) null));
        d.process(gray(0)); d.reset();
        assertFalse(d.triggered());
        assertEquals(WAITING_FOR_WHITE, d.process(gray(0)));
    }
}
