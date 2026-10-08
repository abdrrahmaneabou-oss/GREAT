package com.great.app.shizuku;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;

import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku UserService. The live trigger backend is not enabled here yet; getBackend() performs a
 * one-shot capability probe so GREAT can verify that the Shizuku shell can see a multi-touch
 * touchscreen before we attach any runtime trigger logic.
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
        return backend + " • " + probeTouchSource();
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

    private static String probeTouchSource() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getevent", "-pl")
                    .redirectErrorStream(true)
                    .start();
            boolean hasMtX = false;
            boolean hasMtY = false;
            String deviceName = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("name:") && (lower.contains("touch") || lower.contains("goodix")
                            || lower.contains("synaptics") || lower.contains("fts"))) {
                        int first = line.indexOf('"');
                        int last = line.lastIndexOf('"');
                        if (first >= 0 && last > first) deviceName = line.substring(first + 1, last);
                    }
                    if (line.contains("ABS_MT_POSITION_X")) hasMtX = true;
                    if (line.contains("ABS_MT_POSITION_Y")) hasMtY = true;
                }
            }
            try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (hasMtX && hasMtY) {
                return "multi-touch source visible" + (deviceName == null ? "" : " • " + deviceName);
            }
            return "no multi-touch source detected";
        } catch (Throwable e) {
            String message = e.getMessage();
            return "touch probe failed: " + (message == null ? e.getClass().getSimpleName() : message);
        } finally {
            if (process != null) process.destroy();
        }
    }
}
