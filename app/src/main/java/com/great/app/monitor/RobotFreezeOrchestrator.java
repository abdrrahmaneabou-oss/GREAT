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
    private static final int MONITOR_POLL_MS = 250;

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
    private long nextPeriodicAtNanos;
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

    /** Polling only tracks Robot ON/OFF; actual Freeze deadlines are random to the millisecond. */
    public void ensureStarted() {
        synchronized (lock) {
            if (started) return;
            started = true;
            scheduler.scheduleWithFixedDelay(this::periodicTick,
                    0L, MONITOR_POLL_MS, TimeUnit.MILLISECONDS);
        }
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
            nextPeriodicAtNanos = 0L;
            if (periodicEnd != null) periodicEnd.cancel(false);
            periodicEnd = null;
        }
        GreatEngine.instance().freezeCore().setPeriodicHoldTrigger(false);
        GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
    }

    public void visualFreezeStarted() {
        GlobalRobotOutboundThrottle.instance().startLightForVisualFreeze();
    }

    public boolean periodicFreezeActive() {
        synchronized (lock) { return periodicFreezeActive; }
    }

    private void periodicTick() {
        boolean run = false;
        int durationMs = 0;
        synchronized (lock) {
            long now = System.nanoTime();
            if (!FreezeMonitorService.isMonitoringActive()) {
                nextPeriodicAtNanos = 0L;
                return;
            }

            if (nextPeriodicAtNanos == 0L) {
                nextPeriodicAtNanos = now + TimeUnit.MILLISECONDS.toNanos(
                        randomInclusiveLocked(MIN_PERIODIC_INTERVAL_MS, MAX_PERIODIC_INTERVAL_MS));
                return;
            }
            if (now < nextPeriodicAtNanos) return;

            nextPeriodicAtNanos = now + TimeUnit.MILLISECONDS.toNanos(
                    randomInclusiveLocked(MIN_PERIODIC_INTERVAL_MS, MAX_PERIODIC_INTERVAL_MS));

            GreatEngine engine = GreatEngine.instance();
            boolean manualFreeze = engine.capabilities().snapshot().enabled(Capability.FREEZE);
            boolean eligible = !visualCycleBusy
                    && !periodicFreezeActive
                    && !manualFreeze
                    && !engine.freezeCore().holdTriggerActive();
            if (eligible && rng.nextInt(100) >= freezeSkipPercent) {
                periodicFreezeActive = true;
                durationMs = randomInclusiveLocked(MIN_PERIODIC_FREEZE_MS, MAX_PERIODIC_FREEZE_MS);
                run = true;
            }
        }

        if (!run) return;
        final int chosenDuration = durationMs;
        synchronized (lock) {
            // Activation and scheduling share the same lock as resetVisualCycle().
            // An obsolete tick cannot reactivate a hold after monitoring OFF.
            if (!periodicFreezeActive || !FreezeMonitorService.isMonitoringActive()) {
                periodicFreezeActive = false;
                return;
            }
            GreatEngine.instance().freezeCore().setPeriodicHoldTrigger(true);
            GlobalRobotOutboundThrottle.instance().startLightForPeriodicFreeze();
            periodicEnd = scheduler.schedule(this::finishPeriodicFreeze,
                    chosenDuration, TimeUnit.MILLISECONDS);
        }
    }

    private void finishPeriodicFreeze() {
        synchronized (lock) {
            periodicEnd = null;
            if (!periodicFreezeActive) return;
            periodicFreezeActive = false;
            GreatEngine.instance().freezeCore().setPeriodicHoldTrigger(false);
        }
        if (FreezeMonitorService.isMonitoringActive()) {
            GlobalRobotOutboundThrottle.instance().finishPeriodicFreezeAndStartPostThrottle();
        } else {
            GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
        }
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }
}
