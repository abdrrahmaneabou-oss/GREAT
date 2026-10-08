package com.great.app.monitor;

/** Pure screen-to-crop mapping, also used by the capture-independent tests. */
public final class MonitorGeometry {
    private MonitorGeometry() { }
    public static int mmToPx(float mm, float xdpi, float ydpi, int densityDpi) {
        float x = validDpi(xdpi) ? xdpi : densityDpi;
        float y = validDpi(ydpi) ? ydpi : densityDpi;
        return Math.max(1, Math.round(((x + y) / 2f) * mm / 25.4f));
    }
    private static boolean validDpi(float value) {
        return Float.isFinite(value) && value >= 100 && value <= 1000;
    }
    public static int center(int screenCenter, int screenExtent, int cropStart, int cropEnd) {
        if (screenExtent <= 0 || cropEnd <= cropStart) throw new IllegalArgumentException("empty geometry");
        return Math.max(cropStart, Math.min(cropEnd - 1,
                Math.round(cropStart + screenCenter * (float) (cropEnd - cropStart) / screenExtent)));
    }
    public static float radius(int diameterPx, int screenExtent, int cropExtent) {
        return Math.max(.5f, cropExtent * (diameterPx / 2f) / screenExtent);
    }
}
