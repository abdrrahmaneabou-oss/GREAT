package com.great.app.shizuku;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku UserService used only to forward touches that GREAT's visible trigger
 * overlay receives. It does not monitor device-wide input.
 */
public final class GreatTouchUserService extends IShizukuTouchService.Stub {
    private static final String INPUT_DESCRIPTOR = "android.hardware.input.IInputManager";
    private static final int FALLBACK_INJECT_TRANSACTION = 11;
    private static final int INJECT_MODE_WAIT_FOR_RESULT = 1;

    private IBinder inputBinder;
    private int injectTransaction = -1;
    private String backend = "Not initialized";

    public GreatTouchUserService() {
        initializeBackend();
    }

    @Override public void destroy() {
        System.exit(0);
    }

    @Override public int getUid() {
        return Process.myUid();
    }

    @Override public synchronized String getBackend() {
        if (inputBinder == null || !inputBinder.isBinderAlive()) initializeBackend();
        return backend;
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
                backend = "InputManager binder unavailable";
                return;
            }

            Integer code = SystemServiceHelper.getTransactionCode(
                    "android.hardware.input.IInputManager$Stub", "injectInputEvent");
            inputBinder = binder;
            injectTransaction = code != null && code > 0 ? code : FALLBACK_INJECT_TRANSACTION;
            backend = "Direct IInputManager binder • tx=" + injectTransaction + " • uid=" + Process.myUid();
        } catch (Throwable e) {
            inputBinder = null;
            injectTransaction = -1;
            String message = e.getMessage();
            backend = "Input backend error: " + (message == null ? e.getClass().getSimpleName() : message);
        }
    }
}
