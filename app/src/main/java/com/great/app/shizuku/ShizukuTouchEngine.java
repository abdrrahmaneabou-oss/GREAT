package com.great.app.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;

import com.great.app.core.GreatEngine;

import rikka.shizuku.Shizuku;

/** Bridge for the circle trigger. The app receives only pressed/released state, never touch paths. */
public final class ShizukuTouchEngine {
    public interface Listener { void onStateChanged(boolean ready, String status); }

    private static final ShizukuTouchEngine INSTANCE = new ShizukuTouchEngine();
    private volatile IShizukuTouchService remote;
    private volatile boolean ready;
    private volatile boolean binding;
    private volatile String status = "Touch trigger idle";
    private volatile Listener listener;
    private volatile Snapshot snapshot = Snapshot.off();
    private volatile long lastLatencyMicros = -1L;
    private long revision;
    private Shizuku.UserServiceArgs args;
    private Context appContext;

    private final ITouchTriggerCallback callback = new ITouchTriggerCallback.Stub() {
        @Override public void onTriggerChanged(boolean active, long eventNanos) {
            long now = SystemClock.elapsedRealtimeNanos();
            if (eventNanos > 0L && now >= eventNanos) lastLatencyMicros = (now - eventNanos) / 1_000L;
            GreatEngine.instance().freezeCore().setHoldTrigger(active);
        }

        @Override public void onMonitorStatus(String value) {
            if (value != null) status = value;
            publish();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            IShizukuTouchService service = IShizukuTouchService.Stub.asInterface(binder);
            try {
                int uid = service.getUid();
                if (uid != 2000 && uid != 0) {
                    ready = false;
                    remote = null;
                    status = "Wrong Shizuku uid: " + uid;
                } else {
                    remote = service;
                    ready = true;
                    service.setCallback(callback);
                    status = "Ready • " + service.getBackend();
                    send(service, snapshot);
                }
            } catch (Throwable e) {
                ready = false;
                remote = null;
                status = "Touch trigger connection failed";
            }
            publish();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            ready = false;
            remote = null;
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
            status = "Shizuku touch trigger disconnected";
            publish();
        }
    };

    public static ShizukuTouchEngine instance() { return INSTANCE; }
    private ShizukuTouchEngine() { }

    public synchronized void ensureBound(Context context, Listener nextListener) {
        listener = nextListener;
        if (appContext == null) appContext = context.getApplicationContext();
        if (ready && remote != null) { publish(); return; }
        if (binding) return;
        if (!Shizuku.pingBinder()) {
            ready = false;
            status = "Shizuku service unavailable";
            publish();
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ready = false;
                status = "Shizuku permission required";
                publish();
                return;
            }
        } catch (Throwable e) {
            ready = false;
            status = "Cannot read Shizuku permission";
            publish();
            return;
        }
        if (args == null) {
            args = new Shizuku.UserServiceArgs(new ComponentName(
                    appContext.getPackageName(), GreatTouchUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("great_touch")
                    .debuggable(false)
                    .version(2);
        }
        try {
            binding = true;
            status = "Starting Shizuku touch trigger…";
            publish();
            Shizuku.bindUserService(args, connection);
        } catch (Throwable e) {
            binding = false;
            ready = false;
            status = "Cannot start Shizuku touch trigger";
            publish();
        }
    }

    public synchronized void updateTrigger(boolean enabled, float centerX, float centerY,
                                           float radiusPx, int screenWidth, int screenHeight,
                                           int rotation) {
        long nextRevision = Math.max(revision + 1L, SystemClock.elapsedRealtimeNanos());
        revision = nextRevision;
        snapshot = new Snapshot(enabled, centerX, centerY, radiusPx,
                Math.max(1, screenWidth), Math.max(1, screenHeight), rotation & 3, nextRevision);
        IShizukuTouchService service = remote;
        if (ready && service != null) {
            try { send(service, snapshot); }
            catch (Throwable e) {
                ready = false;
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                status = "Circle sync failed";
                publish();
            }
        } else if (!enabled) {
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
        }
    }

    private static void send(IShizukuTouchService service, Snapshot s) throws Exception {
        service.updateTriggerGeometry(s.centerX, s.centerY, s.radiusPx,
                s.screenWidth, s.screenHeight, s.rotation, s.revision);
        service.setTriggerEnabled(s.enabled, s.revision);
        if (s.enabled) service.startMonitor();
        else service.stopMonitor();
    }

    public synchronized void unbind() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        IShizukuTouchService service = remote;
        remote = null;
        ready = false;
        binding = false;
        if (service != null) {
            try { service.stopMonitor(); } catch (Throwable ignored) { }
        }
        if (args != null) {
            try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) { }
        }
        status = "Touch trigger stopped";
        publish();
    }

    public boolean ready() { return ready; }
    public String status() { return status; }
    public long lastLatencyMicros() { return lastLatencyMicros; }

    private void publish() {
        Listener current = listener;
        if (current != null) current.onStateChanged(ready, status);
    }

    private record Snapshot(boolean enabled, float centerX, float centerY, float radiusPx,
                            int screenWidth, int screenHeight, int rotation, long revision) {
        static Snapshot off() { return new Snapshot(false, 0f, 0f, 0f, 1, 1, 0, 0L); }
    }
}
