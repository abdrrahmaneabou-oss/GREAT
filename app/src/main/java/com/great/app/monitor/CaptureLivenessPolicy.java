package com.great.app.monitor;

import java.util.concurrent.TimeUnit;

/**
 * Pure decision policy for a MediaProjection ImageReader that goes silent.
 * Silence can be a static screen, so retries must be bounded and must preserve
 * the existing white baseline unless actual frame corruption was observed.
 */
public final class CaptureLivenessPolicy {
    public static final long STALE_FRAME_NANOS = TimeUnit.SECONDS.toNanos(14);
    public static final int MAX_RECOVERY_ATTEMPTS = 3;
    public static final int FAILURE_TRIGGER = 4;

    private CaptureLivenessPolicy() { }

    public static boolean shouldRecover(long ageNanos, int consecutiveFailures,
                                        int attempts, boolean recoveryAlreadyPending) {
        return !recoveryAlreadyPending && attempts < MAX_RECOVERY_ATTEMPTS
                && (ageNanos > STALE_FRAME_NANOS || consecutiveFailures >= FAILURE_TRIGGER);
    }

    /** No new frames is not proof the prior white baseline became invalid. */
    public static boolean preserveWhiteBaseline(boolean repeatedBadFrames) {
        return !repeatedBadFrames;
    }
}
