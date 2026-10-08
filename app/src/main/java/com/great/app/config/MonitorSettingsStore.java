package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent position, functional state and timeout for the visual monitor. */
public final class MonitorSettingsStore {
    private static final String PREFS = "great_visual_monitor";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    private static final String KEY_CENTER_X = "center_x_fraction";
    private static final String KEY_CENTER_Y = "center_y_fraction";
    private static final String KEY_MAX_FREEZE_TENTHS = "max_freeze_tenths";

    public static final int MIN_FREEZE_TENTHS = 1;   // 0.10 s
    public static final int MAX_FREEZE_TENTHS = 50; // 5.00 s
    public static final int DEFAULT_FREEZE_TENTHS = 10; // 1.00 s

    private final SharedPreferences prefs;

    public MonitorSettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean monitoringEnabled() {
        return prefs.getBoolean(KEY_MONITORING_ENABLED, false);
    }

    public void setMonitoringEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply();
    }

    public float centerXFraction() {
        return clampFraction(prefs.getFloat(KEY_CENTER_X, 0.80f));
    }

    public float centerYFraction() {
        return clampFraction(prefs.getFloat(KEY_CENTER_Y, 0.55f));
    }

    public void setCenterFractions(float x, float y) {
        prefs.edit()
                .putFloat(KEY_CENTER_X, clampFraction(x))
                .putFloat(KEY_CENTER_Y, clampFraction(y))
                .apply();
    }

    public int maxFreezeTenths() {
        return clampTenths(prefs.getInt(KEY_MAX_FREEZE_TENTHS, DEFAULT_FREEZE_TENTHS));
    }

    public int setMaxFreezeTenths(int value) {
        int clamped = clampTenths(value);
        prefs.edit().putInt(KEY_MAX_FREEZE_TENTHS, clamped).apply();
        return clamped;
    }

    public long maxFreezeMillis() {
        return maxFreezeTenths() * 100L;
    }

    private static int clampTenths(int value) {
        return Math.max(MIN_FREEZE_TENTHS, Math.min(MAX_FREEZE_TENTHS, value));
    }

    private static float clampFraction(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
