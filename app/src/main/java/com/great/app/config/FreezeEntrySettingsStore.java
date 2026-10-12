package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent tuning for Freeze entry ramp, Robot skip rate, and packet-summary overlay scale. */
public final class FreezeEntrySettingsStore {
    private static final String PREFS = "great_freeze_entry_tuning";

    private static final String RAMP_DURATION_MIN_MS = "ramp_duration_min_ms";
    private static final String RAMP_DURATION_MAX_MS = "ramp_duration_max_ms";
    private static final String RAMP_STAGE1_MIN_PERCENT = "ramp_stage1_min_percent";
    private static final String RAMP_STAGE1_MAX_PERCENT = "ramp_stage1_max_percent";
    private static final String RAMP_STAGE2_MIN_PERCENT = "ramp_stage2_min_percent";
    private static final String RAMP_STAGE2_MAX_PERCENT = "ramp_stage2_max_percent";
    private static final String ROBOT_SKIP_PERCENT = "robot_skip_percent";
    private static final String SUMMARY_SCALE = "summary_scale";

    public static final int DEFAULT_RAMP_DURATION_MIN_MS = 70;
    public static final int DEFAULT_RAMP_DURATION_MAX_MS = 130;
    public static final int DEFAULT_RAMP_STAGE1_MIN_PERCENT = 20;
    public static final int DEFAULT_RAMP_STAGE1_MAX_PERCENT = 40;
    public static final int DEFAULT_RAMP_STAGE2_MIN_PERCENT = 55;
    public static final int DEFAULT_RAMP_STAGE2_MAX_PERCENT = 75;
    public static final int DEFAULT_ROBOT_SKIP_PERCENT = 25;
    public static final float DEFAULT_SUMMARY_SCALE = 1.0f;

    public static final int MIN_RAMP_DURATION_MS = 0;
    public static final int MAX_RAMP_DURATION_MS = 5000;
    public static final int MIN_HOLD_PERCENT = 0;
    public static final int MAX_HOLD_PERCENT = 100;
    public static final int MIN_ROBOT_SKIP_PERCENT = 0;
    public static final int MAX_ROBOT_SKIP_PERCENT = 25;
    public static final float MIN_SUMMARY_SCALE = 0.55f;
    public static final float MAX_SUMMARY_SCALE = 2.40f;

    private final SharedPreferences prefs;

    public FreezeEntrySettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int rampDurationMinMs() { return prefs.getInt(RAMP_DURATION_MIN_MS, DEFAULT_RAMP_DURATION_MIN_MS); }
    public int rampDurationMaxMs() { return prefs.getInt(RAMP_DURATION_MAX_MS, DEFAULT_RAMP_DURATION_MAX_MS); }
    public int rampStage1MinPercent() { return prefs.getInt(RAMP_STAGE1_MIN_PERCENT, DEFAULT_RAMP_STAGE1_MIN_PERCENT); }
    public int rampStage1MaxPercent() { return prefs.getInt(RAMP_STAGE1_MAX_PERCENT, DEFAULT_RAMP_STAGE1_MAX_PERCENT); }
    public int rampStage2MinPercent() { return prefs.getInt(RAMP_STAGE2_MIN_PERCENT, DEFAULT_RAMP_STAGE2_MIN_PERCENT); }
    public int rampStage2MaxPercent() { return prefs.getInt(RAMP_STAGE2_MAX_PERCENT, DEFAULT_RAMP_STAGE2_MAX_PERCENT); }

    public void setRampProfile(int durationMinMs, int durationMaxMs,
                               int stage1MinPercent, int stage1MaxPercent,
                               int stage2MinPercent, int stage2MaxPercent) {
        validateRange(durationMinMs, durationMaxMs, MIN_RAMP_DURATION_MS, MAX_RAMP_DURATION_MS, "Ramp duration");
        validateRange(stage1MinPercent, stage1MaxPercent, MIN_HOLD_PERCENT, MAX_HOLD_PERCENT, "Stage 1 hold");
        validateRange(stage2MinPercent, stage2MaxPercent, MIN_HOLD_PERCENT, MAX_HOLD_PERCENT, "Stage 2 hold");
        prefs.edit()
                .putInt(RAMP_DURATION_MIN_MS, durationMinMs)
                .putInt(RAMP_DURATION_MAX_MS, durationMaxMs)
                .putInt(RAMP_STAGE1_MIN_PERCENT, stage1MinPercent)
                .putInt(RAMP_STAGE1_MAX_PERCENT, stage1MaxPercent)
                .putInt(RAMP_STAGE2_MIN_PERCENT, stage2MinPercent)
                .putInt(RAMP_STAGE2_MAX_PERCENT, stage2MaxPercent)
                .apply();
    }

    /** Returns 25 until the user explicitly saves a different value. */
    public int robotSkipPercent() {
        return clamp(prefs.getInt(ROBOT_SKIP_PERCENT, DEFAULT_ROBOT_SKIP_PERCENT),
                MIN_ROBOT_SKIP_PERCENT, MAX_ROBOT_SKIP_PERCENT);
    }

    public void setRobotSkipPercent(int percent) {
        if (percent < MIN_ROBOT_SKIP_PERCENT || percent > MAX_ROBOT_SKIP_PERCENT) {
            throw new IllegalArgumentException("Robot skip must be 0..25%");
        }
        prefs.edit().putInt(ROBOT_SKIP_PERCENT, percent).apply();
    }

    public float summaryScale() {
        return clamp(prefs.getFloat(SUMMARY_SCALE, DEFAULT_SUMMARY_SCALE), MIN_SUMMARY_SCALE, MAX_SUMMARY_SCALE);
    }

    public void setSummaryScale(float scale) {
        prefs.edit().putFloat(SUMMARY_SCALE, clamp(scale, MIN_SUMMARY_SCALE, MAX_SUMMARY_SCALE)).apply();
    }

    private static void validateRange(int min, int max, int low, int high, String label) {
        if (min < low || max > high || min > max) {
            throw new IllegalArgumentException(label + " range is invalid");
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
