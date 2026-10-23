package com.great.app.ui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import com.great.app.monitor.CaptureLivenessPolicy;
import com.great.app.monitor.PixelTriggerMonitorEngine;
import com.great.app.monitor.ProbeReticleGeometry;
import com.great.app.monitor.RobotFreezeOrchestrator;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Tiny PixelTrigger-style visual sensor. Its locked overlay never consumes game touches. */
public final class FreezeMonitorService extends Service {
    public static final String ACTION_START = "com.great.app.action.START_FREEZE_MONITOR";
    public static final String ACTION_EDIT = "com.great.app.action.EDIT_FREEZE_MONITOR";
    public static final String ACTION_LOCK = "com.great.app.action.LOCK_FREEZE_MONITOR";
    public static final String ACTION_STOP = "com.great.app.action.STOP_FREEZE_MONITOR";
    public static final String ACTION_MONITORING_ON = "com.great.app.action.FREEZE_MONITORING_ON";
    public static final String ACTION_REFRESH_TIMEOUT = "com.great.app.action.REFRESH_ROBOT_TIMEOUT";
    public static final String ACTION_MONITORING_OFF = "com.great.app.action.FREEZE_MONITORING_OFF";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    public static final String EXTRA_EDIT_AFTER_START = "edit_after_projection_start";

    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "great_visual_monitor";
    private static final String RECOVERY_CHANNEL_ID = "great_visual_monitor_recovery";
    private static final int RECOVERY_NOTIFICATION_ID = 4109;
    private volatile long lastNotificationUpdateNanos;
    private static final long HEALTH_INTERVAL_MS = 3_000L;

    private static final int COLOR_WAITING = Color.rgb(255, 184, 77);
    private static final int COLOR_ARMED = Color.rgb(60, 220, 120);
    private static final int COLOR_FIRED = Color.rgb(255, 80, 95);
    private static final int COLOR_PAUSED = Color.rgb(145, 150, 160);
    private static final int COLOR_NOT_READY = Color.rgb(220, 85, 255);

    private static volatile boolean running;
    private static volatile boolean editing;
    private static volatile boolean monitoringActive;
    private static volatile boolean recoveryNeeded;
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
    private boolean shuttingDown;
    private volatile int monitorColor = COLOR_NOT_READY;
    private Runnable freezeTimeout;
    private final AtomicLong timeoutSerial = new AtomicLong();
    private volatile long visualFiredAtNanos;
    private volatile int captureWidth;
    private volatile int captureHeight;
    private volatile boolean captureResizePending;
    private volatile boolean captureSessionEnded;
    private static volatile long lastValidFrameNanos;
    private static volatile String lastProbeReading = "no valid RGB frame";
    private static volatile long capturedSampleCount;
    private volatile long lastImageFrameNanos;
    private volatile int frameFailures;
    private volatile int surfaceRecoveryAttempts;
    private volatile long lastRecoveryAttemptNanos;
    private volatile boolean surfaceRecoveryPending;
    private int resizeFailures;
    // MediaProjection is change-driven on some phones. A motionless white screen
    // may deliver just ONE frame, while the detector expects three confirmations.
    // Confirm that observation over a short time window rather than treating
    // the absence of two redundant frames as absence of white.
    private static final long WHITE_CONFIRM_INTERVAL_MS = 55L;
    private static final long WHITE_CONFIRM_MAX_AGE_NS = TimeUnit.MILLISECONDS.toNanos(280);
    private volatile PixelTriggerMonitorEngine.Sample pendingWhite;
    private volatile long pendingWhiteAtNanos;
    private volatile int repeatedWhiteConfirms;
    private final Runnable confirmStationaryWhite = new Runnable() {
        @Override public void run() {
            if (shuttingDown || !running || !monitoringActive || editing
                    || !settings.enabled() || !settings.monitoringEnabled()
                    || pendingWhite == null
                    || System.nanoTime() - pendingWhiteAtNanos > WHITE_CONFIRM_MAX_AGE_NS
                    || repeatedWhiteConfirms >= 2) return;
            synchronized (engine) {
                if (engine.state() == PixelTriggerMonitorEngine.State.ARMED
                        || !pendingWhite.isArmingWhite()) return;
                engine.process(pendingWhite);
            }
            repeatedWhiteConfirms++;
            if (repeatedWhiteConfirms < 2) {
                captureHandler.postDelayed(this, WHITE_CONFIRM_INTERVAL_MS);
            }
        }
    };

