package com.great.app.monitor;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** These decisions are the fallback for Android capture frames that stop arriving. */
public final class CaptureLivenessPolicyTest {
    @Test public void activeFramesDoNotTriggerRepairs() {
        assertFalse(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(2), 0, 0, false));
    }

    @Test public void staticStreamMayBeProbedButNotMisdiagnosedAsPermissionLoss() {
        assertTrue(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(15), 0, 0, false));
        assertTrue(CaptureLivenessPolicy.preserveWhiteBaseline(false));
    }

    @Test public void repeatedFrameCorruptionTriggersFreshWhiteRearm() {
        assertTrue(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(1), 4, 0, false));
        assertFalse(CaptureLivenessPolicy.preserveWhiteBaseline(true));
    }

    @Test public void retriesAreBoundedAndNoDuplicateRepairIsScheduled() {
        assertFalse(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(30), 12, 0, true));
        assertFalse(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(30), 12, 3, false));
        assertTrue(CaptureLivenessPolicy.shouldRecover(
                TimeUnit.SECONDS.toNanos(30), 12, 2, false));
    }
}
