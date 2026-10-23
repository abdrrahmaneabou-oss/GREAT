package com.great.app.monitor;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbeReticleGeometryTest {
    @Test public void markerNeverCoversCenterOrAnyOfFiveProbes() {
        float[] densities = {0.75f, 1f, 1.5f, 2f, 3f, 4f, 6f};
        float[] diameters = {0.8f, 1.5f, 2f, 4f, 9f, 13f, 24f};
        for (float density : densities) {
            for (float diameter : diameters) {
                assertTrue("Probe occluded density=" + density + " diameter=" + diameter,
                        ProbeReticleGeometry.probeUnpainted(density, diameter));
                assertTrue(ProbeReticleGeometry.radiusPixels(density, diameter)
                        >= diameter / 2f + density * 4f);
            }
        }
    }
}
