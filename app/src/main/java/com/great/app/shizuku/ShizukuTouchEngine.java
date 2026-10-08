package com.great.app.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.great.app.config.TriggerSettingsStore;
import com.great.app.core.GreatEngine;

import rikka.shizuku.Shizuku;

/** Process-wide bridge to GREAT's Shizuku trigger UserService. */
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

    private final ITouchTriggerCallback triggerCallback = new ITouchTriggerCallback.Stub() {
        @Override public void onTriggerChanged(boolean active, long eventNanos) {
            GreatEngine.instance().freezeCore().setHoldTrigger(active);
        }

        @Override public void onMonitorStatus(String nextStatus) {
            if (nextStatus != null && !nextStatus.isBlank()) {
                status = nextStatus;
                publish();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            IShizukuTouchService service = IShizukuTouchService.Stub.asInterface(binder);
            remote = service;
            try {
                int uid = service.getUid();
                ready = uid == 2000 || uid == 0;
                if (!ready) {
                    status = "Wrong Shizuku uid: " + uid;
                } else {
                    service.setTriggerCallback(triggerCallback);
                    syncTriggerGeometryInternal(service);
                    status = "Ready • " + service.getBackend();
                }
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
            try {
                syncTriggerGeometryInternal(remote);
            } catch (Throwable e) {
                status = "Trigger geometry sync failed";
            }
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
                    .version(3);
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

    public synchronized void syncTriggerGeometry(Context context) {
        if (appContext == null && context != null) appContext = context.getApplicationContext();
        IShizukuTouchService service = remote;
        if (!ready || service == null) return;
        try {
            syncTriggerGeometryInternal(service);
        } catch (Throwable e) {
            status = "Trigger geometry sync failed";
            publish();
        }
    }

    private void syncTriggerGeometryInternal(IShizukuTouchService service) throws Exception {
        Context context = appContext;
        if (context == null || service == null) return;

        TriggerSettingsStore settings = new TriggerSettingsStore(context);
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int width;
        int height;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            width = Math.max(1, bounds.width());
            height = Math.max(1, bounds.height());
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            width = Math.max(1, metrics.widthPixels);
            height = Math.max(1, metrics.heightPixels);
        }

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float dpi = (dm.xdpi + dm.ydpi) / 2f;
        if (!Float.isFinite(dpi) || dpi < 100f || dpi > 1000f) dpi = dm.densityDpi;
        float diameterPx = Math.max(1f, settings.diameterCm() * dpi / 2.54f);
        float centerX = settings.centerXFraction() * width;
        float centerY = settings.centerYFraction() * height;

        int rotation = 0;
        try {
            Display display = context.getDisplay();
            if (display != null) rotation = display.getRotation();
        } catch (Throwable ignored) { }

        service.configureTrigger(settings.enabled(), centerX, centerY, diameterPx / 2f,
                width, height, rotation, settings.revision());
    }

    /** Legacy forwarding path retained only for compatibility; locked trigger mode never calls it. */
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
            status = "Legacy touch forwarding failed";
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
        if (service != null) {
            try { service.setTriggerCallback(null); } catch (Throwable ignored) { }
        }
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
