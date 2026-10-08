package com.great.app.shizuku;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku UserService for GREAT's trigger engine.
 *
 * The locked overlay itself stays NOT_TOUCHABLE. This service owns the current trigger geometry
 * and exposes a callback contract for the future passive touch backend. The current build performs
 * only one-shot touch-source discovery; it does not capture live device-wide touch events yet.
 */
public final class GreatTouchUserService extends IShizukuTouchService.Stub {
    private static final String INPUT_DESCRIPTOR = "android.hardware.input.IInputManager";
    private static final int FALLBACK_INJECT_TRANSACTION = 11;
    private static final int INJECT_MODE_WAIT_FOR_RESULT = 1;

    private IBinder inputBinder;
    private int injectTransaction = -1;
    private String backend = "Not initialized";

    private volatile ITouchTriggerCallback triggerCallback;
    private volatile boolean triggerEnabled;
    private volatile float centerX;
    private volatile float centerY;
    private volatile float radiusPx;
    private volatile int screenWidth = 1;
    private volatile int screenHeight = 1;
    private volatile int rotation;
    private volatile long triggerRevision = -1L;

    public GreatTouchUserService() {
        initializeBackend();
    }

    @Override public void destroy() {
        triggerCallback = null;
        System.exit(0);
    }

    @Override public int getUid() {
        return Process.myUid();
    }

    @Override public synchronized String getBackend() {
        if (inputBinder == null || !inputBinder.isBinderAlive()) initializeBackend();
        TouchSourceProbe.Result probe = TouchSourceProbe.run();
        return backend + " • " + probe.detail()
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

    @Override public boolean injectMotion(int action, long downTime, long eventTime,
                                          float x, float y, int displayId) {
        IBinder binder;
        int transaction;
        synchronized (this) {
            if (inputBinder == null || !inputBinder.isBinderAlive()) initializeBackend();
            binder = inputBinder;
            transaction = injectTransaction;
        }
        if (binder == null || transaction <= 0) return false;

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                1.0f,
                1.0f,
                0,
                1.0f,
                1.0f,
                -1,
                0
        );
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        applyDisplayId(event, displayId);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INPUT_DESCRIPTOR);
            data.writeTypedObject(event, 0);
            data.writeInt(INJECT_MODE_WAIT_FOR_RESULT);
            if (!binder.transact(transaction, data, reply, 0)) return false;
            reply.readException();
            return reply.dataAvail() <= 0 || reply.readInt() != 0;
        } catch (Throwable ignored) {
            synchronized (this) {
                backend = "IInputManager injection failed";
            }
            return false;
        } finally {
            reply.recycle();
            data.recycle();
            event.recycle();
        }
    }

    private static void applyDisplayId(MotionEvent event, int displayId) {
        if (displayId < 0) return;
        try {
            Method method = event.getClass().getMethod("setDisplayId", int.class);
            method.invoke(event, displayId);
        } catch (Throwable ignored) { }
    }

    private synchronized void initializeBackend() {
        try {
            IBinder binder = SystemServiceHelper.getSystemService("input");
            if (binder == null) {
                inputBinder = null;
                injectTransaction = -1;
                backend = "InputManager binder unavailable • uid=" + Process.myUid();
                return;
            }

            Integer code = SystemServiceHelper.getTransactionCode(
                    "android.hardware.input.IInputManager$Stub", "injectInputEvent");
            inputBinder = binder;
            injectTransaction = code != null && code > 0 ? code : FALLBACK_INJECT_TRANSACTION;
            backend = "Shizuku UserService ready • uid=" + Process.myUid();
        } catch (Throwable e) {
            inputBinder = null;
            injectTransaction = -1;
            String message = e.getMessage();
            backend = "Input backend error: " + (message == null ? e.getClass().getSimpleName() : message);
        }
    }
}
