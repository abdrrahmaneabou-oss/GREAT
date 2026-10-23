package com.great.app.monitor;

/**
 * The MediaProjection stream includes GREAT's own overlay.
 * Keep the entire RGB probe footprint inside an unpainted opening,
 * independent of resolution, density or the selected sensor diameter.
 */
public final class ProbeReticleGeometry {
    public static final float RETICLE_RADIUS_DP = 8f;
    public static final float RETICLE_STROKE_DP = 1.25f;
    public static final float CLEARANCE_DP = 4f;

    private ProbeReticleGeometry() { }

    public static float radiusPixels(float density, float probeDiameterPx) {
        if (!(density > 0f) || !(probeDiameterPx > 0f)) {
            throw new IllegalArgumentException("Invalid pixel density / probe size");
        }
        return Math.max(RETICLE_RADIUS_DP * density,
                probeDiameterPx / 2f + CLEARANCE_DP * density);
    }

    public static boolean probeUnpainted(float density, float probeDiameterPx) {
        return radiusPixels(density, probeDiameterPx)
                - RETICLE_STROKE_DP * density / 2f > probeDiameterPx / 2f;
    }
}
