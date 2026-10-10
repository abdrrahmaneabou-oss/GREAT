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

    private RobotFreezeOrchestrator(Random rng) { this.rng = rng; }
    public static RobotFreezeOrchestrator instance() { return INSTANCE; }

    /** Polling only tracks Robot ON/OFF; actual Freeze deadlines are random to the millisecond. */
    public void ensureStarted() {
        synchronized (lock) {
            if (started) return;
            started = true;
            scheduler.scheduleWithFixedDelay(this::periodicTick,
                    0L, MONITOR_POLL_MS, TimeUnit.MILLISECONDS);
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
        if (freezeActuallyRan && throttle.lightActive()) {
            // Only this visual white-return path uses the user-configurable post-Freeze card.
            throttle.finishVisualFreezeAndStartPostThrottle();
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
                // When Robot is re-enabled, it always gets a fresh full 30..60 second interval.
                nextPeriodicAtNanos = 0L;
                return;
            }

            if (nextPeriodicAtNanos == 0L) {
                nextPeriodicAtNanos = now + TimeUnit.MILLISECONDS.toNanos(
                        randomInclusiveLocked(MIN_PERIODIC_INTERVAL_MS, MAX_PERIODIC_INTERVAL_MS));
                return;
            }
            if (now < nextPeriodicAtNanos) return;

            // Every deadline immediately receives a new independent 30..60 s deadline, whether
            // this particular cycle runs or is skipped by the 25% rule / another active Freeze.
            nextPeriodicAtNanos = now + TimeUnit.MILLISECONDS.toNanos(
                    randomInclusiveLocked(MIN_PERIODIC_INTERVAL_MS, MAX_PERIODIC_INTERVAL_MS));

            GreatEngine engine = GreatEngine.instance();
            boolean manualFreeze = engine.capabilities().snapshot().enabled(Capability.FREEZE);
            boolean eligible = !visualCycleBusy
                    && !periodicFreezeActive
                    && !manualFreeze
                    && !engine.freezeCore().holdTriggerActive();
            if (eligible && rng.nextInt(100) >= FREEZE_SKIP_PERCENT) {
                periodicFreezeActive = true;
                durationMs = randomInclusiveLocked(MIN_PERIODIC_FREEZE_MS, MAX_PERIODIC_FREEZE_MS);
                run = true;
            }
        }

        if (!run) return;
        GreatEngine.instance().freezeCore().setHoldTrigger(true);
        GlobalRobotOutboundThrottle.instance().startLightForPeriodicFreeze();
        final int chosenDuration = durationMs;
        synchronized (lock) {
            periodicEnd = scheduler.schedule(this::finishPeriodicFreeze,
                    chosenDuration, TimeUnit.MILLISECONDS);
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
            // Periodic post-Freeze throttle keeps its fixed profile and ignores the visual card.
            GlobalRobotOutboundThrottle.instance().finishPeriodicFreezeAndStartPostThrottle();
        } else {
            GlobalRobotOutboundThrottle.instance().cancelActiveCycle();
        }
    }

    private int randomInclusiveLocked(int from, int to) {
        return from == to ? from : from + rng.nextInt(to - from + 1);
    }
}
