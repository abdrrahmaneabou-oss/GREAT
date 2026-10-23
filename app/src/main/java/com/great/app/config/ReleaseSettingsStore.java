package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.great.app.core.FreezeCore;

/** Persistent tuning for the Freeze buffered-packet release stage. */
public final class ReleaseSettingsStore {
    private static final String PREFS = "great_release_settings";
    private static final String DROP_ENABLED = "drop_enabled";
    private static final String BURST_ENABLED = "burst_enabled";
    private static final String DELAY_ENABLED = "delay_enabled";
    private static final String DROP_MIN = "drop_min";
    private static final String DROP_MAX = "drop_max";
    private static final String BURST_MIN = "burst_min";
    private static final String BURST_MAX = "burst_max";
    private static final String DELAY_MIN_MS = "delay_min_ms";
    private static final String DELAY_MAX_MS = "delay_max_ms";

    public static final int DEFAULT_DROP_MIN_PERCENT = 15;
    public static final int DEFAULT_DROP_MAX_PERCENT = 35;
    public static final int DEFAULT_BURST_MIN = 2;
    public static final int DEFAULT_BURST_MAX = 5;
    public static final int DEFAULT_DELAY_MIN_MS = 25;
    public static final int DEFAULT_DELAY_MAX_MS = 75;

    private final SharedPreferences prefs;

    public ReleaseSettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        // A partial migration must not overwrite the user's valid existing values.
        if (!prefs.contains(DROP_MIN)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(DROP_MIN, DEFAULT_DROP_MIN_PERCENT);
        }
        if (!prefs.contains(DROP_MAX)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(DROP_MAX, DEFAULT_DROP_MAX_PERCENT);
        }
        if (!prefs.contains(BURST_MIN)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(BURST_MIN, DEFAULT_BURST_MIN);
        }
        if (!prefs.contains(BURST_MAX)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(BURST_MAX, DEFAULT_BURST_MAX);
        }
        if (!prefs.contains(DELAY_MIN_MS)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(DELAY_MIN_MS, DEFAULT_DELAY_MIN_MS);
        }
        if (!prefs.contains(DELAY_MAX_MS)) {
            if (editor == null) editor = prefs.edit();
            editor.putInt(DELAY_MAX_MS, DEFAULT_DELAY_MAX_MS);
        }
        if (editor != null) editor.apply();
    }

    public boolean dropEnabled() { return prefs.getBoolean(DROP_ENABLED, true); }
    public boolean burstEnabled() { return prefs.getBoolean(BURST_ENABLED, true); }
    public boolean delayEnabled() { return prefs.getBoolean(DELAY_ENABLED, true); }

    public void setDropEnabled(boolean enabled) { prefs.edit().putBoolean(DROP_ENABLED, enabled).apply(); }
    public void setBurstEnabled(boolean enabled) { prefs.edit().putBoolean(BURST_ENABLED, enabled).apply(); }
    public void setDelayEnabled(boolean enabled) { prefs.edit().putBoolean(DELAY_ENABLED, enabled).apply(); }

    public boolean hasDropRange() { return prefs.contains(DROP_MIN) && prefs.contains(DROP_MAX); }
    public boolean hasBurstRange() { return prefs.contains(BURST_MIN) && prefs.contains(BURST_MAX); }
    public boolean hasDelayRange() { return prefs.contains(DELAY_MIN_MS) && prefs.contains(DELAY_MAX_MS); }

    public int dropMinPercent() {
        return validDropRange(rawDropMin(), rawDropMax()) ? rawDropMin() : DEFAULT_DROP_MIN_PERCENT;
    }

    public int dropMaxPercent() {
        return validDropRange(rawDropMin(), rawDropMax()) ? rawDropMax() : DEFAULT_DROP_MAX_PERCENT;
    }

    public int burstMin() {
        return validBurstRange(rawBurstMin(), rawBurstMax()) ? rawBurstMin() : DEFAULT_BURST_MIN;
    }

    public int burstMax() {
        return validBurstRange(rawBurstMin(), rawBurstMax()) ? rawBurstMax() : DEFAULT_BURST_MAX;
    }

    public int delayMinMs() {
        return validDelayRange(rawDelayMin(), rawDelayMax()) ? rawDelayMin() : DEFAULT_DELAY_MIN_MS;
    }

    public int delayMaxMs() {
        return validDelayRange(rawDelayMin(), rawDelayMax()) ? rawDelayMax() : DEFAULT_DELAY_MAX_MS;
    }

    public void setDropRange(int min, int max) {
        if (!validDropRange(min, max)) throw new IllegalArgumentException("Drop range must be 0..100% and ordered");
        prefs.edit().putInt(DROP_MIN, min).putInt(DROP_MAX, max).apply();
    }

    public void setBurstRange(int min, int max) {
        if (!validBurstRange(min, max)) throw new IllegalArgumentException("Burst range must be 1..100 and ordered");
        prefs.edit().putInt(BURST_MIN, min).putInt(BURST_MAX, max).apply();
    }

    public void setDelayRangeMs(int min, int max) {
        if (!validDelayRange(min, max)) throw new IllegalArgumentException("Delay range must be 0..5000 ms and ordered");
        prefs.edit().putInt(DELAY_MIN_MS, min).putInt(DELAY_MAX_MS, max).apply();
    }

    /** Persist all three ranges in a single preference transaction. */
    public void setRanges(int dropMin, int dropMax, int burstMin, int burstMax,
                          int delayMin, int delayMax) {
        if (!validDropRange(dropMin, dropMax) || !validBurstRange(burstMin, burstMax)
                || !validDelayRange(delayMin, delayMax)) {
            throw new IllegalArgumentException("Invalid release settings");
        }
        prefs.edit().putInt(DROP_MIN, dropMin).putInt(DROP_MAX, dropMax)
                .putInt(BURST_MIN, burstMin).putInt(BURST_MAX, burstMax)
                .putInt(DELAY_MIN_MS, delayMin).putInt(DELAY_MAX_MS, delayMax).apply();
    }

    private int rawDropMin() { return prefs.getInt(DROP_MIN, DEFAULT_DROP_MIN_PERCENT); }
    private int rawDropMax() { return prefs.getInt(DROP_MAX, DEFAULT_DROP_MAX_PERCENT); }
    private int rawBurstMin() { return prefs.getInt(BURST_MIN, DEFAULT_BURST_MIN); }
    private int rawBurstMax() { return prefs.getInt(BURST_MAX, DEFAULT_BURST_MAX); }
    private int rawDelayMin() { return prefs.getInt(DELAY_MIN_MS, DEFAULT_DELAY_MIN_MS); }
    private int rawDelayMax() { return prefs.getInt(DELAY_MAX_MS, DEFAULT_DELAY_MAX_MS); }

    private static boolean validDropRange(int min, int max) {
        return min >= FreezeCore.MIN_RELEASE_DROP_LIMIT_PERCENT
                && max <= FreezeCore.MAX_RELEASE_DROP_LIMIT_PERCENT && min <= max;
    }

    private static boolean validBurstRange(int min, int max) {
        return min >= FreezeCore.MIN_RELEASE_BURST_LIMIT
                && max <= FreezeCore.MAX_RELEASE_BURST_LIMIT && min <= max;
    }

    private static boolean validDelayRange(int min, int max) {
        return min >= FreezeCore.MIN_RELEASE_DELAY_LIMIT_MS
                && max <= FreezeCore.MAX_RELEASE_DELAY_LIMIT_MS && min <= max;
    }
}
