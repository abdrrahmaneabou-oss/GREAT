package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.great.app.core.FreezeCore;
import com.great.app.transport.GlobalRobotOutboundThrottle;

/** Persistent user tuning for GREAT capability limits and packet filtering. */
public final class CapabilitySettingsStore {
    private static final String PREFS = "great_capability_settings";
    private static final String FREEZE_SECONDS = "freeze_seconds";
    private static final String FREEZE_PAYLOAD_MIN = "freeze_payload_min";
    private static final String FREEZE_PAYLOAD_MAX = "freeze_payload_max";
    private static final String FREEZE_PAYLOAD_RANDOM_ENABLED = "freeze_payload_random_enabled";
    private static final String FREEZE_PAYLOAD_MIN_FROM = "freeze_payload_min_from";
    private static final String FREEZE_PAYLOAD_MIN_TO = "freeze_payload_min_to";
    private static final String FREEZE_PAYLOAD_MAX_FROM = "freeze_payload_max_from";
    private static final String FREEZE_PAYLOAD_MAX_TO = "freeze_payload_max_to";
    private static final String OUTBOUND_RELEASE_THROTTLE_ENABLED = "outbound_release_throttle_enabled";
    private static final String ROBOT_WHITE_RETURN_START_MIN_MS = "robot_white_return_start_min_ms";
    private static final String ROBOT_WHITE_RETURN_START_MAX_MS = "robot_white_return_start_max_ms";
    private static final String ROBOT_WHITE_RETURN_DURATION_MIN_MS = "robot_white_return_duration_min_ms";
    private static final String ROBOT_WHITE_RETURN_DURATION_MAX_MS = "robot_white_return_duration_max_ms";

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

    public boolean freezePayloadRandomEnabled() {
        return prefs.getBoolean(FREEZE_PAYLOAD_RANDOM_ENABLED, false);
    }

    public void setFreezePayloadRandomEnabled(boolean enabled) {
        prefs.edit().putBoolean(FREEZE_PAYLOAD_RANDOM_ENABLED, enabled).apply();
    }

    public int freezePayloadMinFrom() { return randomPayloadRange()[0]; }
    public int freezePayloadMinTo() { return randomPayloadRange()[1]; }
    public int freezePayloadMaxFrom() { return randomPayloadRange()[2]; }
    public int freezePayloadMaxTo() { return randomPayloadRange()[3]; }

