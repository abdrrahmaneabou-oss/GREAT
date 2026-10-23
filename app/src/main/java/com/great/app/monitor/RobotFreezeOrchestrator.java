package com.great.app.monitor;

import com.great.app.core.Capability;
import com.great.app.core.GreatEngine;
import com.great.app.transport.GlobalRobotOutboundThrottle;
import com.great.app.ui.FreezeMonitorService;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Robot-only timing layer. It decides whether a Robot Freeze attempt runs, owns the independent
 * 30..60 second random Freeze cadence, and couples Robot throttles to actual Robot hold.
 * FreezeCore itself remains unchanged so packet filtering, ramping and release mechanics stay exact.
 */
public final class RobotFreezeOrchestrator {
    public static final int DEFAULT_FREEZE_SKIP_PERCENT = 25;
    public static final int MIN_FREEZE_SKIP_PERCENT = 0;
    public static final int MAX_FREEZE_SKIP_PERCENT = 100;
    /** Kept for source compatibility; runtime behavior uses the configurable skipPercent field. */
    @Deprecated public static final int FREEZE_SKIP_PERCENT = DEFAULT_FREEZE_SKIP_PERCENT;

    public static final int MIN_PERIODIC_INTERVAL_MS = 30_000;
    public static final int MAX_PERIODIC_INTERVAL_MS = 60_000;
    public static final int MIN_PERIODIC_FREEZE_MS = 300;
    public static final int MAX_PERIODIC_FREEZE_MS = 800;

    private static final RobotFreezeOrchestrator INSTANCE =
            new RobotFreezeOrchestrator(new Random());

    private final Object lock = new Object();
    private final Random rng;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GREAT-Robot-Random-Freeze");
        t.setDaemon(true);
        return t;
    });

    private boolean started;
    private boolean visualCycleBusy;
    private boolean periodicFreezeActive;
    private ScheduledFuture<?> nextPeriodic;
    private ScheduledFuture<?> periodicEnd;
    private int freezeSkipPercent = DEFAULT_FREEZE_SKIP_PERCENT;

    private RobotFreezeOrchestrator(Random rng) { this.rng = rng; }
    public static RobotFreezeOrchestrator instance() { return INSTANCE; }

    public void setSkipPercent(int percent) {
        if (percent < MIN_FREEZE_SKIP_PERCENT || percent > MAX_FREEZE_SKIP_PERCENT) {
            throw new IllegalArgumentException("Robot skip must be 0..100%");
        }
        synchronized (lock) { freezeSkipPercent = percent; }
    }

    public int skipPercent() {
        synchronized (lock) { return freezeSkipPercent; }
    }

    /** Arm exact one-shot intervals, not a 250 ms poll that may exceed the chosen maximum. */
    public void ensureStarted() {
        synchronized (lock) { started = true; }
    }

    public void onMonitoringResumed() {
        synchronized (lock) { scheduleNextLocked(); }
    }

    private void scheduleNextLocked() {
        if (!started || nextPeriodic != null || !FreezeMonitorService.isMonitoringActive()) return;
        int intervalMs = randomInclusiveLocked(MIN_PERIODIC_INTERVAL_MS, MAX_PERIODIC_INTERVAL_MS);
        nextPeriodic = scheduler.schedule(this::periodicTick, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** The configurable percentage of white-loss Robot cycles is ignored completely. */
    public boolean allowVisualFreeze() {
        synchronized (lock) {
            visualCycleBusy = true;
            if (periodicFreezeActive) return false;
            return rng.nextInt(100) >= freezeSkipPercent;
        }
    }

    public void finishVisualCycle(boolean freezeActuallyRan) {
        synchronized (lock) { visualCycleBusy = false; }
        if (freezeActuallyRan) {
            GlobalRobotOutboundThrottle.instance().finishVisualFreezeAndStartPostThrottle();
        }
    }

    public void resetVisualCycle() {
        synchronized (lock) {
            visualCycleBusy = false;
            periodicFreezeActive = false;
            if (nextPeriodic != null) nextPeriodic.cancel(false);
            nextPeriodic = null;
            if (periodicEnd != null) periodicEnd.cancel(false);
            periodicEnd = null;
            // Keep ownership clear and throttle cancellation inside the same
            // critical section as periodic activation. Never cancel a new cycle.
            GreatEngine.instance().freezeCore().setPeriodicHoldTrigger(false);
            GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
        }
    }

    public void visualFreezeStarted() {
        GlobalRobotOutboundThrottle.instance().startLightForVisualFreeze();
    }

    public boolean periodicFreezeActive() {
        synchronized (lock) { return periodicFreezeActive; }
    }

    private void periodicTick() {
        synchronized (lock) {
            nextPeriodic = null;
            if (!FreezeMonitorService.isMonitoringActive()) return;

            // The following cadence starts at this attempt's actual instant.
            scheduleNextLocked();

            GreatEngine engine = GreatEngine.instance();
            boolean manualFreeze = engine.capabilities().snapshot().enabled(Capability.FREEZE);
            boolean eligible = !visualCycleBusy
                    && !periodicFreezeActive
                    && !manualFreeze
                    && !engine.freezeCore().holdTriggerActive();
            if (!eligible || rng.nextInt(100) < freezeSkipPercent) return;

            periodicFreezeActive = true;
            int durationMs = randomInclusiveLocked(
                    MIN_PERIODIC_FREEZE_MS, MAX_PERIODIC_FREEZE_MS);
            // Keep activation and expiry under the same lock as resetVisualCycle.
            engine.freezeCore().setPeriodicHoldTrigger(true);
            GlobalRobotOutboundThrottle.instance().startLightForPeriodicFreeze();
            periodicEnd = scheduler.schedule(this::finishPeriodicFreeze,
                    durationMs, TimeUnit.MILLISECONDS);
        }
    }

    private void finishPeriodicFreeze() {
        synchronized (lock) {
            periodicEnd = null;
            if (!periodicFreezeActive) return;
            periodicFreezeActive = false;
            GreatEngine.instance().freezeCore().setPeriodicHoldTrigger(false);
            // A monitor OFF/ON occurring concurrently cannot inherit the old post phase.
            if (FreezeMonitorService.isMonitoringActive()) {
                GlobalRobotOutboundThrottle.instance().finishPeriodicFreezeAndStartPostThrottle();
            } else {
                GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
            }
        }
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }
}
