package com.great.app.shizuku;

import android.os.Process;

/**
 * Shizuku UserService for GREAT's passive trigger engine.
 *
 * It owns the latest trigger geometry and callback contract. The visual circle remains
 * FLAG_NOT_TOUCHABLE, so the physical touch continues directly to the app/game below.
 */
public final class GreatTouchUserService extends IShizukuTouchService.Stub {
    private volatile ITouchTriggerCallback triggerCallback;
    private volatile boolean triggerEnabled;
    private volatile float centerX;
    private volatile float centerY;
    private volatile float radiusPx;
    private volatile int screenWidth = 1;
    private volatile int screenHeight = 1;
    private volatile int rotation;
    private volatile long triggerRevision = -1L;

    @Override public void destroy() {
        triggerCallback = null;
        System.exit(0);
    }

    @Override public int getUid() {
        return Process.myUid();
    }

    @Override public synchronized String getBackend() {
        TouchSourceProbe.Result probe = TouchSourceProbe.run();
        return "Shizuku passive trigger service • uid=" + Process.myUid()
                + " • " + probe.detail()
                + " • geometryRev=" + triggerRevision;
    }

    @Override public synchronized void configureTrigger(boolean enabled,
                                                        float nextCenterX, float nextCenterY,
                                                        float nextRadiusPx,
                                                        int nextScreenWidth, int nextScreenHeight,
                                                        int nextRotation, long revision) {
        if (revision < triggerRevision) return;
        triggerEnabled = enabled;
        centerX = nextCenterX;
        centerY = nextCenterY;
        radiusPx = Math.max(0f, nextRadiusPx);
        screenWidth = Math.max(1, nextScreenWidth);
        screenHeight = Math.max(1, nextScreenHeight);
        rotation = nextRotation & 3;
        triggerRevision = revision;

        ITouchTriggerCallback callback = triggerCallback;
        if (callback != null) {
            try {
                callback.onMonitorStatus("Geometry synced • rev=" + revision
                        + " • " + Math.round(centerX) + "," + Math.round(centerY)
                        + " • r=" + Math.round(radiusPx)
                        + " • " + screenWidth + "x" + screenHeight
                        + " • rot=" + rotation
                        + " • " + (triggerEnabled ? "ON" : "OFF"));
            } catch (Throwable ignored) { }
        }
    }

    @Override public void setTriggerCallback(ITouchTriggerCallback callback) {
        triggerCallback = callback;
    }

    @Override public long getTriggerRevision() {
        return triggerRevision;
    }
}
