package com.great.app.ui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.GreatEngine;
import com.great.app.monitor.PixelTriggerMonitorEngine;

import java.nio.ByteBuffer;

/**
 * Tiny visual sensor based on PixelTrigger's right-side monitor model.
 * The running overlay never receives touches; MediaProjection samples only five RGB points.
 */
public final class FreezeMonitorService extends Service {
    public static final String ACTION_START = "com.great.app.action.START_FREEZE_MONITOR";
    public static final String ACTION_EDIT = "com.great.app.action.EDIT_FREEZE_MONITOR";
    public static final String ACTION_LOCK = "com.great.app.action.LOCK_FREEZE_MONITOR";
    public static final String ACTION_STOP = "com.great.app.action.STOP_FREEZE_MONITOR";
    public static final String ACTION_MONITORING_ON = "com.great.app.action.FREEZE_MONITORING_ON";
    public static final String ACTION_MONITORING_OFF = "com.great.app.action.FREEZE_MONITORING_OFF";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";

    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "great_visual_monitor";
    // Waiting for the first valid white baseline is visually red because white is currently absent.
    // The PixelTrigger arming rule itself is unchanged: Freeze cannot fire until white has armed it.
    private static final int COLOR_WAITING = Color.rgb(255, 80, 95);
    private static final int COLOR_ARMED = Color.rgb(60, 220, 120);
    private static final int COLOR_FIRED = Color.rgb(255, 80, 95);
    private static final int COLOR_MONITORING_OFF = Color.rgb(145, 150, 160);
    private static final int COLOR_NOT_READY = Color.rgb(220, 85, 255);

    private static volatile boolean running;
    private static volatile String publicStatus = "Monitor stopped";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private MonitorSettingsStore settings;
    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private PixelTriggerMonitorEngine engine;

    private MonitorView monitorView;
    private WindowManager.LayoutParams overlayParams;
    private DragState dragState;
    private boolean editMode;
    private boolean shuttingDown;
    private volatile int monitorColor = COLOR_NOT_READY;
    private int captureWidth;
    private int captureHeight;

