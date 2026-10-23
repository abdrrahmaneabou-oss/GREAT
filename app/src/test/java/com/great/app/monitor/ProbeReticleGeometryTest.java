package com.great.app.monitor;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbeReticleGeometryTest {
    @Test public void originalPhysicalDiameterIsNeverEnlarged() {
        float[] densities = {0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f};
        float[] diameters = {0.8f, 1.5f, 2f, 4f, 5.5f, 9f, 13f, 24f};
        for (float density : densities) {
            for (float diameter : diameters) {
                float paintedOuterRadius = ProbeReticleGeometry.radiusPixels(density, diameter)
                        + ProbeReticleGeometry.strokePixels(diameter) / 2f;
                assertEquals("Visible marker must not grow beyond 0.30mm",
                        diameter / 2f, paintedOuterRadius, 0.001f);
            }
        }
    }

    @Test public void tinyMarkerUsesOnlyOneUnpaintedCenterProbe() {
        assertEquals(0, ProbeReticleGeometry.probeOffsetPixels(2f));
        assertEquals(1, ProbeReticleGeometry.probePoints(17, 23, 2f).length);
        assertArrayEquals(new int[]{17, 23}, ProbeReticleGeometry.probePoints(17, 23, 2f)[0]);
    }

    @Test public void largerPixelRepresentationAddsOnlyInteriorProbes() {
        assertEquals(1, ProbeReticleGeometry.probeOffsetPixels(6f));
        int[][] points = ProbeReticleGeometry.probePoints(17, 23, 6f);
        assertEquals(5, points.length);
        assertArrayEquals(new int[]{17, 23}, points[0]);
        assertArrayEquals(new int[]{16, 23}, points[1]);
        assertArrayEquals(new int[]{18, 23}, points[2]);
        assertArrayEquals(new int[]{17, 22}, points[3]);
        assertArrayEquals(new int[]{17, 24}, points[4]);
        float safeRadius = 6f / 2f - ProbeReticleGeometry.strokePixels(6f);
        assertTrue(safeRadius > 1.5f);
    }

    @Test public void invalidSizesNeverProduceAnOutsideProbe() {
        assertEquals(0, ProbeReticleGeometry.probeOffsetPixels(0f));
        assertEquals(0, ProbeReticleGeometry.probeOffsetPixels(Float.NaN));
        assertEquals(1, ProbeReticleGeometry.probePoints(5, 6, Float.NaN).length);
    }
}
