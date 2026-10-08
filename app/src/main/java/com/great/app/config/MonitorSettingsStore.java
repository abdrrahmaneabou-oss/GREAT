package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted visual ring size and separate portrait/landscape positions. Capture consent is never saved. */
public final class MonitorSettingsStore {
    public static final int MIN_RING_DP = 12, MAX_RING_DP = 48, STEP_DP = 4;
    private final SharedPreferences prefs;
    public MonitorSettingsStore(Context context) {
        prefs = context.getSharedPreferences("great_visual_monitor", Context.MODE_PRIVATE);
    }
    public int ringDp() { return clamp(prefs.getInt("ring_dp", 20), MIN_RING_DP, MAX_RING_DP); }
    public void setRingDp(int size) { prefs.edit().putInt("ring_dp", clamp(size, MIN_RING_DP, MAX_RING_DP)).apply(); }
    private String profile(int w, int h) { return h >= w ? "portrait" : "landscape"; }
    public float x(int w, int h) { return position("x", w, h, .8f); }
    public float y(int w, int h) { return position("y", w, h, .55f); }
    private float position(String axis, int w, int h, float fallback) {
        String key = profile(w, h) + axis;
        String other = (h >= w ? "landscape" : "portrait") + axis;
        return Math.max(0, Math.min(1, prefs.getFloat(key, prefs.getFloat(other, prefs.getFloat("center_" + axis + "_fraction", fallback)))));
    }
    public void save(float x, float y, int w, int h) {
        prefs.edit().putFloat(profile(w, h) + "x", x).putFloat(profile(w, h) + "y", y).apply();
    }
    private static int clamp(int n, int lo, int hi) { return Math.max(lo, Math.min(hi, n)); }
}