    public static boolean isRunning() { return running; }
    public static String status() { return publicStatus; }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        settings = new MonitorSettingsStore(this);
        engine = new PixelTriggerMonitorEngine(this::onDetectorState);
        captureThread = new HandlerThread("GREAT-Visual-Monitor");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        ensureNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            settings.setEnabled(false);
            stopMonitor();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = parcelableIntent(intent, EXTRA_RESULT_DATA);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                setNotReady("Screen capture permission required");
                stopSelf();
                return START_NOT_STICKY;
            }
            settings.setEnabled(true);
            startMonitor(resultCode, resultData);
            return START_STICKY;
        }

        if (ACTION_MONITORING_OFF.equals(action)) {
            if (!running) return START_NOT_STICKY;
            settings.setMonitoringEnabled(false);
            pauseMonitoring();
            return START_STICKY;
        }

        if (ACTION_MONITORING_ON.equals(action)) {
            if (!running) return START_NOT_STICKY;
            settings.setMonitoringEnabled(true);
            resumeMonitoring();
            return START_STICKY;
        }

        if (ACTION_EDIT.equals(action)) {
            if (!running) return START_NOT_STICKY;
            setEditMode(true);
            return START_STICKY;
        }

        if (ACTION_LOCK.equals(action)) {
            if (!running) return START_NOT_STICKY;
            settings.setEnabled(true);
            setEditMode(false);
            return START_STICKY;
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    private void startMonitor(int resultCode, Intent resultData) {
        if (!Settings.canDrawOverlays(this)) {
            setNotReady("Overlay permission required");
            stopSelf();
            return;
        }
        startAsForeground();
        if (projection == null) {
            try {
                projection = projectionManager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        mainHandler.post(() -> {
                            if (shuttingDown) return;
                            setNotReady("Screen capture stopped");
                            GreatEngine.instance().freezeCore().setHoldTrigger(false);
                            stopSelf();
                        });
                    }
                }, mainHandler);
                createCapture();
            } catch (Throwable e) {
                setNotReady("Screen capture failed");
                stopSelf();
                return;
            }
        }
        ensureOverlay();
        running = true;
        editMode = false;
        if (overlayParams != null) {
            overlayParams.flags = lockedFlags();
            try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
        }
        engine.reset();
        if (settings.monitoringEnabled()) {
            applyDetectorState(engine.state());
        } else {
            pauseMonitoring();
        }
    }

    private void createCapture() {
        Point size = screenSize();
        captureWidth = Math.max(1, size.x);
        captureHeight = Math.max(1, size.y);
        int density = getResources().getDisplayMetrics().densityDpi;
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "GREAT-Visual-Monitor",
                captureWidth,
                captureHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
        if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay unavailable");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || editMode || !settings.enabled() || !settings.monitoringEnabled() || !running) return;
            PixelTriggerMonitorEngine.Sample sample = sampleImage(image);
            if (sample != null) engine.process(sample);
        } catch (Throwable ignored) {
            mainHandler.post(() -> setNotReady("Monitor frame unavailable"));
        } finally {
            if (image != null) image.close();
        }
    }

    private PixelTriggerMonitorEngine.Sample sampleImage(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        if (pixelStride < 3 || rowStride <= 0) return null;

        Rect crop = image.getCropRect();
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) return null;
        Point screen = screenSize();
        if (screen.x <= 0 || screen.y <= 0) return null;

        float centerScreenX = settings.centerXFraction() * screen.x;
        float centerScreenY = settings.centerYFraction() * screen.y;
        int cx = crop.left + Math.round(centerScreenX * crop.width() / screen.x);
        int cy = crop.top + Math.round(centerScreenY * crop.height() / screen.y);

        float sampleDiameterScreenPx = sensorDiameterPx();
        int rx = Math.max(1, Math.round((sampleDiameterScreenPx * crop.width() / screen.x) / 2f));
        int ry = Math.max(1, Math.round((sampleDiameterScreenPx * crop.height() / screen.y) / 2f));

        int[][] points = {
                {cx, cy}, {cx - rx, cy}, {cx + rx, cy}, {cx, cy - ry}, {cx, cy + ry}
        };
        ByteBuffer buffer = plane.getBuffer();
        int[] packed = new int[PixelTriggerMonitorEngine.MAX_PROBE_POINTS];
        int count = 0;
        for (int[] p : points) {
            int x = clamp(p[0], crop.left, crop.right - 1);
            int y = clamp(p[1], crop.top, crop.bottom - 1);
            long offsetLong = (long) y * rowStride + (long) x * pixelStride;
            if (offsetLong < 0 || offsetLong + 2 >= buffer.limit()) continue;
            int offset = (int) offsetLong;
            int r = buffer.get(offset) & 0xff;
            int g = buffer.get(offset + 1) & 0xff;
            int b = buffer.get(offset + 2) & 0xff;
            packed[count++] = (r << 16) | (g << 8) | b;
        }
        return count == 0 ? null : new PixelTriggerMonitorEngine.Sample(packed, count);
    }

    private void onDetectorState(PixelTriggerMonitorEngine.State state) {
        if (!settings.monitoringEnabled() || editMode || !running) return;
        applyDetectorState(state);
    }

    private void applyDetectorState(PixelTriggerMonitorEngine.State state) {
        switch (state) {
            case WAITING_FOR_WHITE -> {
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                setVisual(COLOR_WAITING, "Waiting for white • not armed");
            }
            case ARMED -> {
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                setVisual(COLOR_ARMED, "ARMED • white detected • Freeze OFF");
            }
            case FIRED -> {
                GreatEngine.instance().freezeCore().setHoldTrigger(true);
                setVisual(COLOR_FIRED, "FIRED • white lost • Freeze ON");
            }
        }
    }

    private void pauseMonitoring() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (monitorView != null && overlayParams != null && !editMode) {
            overlayParams.flags = lockedFlags();
            try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
        }
        setVisual(COLOR_MONITORING_OFF, "Monitoring OFF");
    }

    private void resumeMonitoring() {
        if (!running) return;
        if (editMode) setEditMode(false);
        applyDetectorState(engine.state());
    }

    private void resetDetector(String status) {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        engine.reset();
        if (settings.monitoringEnabled()) setVisual(COLOR_WAITING, status);
        else setVisual(COLOR_MONITORING_OFF, "Monitoring OFF");
    }

    private void setNotReady(String status) {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        setVisual(COLOR_NOT_READY, status);
    }

    private void setVisual(int color, String status) {
        monitorColor = color;
        publicStatus = status;
        mainHandler.post(() -> {
            if (monitorView != null) monitorView.invalidate();
        });
    }

    private void ensureOverlay() {
        if (monitorView != null) return;
        monitorView = new MonitorView();
        monitorView.setOnTouchListener(this::onMonitorTouch);
        int touchSize = Math.max(dp(48), Math.round(sensorDiameterPx()) + dp(30));
        Point screen = screenSize();
        overlayParams = new WindowManager.LayoutParams(
                touchSize,
                touchSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                lockedFlags(),
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = clamp(Math.round(settings.centerXFraction() * screen.x - touchSize / 2f),
                0, Math.max(0, screen.x - touchSize));
        overlayParams.y = clamp(Math.round(settings.centerYFraction() * screen.y - touchSize / 2f),
                0, Math.max(0, screen.y - touchSize));
        windowManager.addView(monitorView, overlayParams);
    }

    private void setEditMode(boolean edit) {
        editMode = edit;
        dragState = null;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (edit) engine.reset();
        if (monitorView == null || overlayParams == null) return;
        overlayParams.flags = edit ? editFlags() : lockedFlags();
        try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
        if (edit) {
            setVisual(COLOR_MONITORING_OFF, "Edit monitor position");
        } else {
            resetDetector("Waiting for white • not armed");
        }
    }

    private boolean onMonitorTouch(View view, MotionEvent event) {
        if (!editMode || overlayParams == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragState = new DragState(overlayParams.x, overlayParams.y, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragState == null) return false;
                moveOverlay(
                        dragState.startX + Math.round(event.getRawX() - dragState.touchX),
                        dragState.startY + Math.round(event.getRawY() - dragState.touchY));
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragState != null) saveCenter();
                dragState = null;
                return true;
            }
            default -> { return true; }
        }
    }

    private void moveOverlay(int x, int y) {
        if (monitorView == null || overlayParams == null) return;
        Point screen = screenSize();
        overlayParams.x = clamp(x, 0, Math.max(0, screen.x - overlayParams.width));
        overlayParams.y = clamp(y, 0, Math.max(0, screen.y - overlayParams.height));
        try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
    }

    private void saveCenter() {
        if (overlayParams == null) return;
        Point screen = screenSize();
        if (screen.x <= 0 || screen.y <= 0) return;
        settings.setCenterFractions(
                (overlayParams.x + overlayParams.width / 2f) / screen.x,
                (overlayParams.y + overlayParams.height / 2f) / screen.y);
    }

    private float sensorDiameterPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpi = (dm.xdpi + dm.ydpi) / 2f;
        if (!Float.isFinite(dpi) || dpi < 100f || dpi > 1000f) dpi = dm.densityDpi;
        return Math.max(1f, PixelTriggerMonitorEngine.SENSOR_DIAMETER_MM * dpi / 25.4f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new Point(Math.max(1, bounds.width()), Math.max(1, bounds.height()));
        }
        Point point = new Point();
        windowManager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    private static int baseFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    private static int lockedFlags() {
        return baseFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    private static int editFlags() {
        return baseFlags() & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("GREAT visual monitor")
                .setContentText("Monitoring one tiny screen region for Freeze")
                .setOngoing(true)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GREAT visual monitor", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Required while GREAT monitors the selected screen point");
        nm.createNotificationChannel(channel);
    }

    private void stopMonitor() {
        if (shuttingDown) return;
        shuttingDown = true;
        running = false;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        publicStatus = "Monitor stopped";
        removeOverlay();
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) { }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) { }
            imageReader = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try { p.stop(); } catch (Throwable ignored) { }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void removeOverlay() {
        if (monitorView != null) {
            try { windowManager.removeView(monitorView); } catch (Throwable ignored) { }
        }
        monitorView = null;
        overlayParams = null;
        editMode = false;
        dragState = null;
    }

    @Override public void onDestroy() {
        running = false;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        removeOverlay();
        if (!shuttingDown) {
            shuttingDown = true;
            if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Throwable ignored) { }
            if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) { }
            if (projection != null) try { projection.stop(); } catch (Throwable ignored) { }
        }
        if (captureThread != null) captureThread.quitSafely();
        publicStatus = "Monitor stopped";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @SuppressWarnings("deprecation")
    private static Intent parcelableIntent(Intent source, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(key, Intent.class);
        }
        return source.getParcelableExtra(key);
    }

    private final class MonitorView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        MonitorView() {
            super(FreezeMonitorService.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            stroke.setColor(monitorColor);
            float radius = Math.max(0.5f, sensorDiameterPx() / 2f);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, stroke);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DragState {
        final int startX;
        final int startY;
        final float touchX;
        final float touchY;

        DragState(int startX, int startY, float touchX, float touchY) {
            this.startX = startX;
            this.startY = startY;
            this.touchX = touchX;
            this.touchY = touchY;
        }
    }
}
