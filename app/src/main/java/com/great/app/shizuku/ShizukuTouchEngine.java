package com.great.app.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.view.MotionEvent;

import com.great.app.core.GreatEngine;

import rikka.shizuku.Shizuku;

/** Process-wide bridge to GREAT's Shizuku input UserService. */
public final class ShizukuTouchEngine {
    public interface Listener {
        void onStateChanged(boolean ready, String status);
    }

    private static final ShizukuTouchEngine INSTANCE = new ShizukuTouchEngine();

    private volatile IShizukuTouchService remote;
    private volatile boolean ready;
    private volatile boolean binding;
    private volatile String status = "Touch engine idle";
    private volatile Listener listener;
    private Shizuku.UserServiceArgs args;
    private Context appContext;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            IShizukuTouchService service = IShizukuTouchService.Stub.asInterface(binder);
            remote = service;
            try {
                int uid = service.getUid();
                String backend = service.getBackend();
                ready = uid == 2000 || uid == 0;
                status = ready ? "Ready • " + backend : "Wrong Shizuku uid: " + uid;
            } catch (Throwable e) {
                remote = null;
                ready = false;
                status = "Touch engine connection failed";
            }
            publish();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            remote = null;
            ready = false;
            status = "Shizuku touch service disconnected";
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
            publish();
        }
    };

    public static ShizukuTouchEngine instance() { return INSTANCE; }

    private ShizukuTouchEngine() { }

    public synchronized void ensureBound(Context context, Listener listener) {
        this.listener = listener;
        if (appContext == null) appContext = context.getApplicationContext();
        if (ready && remote != null) {
            publish();
            return;
        }
        if (binding) return;

        if (!Shizuku.pingBinder()) {
            status = "Shizuku service unavailable";
            ready = false;
            publish();
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status = "Shizuku permission required";
                ready = false;
                publish();
                return;
            }
        } catch (Throwable e) {
            status = "Cannot read Shizuku permission";
            ready = false;
            publish();
            return;
        }

        if (args == null) {
            args = new Shizuku.UserServiceArgs(new ComponentName(
                    appContext.getPackageName(), GreatTouchUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("great_touch")
                    .debuggable(false)
                    .version(1);
        }

        try {
            binding = true;
            status = "Starting Shizuku touch service…";
            publish();
            Shizuku.bindUserService(args, connection);
        } catch (Throwable e) {
            binding = false;
            ready = false;
            status = "Cannot start Shizuku touch service";
            publish();
        }
    }

    public boolean forward(MotionEvent event, int displayId) {
        IShizukuTouchService service = remote;
        if (!ready || service == null || event == null) return false;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
        try {
            return service.injectMotion(action, event.getDownTime(), event.getEventTime(),
                    event.getRawX(), event.getRawY(), displayId);
        } catch (Throwable e) {
            ready = false;
            status = "Touch forwarding failed";
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
            publish();
            return false;
        }
    }

    public synchronized void unbind() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        IShizukuTouchService service = remote;
        remote = null;
        ready = false;
        binding = false;
        if (args != null) {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (Throwable ignored) { }
        } else if (service != null) {
            try { service.destroy(); } catch (Throwable ignored) { }
        }
        status = "Touch engine stopped";
        publish();
    }

    public boolean ready() { return ready; }
    public String status() { return status; }

    private void publish() {
        Listener l = listener;
        if (l != null) l.onStateChanged(ready, status);
    }
}
