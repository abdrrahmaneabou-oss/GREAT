package com.great.app.monitor;

/**
 * Geometry of the original 0.30 mm sensor reticle. The rendered outline must
 * NEVER be enlarged to make room for the color probes: only the invisible
 * sampling pattern is adjusted for the number of pixels actually available.
 */
public final class ProbeReticleGeometry {
    // Anti-aliased hairline. Unlike the old >=1dp stroke this cannot fill the
    // small 0.30 mm circle on low-density screens.
    public static final float MAX_STROKE_PX = 0.75f;

    private ProbeReticleGeometry() { }

    /** Width of the drawn ring, in physical output pixels. */
    public static float strokePixels(float diameterPx) {
        if (!(diameterPx > 0f) || !Float.isFinite(diameterPx)) {
            throw new IllegalArgumentException("Invalid sensor diameter");
        }
        return Math.min(MAX_STROKE_PX, Math.max(0.25f, diameterPx * 0.18f));
    }

    /**
     * Canvas Paint.Style.STROKE extends outside the radius by half its width.
     * Subtract that half-width, so OUTER diameter remains exactly 0.30 mm.
     */
    public static float radiusPixels(float density, float diameterPx) {
        if (!(density > 0f) || !Float.isFinite(density)) {
            throw new IllegalArgumentException("Invalid density");
        }
        return Math.max(0f, diameterPx / 2f - strokePixels(diameterPx) / 2f);
    }

    /** At a very small scale the central pixel is the only safe probe. */
    public static int probeOffsetPixels(float imageDiameterPx) {
        if (!(imageDiameterPx > 0f) || !Float.isFinite(imageDiameterPx)) return 0;
        // Both line width and one half of an antialiasing pixel are reserved
        // inside the physical outline. Never sample across the painted ring.
        float safeRadius = imageDiameterPx / 2f
                - strokePixels(imageDiameterPx) - 0.65f;
        return safeRadius >= 1.25f ? 1 : 0;
    }

    public static int[][] probePoints(int centerX, int centerY, float imageDiameterPx) {
        int step = probeOffsetPixels(imageDiameterPx);
        if (step == 0) return new int[][] {{centerX, centerY}};
        return new int[][] {
            {centerX, centerY},
            {centerX - step, centerY},
            {centerX + step, centerY},
            {centerX, centerY - step},
            {centerX, centerY + step}
        };
    }
}
