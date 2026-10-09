package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.great.app.core.FreezeCore;

/** Persistent user tuning for GREAT capability limits and packet filtering. */
public final class CapabilitySettingsStore {
    private static final String PREFS = "great_capability_settings";
    private static final String FREEZE_SECONDS = "freeze_seconds";
    private static final String FREEZE_PAYLOAD_MIN = "freeze_payload_min";
    private static final String FREEZE_PAYLOAD_MAX = "freeze_payload_max";

    private final SharedPreferences prefs;

    public CapabilitySettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int freezeSeconds() {
        return clampSeconds(prefs.getInt(FREEZE_SECONDS, FreezeCore.DEFAULT_DURATION_SECONDS));
    }

    public int setFreezeSeconds(int seconds) {
        int value = clampSeconds(seconds);
        prefs.edit().putInt(FREEZE_SECONDS, value).apply();
        return value;
    }

    public int freezePayloadMin() {
        int min = prefs.getInt(FREEZE_PAYLOAD_MIN, FreezeCore.DEFAULT_PAYLOAD_MIN);
        int max = freezePayloadMax();
        if (!validPayloadRange(min, max)) return FreezeCore.DEFAULT_PAYLOAD_MIN;
        return min;
    }

    public int freezePayloadMax() {
        int max = prefs.getInt(FREEZE_PAYLOAD_MAX, FreezeCore.DEFAULT_PAYLOAD_MAX);
        int min = prefs.getInt(FREEZE_PAYLOAD_MIN, FreezeCore.DEFAULT_PAYLOAD_MIN);
        if (!validPayloadRange(min, max)) return FreezeCore.DEFAULT_PAYLOAD_MAX;
        return max;
    }

    public void setFreezePayloadRange(int min, int max) {
        if (!validPayloadRange(min, max)) {
            throw new IllegalArgumentException("Payload range must be 20..500 bytes and min must be less than max");
        }
        prefs.edit()
                .putInt(FREEZE_PAYLOAD_MIN, min)
                .putInt(FREEZE_PAYLOAD_MAX, max)
                .apply();
    }

    private static boolean validPayloadRange(int min, int max) {
        return min >= FreezeCore.MIN_PAYLOAD_LIMIT
                && max <= FreezeCore.MAX_PAYLOAD_LIMIT
                && min < max;
    }

    private static int clampSeconds(int seconds) {
        return Math.max(FreezeCore.MIN_DURATION_SECONDS,
                Math.min(FreezeCore.MAX_DURATION_SECONDS, seconds));
    }
}