    private void resetStationaryWhite() {
        pendingWhite = null;
        pendingWhiteAtNanos = 0L;
        repeatedWhiteConfirms = 0;
        if (captureHandler != null) captureHandler.removeCallbacks(confirmStationaryWhite);
    }

    private void queueStationaryWhite(PixelTriggerMonitorEngine.Sample sample) {
        captureHandler.removeCallbacks(confirmStationaryWhite);
        if (!sample.isArmingWhite()
                || engine.state() == PixelTriggerMonitorEngine.State.ARMED) {
            pendingWhite = null;
            return;
        }
        pendingWhite = sample;
        pendingWhiteAtNanos = System.nanoTime();
        repeatedWhiteConfirms = 0;
        captureHandler.postDelayed(confirmStationaryWhite, WHITE_CONFIRM_INTERVAL_MS);
    }

    private final Runnable captureWatchdog = new Runnable() {
        @Override public void run() {
            try {
                if (!shuttingDown && running && monitoringActive
                        && settings.enabled() && settings.monitoringEnabled()
                        && projection != null && virtualDisplay != null && !captureResizePending) {
                    long idleNs = System.nanoTime() - lastImageFrameNanos;
                    if (CaptureLivenessPolicy.shouldRecover(
                            idleNs, frameFailures, surfaceRecoveryAttempts,
                            System.nanoTime() - lastRecoveryAttemptNanos,
                            surfaceRecoveryPending)) {
                        surfaceRecoveryPending = true;
                        mainHandler.post(() -> recoverCaptureSurface(
                                frameFailures >= CaptureLivenessPolicy.FAILURE_TRIGGER
                                        ? "Repeated unusable images" : "No new captured frames"));
                    }
                }
            } finally {
                if (!shuttingDown) captureHandler.postDelayed(this, HEALTH_INTERVAL_MS);
            }
        }
    };