    public void setFreezePayloadRandomRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        if (!validRandomPayloadRange(minFrom, minTo, maxFrom, maxTo)) {
            throw new IllegalArgumentException(
                    "Random payload ranges must stay inside 20..500 and minimum range must remain below maximum range");
        }
        prefs.edit()
                .putInt(FREEZE_PAYLOAD_MIN_FROM, minFrom)
                .putInt(FREEZE_PAYLOAD_MIN_TO, minTo)
                .putInt(FREEZE_PAYLOAD_MAX_FROM, maxFrom)
                .putInt(FREEZE_PAYLOAD_MAX_TO, maxTo)
                .apply();
    }

    /** This switch controls only Robot post-Freeze throttle after the visual white-return cycle. */
    public boolean outboundReleaseThrottleEnabled() {
        return prefs.getBoolean(OUTBOUND_RELEASE_THROTTLE_ENABLED, true);
    }

    public void setOutboundReleaseThrottleEnabled(boolean enabled) {
        prefs.edit().putBoolean(OUTBOUND_RELEASE_THROTTLE_ENABLED, enabled).apply();
    }

    public int robotWhiteReturnStartMinMs() { return robotWhiteReturnRange()[0]; }
    public int robotWhiteReturnStartMaxMs() { return robotWhiteReturnRange()[1]; }
    public int robotWhiteReturnDurationMinMs() { return robotWhiteReturnRange()[2]; }
    public int robotWhiteReturnDurationMaxMs() { return robotWhiteReturnRange()[3]; }

    public void setRobotWhiteReturnThrottleRange(int startMinMs, int startMaxMs,
                                                  int durationMinMs, int durationMaxMs) {
        if (!GlobalRobotOutboundThrottle.validVisualPostRange(
                startMinMs, startMaxMs, durationMinMs, durationMaxMs)) {
            throw new IllegalArgumentException("Throttle ranges must be ordered and stay inside 1..5000 ms");
        }
        prefs.edit()
                .putInt(ROBOT_WHITE_RETURN_START_MIN_MS, startMinMs)
                .putInt(ROBOT_WHITE_RETURN_START_MAX_MS, startMaxMs)
                .putInt(ROBOT_WHITE_RETURN_DURATION_MIN_MS, durationMinMs)
                .putInt(ROBOT_WHITE_RETURN_DURATION_MAX_MS, durationMaxMs)
                .apply();
    }

    private int[] robotWhiteReturnRange() {
        int startMin = prefs.getInt(ROBOT_WHITE_RETURN_START_MIN_MS,
                GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_START_MIN_MS);
        int startMax = prefs.getInt(ROBOT_WHITE_RETURN_START_MAX_MS,
                GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_START_MAX_MS);
        int durationMin = prefs.getInt(ROBOT_WHITE_RETURN_DURATION_MIN_MS,
                GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_DURATION_MIN_MS);
        int durationMax = prefs.getInt(ROBOT_WHITE_RETURN_DURATION_MAX_MS,
                GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_DURATION_MAX_MS);
        if (!GlobalRobotOutboundThrottle.validVisualPostRange(startMin, startMax, durationMin, durationMax)) {
            return new int[]{
                    GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_START_MIN_MS,
                    GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_START_MAX_MS,
                    GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_DURATION_MIN_MS,
                    GlobalRobotOutboundThrottle.DEFAULT_VISUAL_POST_DURATION_MAX_MS
            };
        }
        return new int[]{startMin, startMax, durationMin, durationMax};
    }

    private int[] randomPayloadRange() {
        int minFrom = prefs.getInt(FREEZE_PAYLOAD_MIN_FROM, FreezeCore.DEFAULT_RANDOM_PAYLOAD_MIN_FROM);
        int minTo = prefs.getInt(FREEZE_PAYLOAD_MIN_TO, FreezeCore.DEFAULT_RANDOM_PAYLOAD_MIN_TO);
        int maxFrom = prefs.getInt(FREEZE_PAYLOAD_MAX_FROM, FreezeCore.DEFAULT_RANDOM_PAYLOAD_MAX_FROM);
        int maxTo = prefs.getInt(FREEZE_PAYLOAD_MAX_TO, FreezeCore.DEFAULT_RANDOM_PAYLOAD_MAX_TO);
        if (!validRandomPayloadRange(minFrom, minTo, maxFrom, maxTo)) {
            return new int[]{
                    FreezeCore.DEFAULT_RANDOM_PAYLOAD_MIN_FROM,
                    FreezeCore.DEFAULT_RANDOM_PAYLOAD_MIN_TO,
                    FreezeCore.DEFAULT_RANDOM_PAYLOAD_MAX_FROM,
                    FreezeCore.DEFAULT_RANDOM_PAYLOAD_MAX_TO
            };
        }
        return new int[]{minFrom, minTo, maxFrom, maxTo};
    }

    private static boolean validPayloadRange(int min, int max) {
        return min >= FreezeCore.MIN_PAYLOAD_LIMIT
                && max <= FreezeCore.MAX_PAYLOAD_LIMIT
                && min < max;
    }

    private static boolean validRandomPayloadRange(int minFrom, int minTo, int maxFrom, int maxTo) {
        return minFrom >= FreezeCore.MIN_PAYLOAD_LIMIT
                && minFrom <= minTo
                && minTo < maxFrom
                && maxFrom <= maxTo
                && maxTo <= FreezeCore.MAX_PAYLOAD_LIMIT;
    }

    private static int clampSeconds(int seconds) {
        return Math.max(FreezeCore.MIN_DURATION_SECONDS,
                Math.min(FreezeCore.MAX_DURATION_SECONDS, seconds));
    }
}
