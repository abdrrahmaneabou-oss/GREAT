package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent position, visibility and functional state for the visual monitor. */
public final class MonitorSettingsStore {
    private static final String PREFS = "great_visual_monitor";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    private static final String KEY_CENTER_X = "center_x_fraction";
    private static final String KEY_CENTER_Y = "center_y_fraction";

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
        return clamp(prefs.getFloat(KEY_CENTER_X, 0.80f));
    }

    public float centerYFraction() {
        return clamp(prefs.getFloat(KEY_CENTER_Y, 0.55f));
    }

    public void setCenterFractions(float x, float y) {
        prefs.edit()
                .putFloat(KEY_CENTER_X, clamp(x))
                .putFloat(KEY_CENTER_Y, clamp(y))
                .apply();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