    public static boolean isRunning() { return running; }
    public static boolean isEditing() { return editing; }
    public static boolean isMonitoringActive() { return running && monitoringActive && !editing; }
    public static boolean isCaptureRecovering() { return isMonitoringActive() && recoveryNeeded; }
    public static String status() {
        if (!isMonitoringActive()) return publicStatus;
        long last = lastValidFrameNanos;
        if (last <= 0L) return publicStatus + " • awaiting first captured frame"
                + " • " + lastProbeReading;
        long age = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - last);
        if (age >= 10L) {
            // A static screen may legitimately yield no new frames. Don't imply permission loss.
            return publicStatus + " • last valid image " + age + "s ago"
                    + " • " + lastProbeReading;
        }
        return publicStatus + " • " + lastProbeReading;
    }

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
        captureHandler.postDelayed(captureWatchdog, HEALTH_INTERVAL_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            settings.setEnabled(false);
            settings.setMonitoringEnabled(false);
            clearRecoveryNotification();
            stopMonitor();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = parcelableIntent(intent, EXTRA_RESULT_DATA);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                captureSessionEnded = true;
                setNotReady("Screen capture permission required");
                showRecoveryNotification("Tap to grant screen capture access");
                stopSelf();
                return START_NOT_STICKY;
            }
            settings.setEnabled(true);
            startMonitor(resultCode, resultData);
            if (intent.getBooleanExtra(EXTRA_EDIT_AFTER_START, false) && running) enterEditMode();
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
        if (ACTION_REFRESH_TIMEOUT.equals(action)) {
            if (running && monitoringActive && !editing) {
                synchronized (engine) {
                    if (engine.state() == PixelTriggerMonitorEngine.State.FIRED) {
                        GreatEngine.instance().freezeCore()
                                .setVisualFreezeDeadlineMillis(settings.maxFreezeMillis());
                        scheduleFreezeTimeout();
                    }
                }
            }
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_EDIT.equals(action)) {
            if (!running) return START_NOT_STICKY;
            enterEditMode();
            return START_STICKY;
        }
        if (ACTION_LOCK.equals(action)) {
            if (!running) return START_NOT_STICKY;
            settings.setEnabled(true);
            settings.setMonitoringEnabled(true);
            leaveEditAndArm();
            return START_STICKY;
        }
        if (!running && settings.monitoringEnabled()) {
            publicStatus = "Monitoring requested • screen capture permission required";
            showRecoveryNotification("Tap to restart screen capture");
            stopSelf();
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    private void startMonitor(int resultCode, Intent resultData) {
        if (!Settings.canDrawOverlays(this)) {
            captureSessionEnded = true;
            setNotReady("Overlay permission required");
            showRecoveryNotification("Overlay permission required");
            stopSelf();
            return;
        }
        try {
            startAsForeground();
        } catch (RuntimeException error) {
            captureSessionEnded = true;
            setNotReady("Android refused foreground screen capture");
            showRecoveryNotification("Open GREAT to grant capture and foreground permissions");
            stopSelf();
            return;
        }
        if (projection == null) {
            try {
                projection = projectionManager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        mainHandler.post(() -> {
                            if (shuttingDown) return;
                            captureSessionEnded = true;
                            running = false;
                            monitoringActive = false;
                            cancelFreezeTimeout();
                            RobotFreezeOrchestrator.instance().resetVisualCycle();
                            GreatEngine.instance().freezeCore().setHoldTrigger(false);
                            setNotReady("Screen capture ended • grant permission to resume");
                            showRecoveryNotification("Screen capture ended • tap to authorize again");
                            stopSelf();
                        });
                    }
                }, mainHandler);
            } catch (Throwable e) {
                captureSessionEnded = true;
                setNotReady("Screen capture failed");
                showRecoveryNotification("Capture unavailable • tap to retry permission");
                stopSelf();
                return;
            }
        }
        try {
            ensureOverlay();
        } catch (RuntimeException error) {
            captureSessionEnded = true;
            setNotReady("Overlay unavailable • permission may have changed");
            showRecoveryNotification("GREAT needs overlay permission to monitor");
            stopSelf();
            return;
        }
        lastValidFrameNanos = 0L;
        lastProbeReading = "no valid RGB frame";
        capturedSampleCount = 0L;
        lastImageFrameNanos = System.nanoTime();
        frameFailures = 0;
        surfaceRecoveryAttempts = 0;
        lastRecoveryAttemptNanos = 0L;
        resizeFailures = 0;
        surfaceRecoveryPending = false;
        clearRecoveryNotification();
        captureSessionEnded = false;
        running = true;
        editing = false;
        monitoringActive = settings.monitoringEnabled();
        lockOverlay();
        resetStationaryWhite();
        synchronized (engine) { engine.reset(); }
        if (monitoringActive) {
            // The detector must be ON before createVirtualDisplay can emit its
            // first frame. Some Android devices emit one frame for a static
            // scene, and the previous order threw that one frame away.
            setVisual(COLOR_NOT_READY, "Awaiting first captured image • monitor ON");
        } else {
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
            setVisual(COLOR_PAUSED, "Monitoring OFF");
        }
        if (virtualDisplay == null) {
            try {
                createCapture();
            } catch (Throwable error) {
                captureSessionEnded = true;
                setNotReady("Cannot start screen frames (" + error.getClass().getSimpleName() + ")");
                showRecoveryNotification("No screen frames • tap to restart capture permission");
                stopSelf();
                return;
            }
        }
        if (monitoringActive) RobotFreezeOrchestrator.instance().onMonitoringResumed();
    }

    private void createCapture() {
        Point size = screenSize();
        captureWidth = Math.max(1, size.x);
        captureHeight = Math.max(1, size.y);
        int density = getResources().getDisplayMetrics().densityDpi;
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "GREAT-Visual-Monitor", captureWidth, captureHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay unavailable");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        boolean resizeAfterFrame = false;
        try {
            if (reader != imageReader) return; // late callback from a replaced surface
            image = reader.acquireLatestImage();
            if (image == null) return;
            lastImageFrameNanos = System.nanoTime();

            Point current = screenSize();
            if ((current.x != captureWidth || current.y != captureHeight) && !captureResizePending) {
                captureResizePending = true;
                resizeAfterFrame = true;
                return;
            }

            if (editing || !running || !monitoringActive || !settings.enabled()
                    || !settings.monitoringEnabled()) return;
            PixelTriggerMonitorEngine.Sample sample = sampleImage(image);
            if (sample != null) {
                int center = sample.probe(0);
                capturedSampleCount++;
                lastProbeReading = "RGB " + ((center >>> 16) & 255) + "/"
                        + ((center >>> 8) & 255) + "/" + (center & 255)
                        + " • white " + Math.round(sample.whiteRatio() * 100) + "%"
                        + " • frames " + capturedSampleCount;
                boolean justRecovered = frameFailures > 0 || surfaceRecoveryAttempts > 0;
                frameFailures = 0;
                surfaceRecoveryAttempts = 0;
                lastValidFrameNanos = System.nanoTime();
                if (justRecovered) mainHandler.post(this::clearRecoveryNotification);
                synchronized (engine) {
                    engine.process(sample);
                    queueStationaryWhite(sample);
                    if (monitorColor == COLOR_NOT_READY) {
                        PixelTriggerMonitorEngine.State state = engine.state();
                        int color = state == PixelTriggerMonitorEngine.State.FIRED ? COLOR_FIRED
                                : state == PixelTriggerMonitorEngine.State.ARMED ? COLOR_ARMED
                                : COLOR_WAITING;
                        setVisual(color, "Capture recovered • " + state);
                    }
                }
            } else {
                lastProbeReading = "captured frame has no readable RGB probes";
                resetStationaryWhite();
                frameFailures++;
            }
        } catch (Throwable error) {
            resetStationaryWhite();
            frameFailures++;
            // A single corrupt frame must not turn an ON session into a misleading OFF state.
            mainHandler.post(() -> {
                if (running && monitoringActive && !shuttingDown) {
                    setVisual(COLOR_NOT_READY, "Frame unavailable • monitoring retries");
                }
            });
        } finally {
            if (image != null) image.close();
            if (resizeAfterFrame) mainHandler.post(this::resizeCaptureToCurrentScreen);
        }
    }

    /**
     * MediaProjection on Android 14+ allows only one createVirtualDisplay() call per session.
     * When a game rotates the device, keep that same VirtualDisplay and resize it onto a new
     * ImageReader surface so probe coordinates remain aligned with the visible overlay.
     */
    private void resizeCaptureToCurrentScreen() {
        ImageReader replacement = null;
        try {
            if (shuttingDown || projection == null || virtualDisplay == null) return;
            Point size = screenSize();
            int width = Math.max(1, size.x);
            int height = Math.max(1, size.y);
            if (width == captureWidth && height == captureHeight) return;

            int density = getResources().getDisplayMetrics().densityDpi;
            replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            replacement.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

            virtualDisplay.resize(width, height, density);
            virtualDisplay.setSurface(replacement.getSurface());

            ImageReader old = imageReader;
            imageReader = replacement;
            replacement = null; // now owned by this service
            captureWidth = width;
            captureHeight = height;
            if (old != null) {
                try { old.setOnImageAvailableListener(null, null); } catch (Throwable ignored) { }
                try { old.close(); } catch (Throwable ignored) { }
            }

            resetStationaryWhite();
            synchronized (engine) { engine.reset(); }
            repositionOverlay();
            RobotFreezeOrchestrator.instance().resetVisualCycle();
            if (settings.monitoringEnabled() && !editing) {
                monitoringActive = true;
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                setVisual(COLOR_WAITING, "Display changed • waiting for white");
                RobotFreezeOrchestrator.instance().onMonitoringResumed();
            }
        } catch (Throwable e) {
            // A failed resize is not an OFF instruction. Rebind the capture surface
            // while retaining the existing authorized MediaProjection session.
            setVisual(COLOR_NOT_READY, "Display resize failed • retrying capture");
            mainHandler.postDelayed(() -> recoverCaptureSurface("Display resize failed"), 700L);
        } finally {
            captureResizePending = false;
            if (replacement != null) {
                try { replacement.setOnImageAvailableListener(null, null); }
                catch (RuntimeException ignored) { }
                try { replacement.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * Android 14+ permits a single createVirtualDisplay per projection grant.
     * Replacing only the ImageReader surface is safe; creating a second display
     * would invalidate an otherwise recoverable monitoring session.
     * Never silently re-request MediaProjection or reset the saved ON intention.
     */
    private void recoverCaptureSurface(String reason) {
        surfaceRecoveryPending = false;
        if (shuttingDown || !running || !monitoringActive || editing || captureResizePending
                || !settings.enabled() || !settings.monitoringEnabled()
                || projection == null || virtualDisplay == null) return;

        surfaceRecoveryAttempts++;
        lastRecoveryAttemptNanos = System.nanoTime();
        ImageReader replacement = null;
        try {
            Point size = screenSize();
            int width = Math.max(1, size.x);
            int height = Math.max(1, size.y);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            replacement = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 3);
            replacement.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
            virtualDisplay.resize(width, height, dpi);
            virtualDisplay.setSurface(replacement.getSurface());

            ImageReader old = imageReader;
            imageReader = replacement;
            replacement = null;
            captureWidth = width;
            captureHeight = height;
            lastImageFrameNanos = System.nanoTime();
            frameFailures = 0;
            if (old != null) {
                try { old.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
                try { old.close(); } catch (RuntimeException ignored) { }
            }
            // An unchanged image may legitimately produce no frames. Do not
            // discard an ARMED white baseline merely because the screen was
            // static: otherwise a later white->dark transition would be missed.
            if (!CaptureLivenessPolicy.preserveWhiteBaseline(
                    !"No new captured frames".equals(reason))) {
                cancelFreezeTimeout();
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                resetStationaryWhite();
                synchronized (engine) { engine.reset(); }
                RobotFreezeOrchestrator.instance().onMonitoringResumed();
            }
            repositionOverlay();
            if ("No new captured frames".equals(reason) && lastValidFrameNanos > 0L) {
                // Static scenes legitimately stop generating callbacks. Keep the
                // last *observed* ARMED/WAITING color; a watchdog probe must not
                // falsely make a healthy white baseline purple forever.
                publicStatus = "Waiting for next captured change • previous "
                        + engine.state();
            } else {
                setVisual(COLOR_NOT_READY, reason + " • surface refreshed, checking frames");
            }
        } catch (Throwable e) {
            lastImageFrameNanos = System.nanoTime();
            if (surfaceRecoveryAttempts >= CaptureLivenessPolicy.FAST_RECOVERY_ATTEMPTS) {
                if ("No new captured frames".equals(reason)) {
                    // No frames can simply mean a perfectly static display.
                    // Keep the user's ON state and previous white baseline.
                    setVisual(COLOR_NOT_READY,
                            "No fresh frames • screen may be static; monitoring remains ON");
                } else {
                    setVisual(COLOR_NOT_READY,
                            "Capture failed repeatedly • tap Robot to retry capture");
                    showRecoveryNotification("Capture errors • tap to restart screen capture");
                }
            } else {
                setVisual(COLOR_NOT_READY, "Capture recovery failed • retrying");
            }
        } finally {
            if (replacement != null) {
                try { replacement.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
                try { replacement.close(); } catch (RuntimeException ignored) { }
            }
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

        // Capture the point actually marked on screen, including overlay-position
        // clamping near its edges, not an idealized unclamped preference.
        WindowManager.LayoutParams marker = overlayParams;
        float centerScreenX = marker != null
                ? marker.x + marker.width / 2f : settings.centerXFraction() * screen.x;
        float centerScreenY = marker != null
                ? marker.y + marker.height / 2f : settings.centerYFraction() * screen.y;
        int cx = crop.left + Math.round(centerScreenX * crop.width() / screen.x);
        int cy = crop.top + Math.round(centerScreenY * crop.height() / screen.y);
        // The visible marker stays 0.30 mm. If only one unobstructed pixel
        // fits inside it, sample ONE pixel instead of reading the colored ring
        // or widening the visible circle.
        float scale = Math.min((float) crop.width() / screen.x,
                (float) crop.height() / screen.y);
        int[][] points = ProbeReticleGeometry.probePoints(
                cx, cy, sensorDiameterPx() * scale);

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
        if (!running || !monitoringActive || editing || !settings.monitoringEnabled()) return;
        switch (state) {
            case WAITING_FOR_WHITE -> {
                visualFiredAtNanos = 0L;
                cancelFreezeTimeout();
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                setVisual(COLOR_WAITING, "Waiting for white");
            }
            case ARMED -> {
                visualFiredAtNanos = 0L;
                cancelFreezeTimeout();
                GreatEngine.instance().freezeCore().setHoldTrigger(false);
                setVisual(COLOR_ARMED, "ARMED • white detected • Freeze OFF");
            }
            case FIRED -> {
                visualFiredAtNanos = System.nanoTime();
                GreatEngine.instance().freezeCore().setHoldTrigger(true);
                GreatEngine.instance().freezeCore().setVisualFreezeDeadlineMillis(settings.maxFreezeMillis());
                setVisual(COLOR_FIRED, "FIRED • white lost • Freeze ON");
                scheduleFreezeTimeout();
            }
        }
    }

    private void scheduleFreezeTimeout() {
        cancelFreezeTimeout();
        final long token = timeoutSerial.get();
        freezeTimeout = () -> {
            if (token != timeoutSerial.get()) return;
            freezeTimeout = null;
            if (!running || editing || !monitoringActive || !settings.monitoringEnabled()) return;
            synchronized (engine) {
                if (engine.state() != PixelTriggerMonitorEngine.State.FIRED) return;
                engine.expireVisualFreeze();
            }
            GreatEngine.instance().freezeCore().setHoldTrigger(false);
            setVisual(COLOR_WAITING, "Freeze limit reached • waiting for white");
        };
        long elapsed = visualFiredAtNanos == 0L ? 0L
                : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - visualFiredAtNanos));
        // The capture thread is independent from Activity layout and scroll work.
        // The FreezeCore deadline remains the final authoritative packet gate.
        captureHandler.postDelayed(freezeTimeout, Math.max(0L,
                settings.maxFreezeMillis() - elapsed));
    }

    private void cancelFreezeTimeout() {
        timeoutSerial.incrementAndGet();
        if (freezeTimeout != null) captureHandler.removeCallbacks(freezeTimeout);
        freezeTimeout = null;
    }

    private void pauseMonitoring() {
        monitoringActive = false;
        resetStationaryWhite();
        cancelFreezeTimeout();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        synchronized (engine) { engine.reset(); }
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        lockOverlay();
        setVisual(COLOR_PAUSED, "Monitoring OFF");
    }

    private void resumeMonitoring() {
        if (!running) return;
        editing = false;
        monitoringActive = true;
        lockOverlay();
        resetDetector("Waiting for white");
        RobotFreezeOrchestrator.instance().onMonitoringResumed();
    }

    private void enterEditMode() {
        editing = true;
        resetStationaryWhite();
        monitoringActive = false;
        settings.setMonitoringEnabled(false);
        dragState = null;
        cancelFreezeTimeout();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        synchronized (engine) { engine.reset(); }
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        if (monitorView != null && overlayParams != null) {
            overlayParams.flags = editFlags();
            try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
        }
        setVisual(COLOR_WAITING, "Edit monitor position");
    }

    private void leaveEditAndArm() {
        editing = false;
        monitoringActive = true;
        dragState = null;
        lockOverlay();
        resetDetector("Waiting for white");
        RobotFreezeOrchestrator.instance().onMonitoringResumed();
    }

    private void resetDetector(String status) {
        resetStationaryWhite();
        cancelFreezeTimeout();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        synchronized (engine) { engine.reset(); }
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        if (settings.monitoringEnabled()) setVisual(COLOR_WAITING, status);
        else setVisual(COLOR_PAUSED, "Monitoring OFF");
    }

    private void setNotReady(String status) {
        monitoringActive = false;
        cancelFreezeTimeout();
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        setVisual(COLOR_NOT_READY, status);
    }

    private void setVisual(int color, String status) {
        monitorColor = color;
        recoveryNeeded = color == COLOR_NOT_READY;
        publicStatus = status;
        mainHandler.post(() -> {
            if (monitorView != null) monitorView.invalidate();
            if (running && !shuttingDown) {
                long now = System.nanoTime();
                if (now - lastNotificationUpdateNanos > TimeUnit.SECONDS.toNanos(2)) {
                    lastNotificationUpdateNanos = now;
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) {
                        try { manager.notify(NOTIFICATION_ID, buildNotification()); }
                        catch (RuntimeException ignored) {
                            // UI notification updates cannot be allowed to kill capture.
                        }
                    }
                }
            }
        });
    }

    private void ensureOverlay() {
        if (monitorView != null) return;
        monitorView = new MonitorView();
        monitorView.setOnTouchListener(this::onMonitorTouch);
        int touchSize = Math.max(dp(48), Math.round(sensorDiameterPx()) + dp(30));
        Point screen = screenSize();
        overlayParams = new WindowManager.LayoutParams(
                touchSize, touchSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                lockedFlags(), PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = clamp(Math.round(settings.centerXFraction() * screen.x - touchSize / 2f),
                0, Math.max(0, screen.x - touchSize));
        overlayParams.y = clamp(Math.round(settings.centerYFraction() * screen.y - touchSize / 2f),
                0, Math.max(0, screen.y - touchSize));
        windowManager.addView(monitorView, overlayParams);
    }

    private void repositionOverlay() {
        if (monitorView == null || overlayParams == null) return;
        Point screen = screenSize();
        overlayParams.x = clamp(Math.round(settings.centerXFraction() * screen.x
                - overlayParams.width / 2f), 0, Math.max(0, screen.x - overlayParams.width));
        overlayParams.y = clamp(Math.round(settings.centerYFraction() * screen.y
                - overlayParams.height / 2f), 0, Math.max(0, screen.y - overlayParams.height));
        try { windowManager.updateViewLayout(monitorView, overlayParams); }
        catch (Throwable ignored) { }
    }

    private void lockOverlay() {
        if (monitorView == null || overlayParams == null) return;
        overlayParams.flags = lockedFlags();
        try { windowManager.updateViewLayout(monitorView, overlayParams); } catch (Throwable ignored) { }
    }

    private boolean onMonitorTouch(View view, MotionEvent event) {
        if (!editing || overlayParams == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragState = new DragState(overlayParams.x, overlayParams.y, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragState == null) return false;
                moveOverlay(dragState.startX + Math.round(event.getRawX() - dragState.touchX),
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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

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
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        Intent openApp = new Intent(this, GreatMainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 4107, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("GREAT • visual monitor")
                .setContentText(monitoringActive ? "Monitoring ON • " + publicStatus
                        : editing ? "Position editing • monitoring paused" : "Monitoring paused")
                .setContentIntent(open)
                // Inspect the actual RGB value without leaving the target game.
                // It contains only the five pixel probe statistics, not a screenshot.
                .setSubText(lastProbeReading)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(publicStatus + " • " + lastProbeReading))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void showRecoveryNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent open = new Intent(this, GreatMainActivity.class)
                .setAction(GreatMainActivity.ACTION_REQUEST_MONITOR_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch = PendingIntent.getActivity(this, 4109, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(this, RECOVERY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("GREAT • monitoring needs attention")
                .setContentText(message)
                .setContentIntent(launch)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        try { manager.notify(RECOVERY_NOTIFICATION_ID, notice); }
        catch (RuntimeException ignored) {
            // Permission and OEM notification behavior must not crash monitoring.
        }
    }

    private void clearRecoveryNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(RECOVERY_NOTIFICATION_ID);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GREAT visual monitor", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Required while GREAT keeps screen capture ready for the robot monitor");
        nm.createNotificationChannel(channel);
        NotificationChannel recovery = new NotificationChannel(
                RECOVERY_CHANNEL_ID, "GREAT capture recovery",
                NotificationManager.IMPORTANCE_DEFAULT);
        recovery.setDescription("Tap to re-authorize capture after Android ends the session");
        nm.createNotificationChannel(recovery);
    }

    private void stopMonitor() {
        if (shuttingDown) return;
        shuttingDown = true;
        resetStationaryWhite();
        running = false;
        editing = false;
        monitoringActive = false;
        captureResizePending = false;
        surfaceRecoveryPending = false;
        cancelFreezeTimeout();
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (!captureSessionEnded) publicStatus = "Monitor stopped";
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
        dragState = null;
    }

    @Override public void onDestroy() {
        resetStationaryWhite();
        running = false;
        editing = false;
        monitoringActive = false;
        captureResizePending = false;
        cancelFreezeTimeout();
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        removeOverlay();
        if (!shuttingDown) {
            shuttingDown = true;
            if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Throwable ignored) { }
            if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) { }
            if (projection != null) try { projection.stop(); } catch (Throwable ignored) { }
        }
        if (captureHandler != null) captureHandler.removeCallbacks(captureWatchdog);
        if (captureThread != null) captureThread.quitSafely();
        if (!captureSessionEnded) publicStatus = "Monitor stopped";
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
            stroke.setStrokeWidth(ProbeReticleGeometry.strokePixels(sensorDiameterPx()));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            stroke.setColor(monitorColor);
            float diameter = sensorDiameterPx();
            float radius = ProbeReticleGeometry.radiusPixels(
                    getResources().getDisplayMetrics().density, diameter);
            // The outer edge is EXACTLY the original physical diameter (0.30mm);
            // color probes stay inside the unpainted center, not outside it.
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
