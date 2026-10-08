package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent geometry and runtime enable state for the Freeze trigger circle. */
public final class TriggerSettingsStore {
    public static final float MIN_DIAMETER_CM = 0.25f;
    public static final float MAX_DIAMETER_CM = 3.00f;
    public static final float STEP_CM = 0.25f;
    private static final float DEFAULT_DIAMETER_CM = 1.00f;

    private static final String PREFS = "great_trigger";
    private static final String KEY_DIAMETER = "diameter_cm";
    private static final String KEY_CENTER_X = "center_x_fraction";
    private static final String KEY_CENTER_Y = "center_y_fraction";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_REVISION = "revision";

    private final SharedPreferences prefs;

    public TriggerSettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public synchronized void setEnabled(boolean enabled) {
        if (enabled() == enabled) return;
        prefs.edit().putBoolean(KEY_ENABLED, enabled).putLong(KEY_REVISION, revision() + 1L).apply();
    }

    public long revision() {
        return prefs.getLong(KEY_REVISION, 0L);
    }

    public float diameterCm() {
        return clamp(prefs.getFloat(KEY_DIAMETER, DEFAULT_DIAMETER_CM),
                MIN_DIAMETER_CM, MAX_DIAMETER_CM);
    }

    public synchronized float setDiameterCm(float value) {
        float snapped = Math.round(value / STEP_CM) * STEP_CM;
        snapped = clamp(snapped, MIN_DIAMETER_CM, MAX_DIAMETER_CM);
        if (Float.compare(snapped, diameterCm()) != 0) {
            prefs.edit().putFloat(KEY_DIAMETER, snapped).putLong(KEY_REVISION, revision() + 1L).apply();
        }
        return snapped;
    }

    public float centerXFraction() {
        return clamp(prefs.getFloat(KEY_CENTER_X, 0.80f), 0f, 1f);
    }

    public float centerYFraction() {
        return clamp(prefs.getFloat(KEY_CENTER_Y, 0.55f), 0f, 1f);
    }

    public synchronized void setCenterFractions(float x, float y) {
        float nextX = clamp(x, 0f, 1f);
        float nextY = clamp(y, 0f, 1f);
        if (Float.compare(nextX, centerXFraction()) == 0 && Float.compare(nextY, centerYFraction()) == 0) return;
        prefs.edit()
                .putFloat(KEY_CENTER_X, nextX)
                .putFloat(KEY_CENTER_Y, nextY)
                .putLong(KEY_REVISION, revision() + 1L)
                .apply();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
