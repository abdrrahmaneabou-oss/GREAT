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
 * 30..60 second random Freeze cadence, and couples the light/global throttle to actual Robot hold.
 * FreezeCore itself remains unchanged so packet filtering, ramping and release mechanics stay exact.
 */
public final class RobotFreezeOrchestrator {
    public static final int FREEZE_SKIP_PERCENT = 25;
    public static final int MIN_PERIODIC_INTERVAL_SECONDS = 30;
    public static final int MAX_PERIODIC_INTERVAL_SECONDS = 60;
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

    private RobotFreezeOrchestrator(Random rng) { this.rng = rng; }
    public static RobotFreezeOrchestrator instance() { return INSTANCE; }

    public void ensureStarted() {
        synchronized (lock) {
            if (started) return;
            started = true;
            scheduleNextLocked();
        }
    }

    /** 25% of white-loss Robot cycles are ignored completely. */
    public boolean allowVisualFreeze() {
        synchronized (lock) {
            visualCycleBusy = true;
            if (periodicFreezeActive) return false;
            return rng.nextInt(100) >= FREEZE_SKIP_PERCENT;
        }
    }

    public void finishVisualCycle(boolean freezeActuallyRan) {
        synchronized (lock) { visualCycleBusy = false; }
        GlobalRobotOutboundThrottle throttle = GlobalRobotOutboundThrottle.instance();
        // If the existing monitor safety timeout already ended the hold, the throttle hot path
        // has already converted LIGHT into POST_FREEZE. Do not restart that post phase on white return.
        if (freezeActuallyRan && throttle.lightActive()) {
            throttle.finishFreezeAndStartPostThrottle();
        }
    }

    public void resetVisualCycle() {
        boolean periodic;
        synchronized (lock) {
            visualCycleBusy = false;
            periodic = periodicFreezeActive;
        }
        if (!periodic) GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
    }

    /** Start light throttling only after FreezeCore has actually entered Robot hold. */
    public void visualFreezeStarted() {
        GlobalRobotOutboundThrottle.instance().startLightForFreeze();
    }

    public boolean periodicFreezeActive() {
        synchronized (lock) { return periodicFreezeActive; }
    }

    private void scheduleNextLocked() {
        if (!started) return;
        int seconds = randomInclusiveLocked(MIN_PERIODIC_INTERVAL_SECONDS, MAX_PERIODIC_INTERVAL_SECONDS);
        nextPeriodic = scheduler.schedule(this::periodicTick, seconds, TimeUnit.SECONDS);
    }

    private void periodicTick() {
        try {
            boolean run = false;
            int durationMs = 0;
            synchronized (lock) {
                nextPeriodic = null;
                GreatEngine engine = GreatEngine.instance();
                boolean manualFreeze = engine.capabilities().snapshot().enabled(Capability.FREEZE);
                boolean eligible = FreezeMonitorService.isMonitoringActive()
                        && !visualCycleBusy
                        && !periodicFreezeActive
                        && !manualFreeze
                        && !engine.freezeCore().holdTriggerActive();
                // 25% of periodic Robot attempts are ignored too.
                if (eligible && rng.nextInt(100) >= FREEZE_SKIP_PERCENT) {
                    periodicFreezeActive = true;
                    durationMs = randomInclusiveLocked(MIN_PERIODIC_FREEZE_MS, MAX_PERIODIC_FREEZE_MS);
                    run = true;
                }
            }

            if (run) {
                GreatEngine.instance().freezeCore().setHoldTrigger(true);
                GlobalRobotOutboundThrottle.instance().startLightForFreeze();
                final int chosenDuration = durationMs;
                synchronized (lock) {
                    periodicEnd = scheduler.schedule(this::finishPeriodicFreeze,
                            chosenDuration, TimeUnit.MILLISECONDS);
                }
            }
        } finally {
            synchronized (lock) { scheduleNextLocked(); }
        }
    }

    private void finishPeriodicFreeze() {
        synchronized (lock) {
            periodicEnd = null;
            if (!periodicFreezeActive) return;
            periodicFreezeActive = false;
        }
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (FreezeMonitorService.isMonitoringActive()) {
            GlobalRobotOutboundThrottle.instance().finishFreezeAndStartPostThrottle();
        } else {
            GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
        }
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }
}
