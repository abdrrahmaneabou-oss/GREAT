package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.great.app.core.FreezeCore;

/** Persistent user tuning for GREAT capability time limits. */
public final class CapabilitySettingsStore {
    private static final String PREFS = "great_capability_settings";
    private static final String FREEZE_SECONDS = "freeze_seconds";

    private final SharedPreferences prefs;

    public CapabilitySettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int freezeSeconds() {
        return clamp(prefs.getInt(FREEZE_SECONDS, FreezeCore.DEFAULT_DURATION_SECONDS));
    }

    public int setFreezeSeconds(int seconds) {
        int value = clamp(seconds);
        prefs.edit().putInt(FREEZE_SECONDS, value).apply();
        return value;
    }

    private static int clamp(int seconds) {
        return Math.max(FreezeCore.MIN_DURATION_SECONDS,
                Math.min(FreezeCore.MAX_DURATION_SECONDS, seconds));
    }
}
