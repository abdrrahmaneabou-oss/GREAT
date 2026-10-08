package com.great.app.ui;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.Toast;

import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.GreatEngine;
import com.great.app.monitor.*;

/** One full-display capture session, one passive ring, one independent Freeze source. */
public final class FreezeMonitorService extends Service {
    public static final String ACTION_START = "com.great.app.monitor.START";
    public static final String ACTION_STOP = "com.great.app.monitor.STOP";
    public static final String ACTION_EDIT = "com.great.app.monitor.EDIT";
    public static final String ACTION_LOCK = "com.great.app.monitor.LOCK";
    public static final String ACTION_REFRESH = "com.great.app.monitor.REFRESH";
    private static volatile boolean sessionActive, monitoring;
    private static volatile String status = "Monitor OFF";
    public static boolean isMonitoring() { return monitoring; }
    public static String status() { return status; }

    public static void request(Context context, String action) {
        if (ACTION_STOP.equals(action)) {
            context.stopService(new Intent(context, FreezeMonitorService.class));
        } else if ((ACTION_START.equals(action) || ACTION_LOCK.equals(action)) && !sessionActive) {
            context.startActivity(new Intent(context, MonitorPermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            context.startService(new Intent(context, FreezeMonitorService.class).setAction(action));
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    // Serializes frame decisions with stop/edit/geometry changes so stale frames cannot re-enable Freeze.
    private final Object gate = new Object();
    private final PixelTriggerMonitorEngine detector = new PixelTriggerMonitorEngine();
    private final PixelProbeSampler sampler = new PixelProbeSampler();
    private HandlerThread captureThread;
    private Handler capture;
    private WindowManager windows;
    private DisplayManager displays;
    private MonitorSettingsStore settings;
    private Ring ring;
    private WindowManager.LayoutParams params;
    private int width, height, rotation, diameter, generation;
    private boolean editing, closed, ready, contentVisible = true;
    private Geometry geometry;
    // These capture resources are accessed only on the capture thread.
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int readerGeneration = -1;
    private PixelTriggerMonitorEngine.State renderedState;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { }
        public void onDisplayRemoved(int id) { }
        public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) updateGeometry(false);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopSelf(); }
    };
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { fail("Screen capture stopped"); }
        @Override public void onCapturedContentResize(int w, int h) {
            main.post(() -> {
                if (closed) return;
                updateGeometry(false);
                // A cropped app session has no screen-space origin: do not sample guessed coordinates.
                if (w != width || h != height) fail("Select the entire screen for Visual Monitor");
            });
        }
        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            synchronized (gate) {
                contentVisible = visible;
                resetLocked();
            }
            main.post(FreezeMonitorService.this::render);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        displays = getSystemService(DisplayManager.class);
        settings = new MonitorSettingsStore(this);
        captureThread = new HandlerThread("GREAT-VisualCapture", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        captureThread.start();
        capture = new Handler(captureThread.getLooper());
        displays.registerDisplayListener(displayListener, main);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || ACTION_STOP.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) && ring == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (ACTION_START.equals(action) && intent.hasExtra("result_data")) {
                if (sessionActive) return START_NOT_STICKY;
                foreground(); // Must precede getMediaProjection on Android 14+.
                synchronized (gate) { editing = false; resetLocked(); }
                updateGeometry(true);
                int result = intent.getIntExtra("result_code", Activity.RESULT_CANCELED);
                Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("result_data", Intent.class)
                        : intent.getParcelableExtra("result_data");
                ring.post(() -> { if (!closed) capture.post(() -> startCapture(result, data)); });
            } else {
                synchronized (gate) {
                    if (ACTION_EDIT.equals(action)) editing = true;
                    if (ACTION_LOCK.equals(action) || ACTION_START.equals(action)) editing = false;
                    resetLocked();
                }
                updateGeometry(true);
            }
        } catch (RuntimeException e) { fail("Cannot start Visual Monitor: " + e.getClass().getSimpleName()); }
        return START_NOT_STICKY;
    }

    private void foreground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("visual_monitor", "Visual Monitor", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FreezeMonitorService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, GreatMainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "visual_monitor")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("GREAT Visual Monitor")
                .setContentText("Monitoring the screen • Tap Stop to end capture")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void startCapture(int result, Intent data) {
        synchronized (gate) { if (closed) return; }
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(result, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(projectionCallback, capture);
            Geometry g;
            synchronized (gate) { g = geometry; }
            reader = newReader(g);
            display = projection.createVirtualDisplay("GREAT-VisualMonitor", half(g.w), half(g.h), half(g.density),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capture);
            synchronized (gate) {
                if (!closed) { sessionActive = true; ready = readerGeneration == generation; }
            }
            main.post(this::render);
        } catch (RuntimeException e) { fail("Screen capture failed: " + e.getClass().getSimpleName()); }
    }

    private ImageReader newReader(Geometry g) {
        ImageReader next = ImageReader.newInstance(half(g.w), half(g.h), PixelFormat.RGBA_8888, 2);
        readerGeneration = g.version;
        next.setOnImageAvailableListener(source -> processFrame(source, g), capture);
        return next;
    }

    private void resizeCapture(Geometry g) {
        synchronized (gate) { if (closed || g.version != generation) return; }
        if (display == null) return;
        try {
            ImageReader old = reader;
            reader = newReader(g);
            display.resize(half(g.w), half(g.h), half(g.density));
            display.setSurface(reader.getSurface());
            if (old != null) { old.setOnImageAvailableListener(null, null); old.close(); }
            synchronized (gate) { ready = !closed && g.version == generation; }
            main.post(this::render);
        } catch (RuntimeException e) { fail("Cannot resize screen capture"); }
    }

    private void processFrame(ImageReader source, Geometry g) {
        if (source != reader) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            synchronized (gate) {
                if (closed || editing || !ready || !contentVisible || g.version != generation) return;
                Rect crop = image.getCropRect();
                if (crop.isEmpty() || image.getPlanes().length == 0) return;
                int x = MonitorGeometry.center(g.cx, g.w, crop.left, crop.right);
                int y = MonitorGeometry.center(g.cy, g.h, crop.top, crop.bottom);
                Image.Plane plane = image.getPlanes()[0];
                PixelSample sample = sampler.sample(plane.getBuffer(), plane.getPixelStride(), plane.getRowStride(),
                        crop.left, crop.top, crop.right, crop.bottom, x, y,
                        MonitorGeometry.radius(g.diameter, g.w, crop.width()), MonitorGeometry.radius(g.diameter, g.h, crop.height()));
                PixelTriggerMonitorEngine.State before = detector.state();
                detector.process(sample);
                // Direct capture-thread update; UI drawing is never on the critical path.
                GreatEngine.instance().freezeCore().setHoldTrigger(detector.triggered());
                if (before != detector.state()) main.post(this::render);
            }
        } catch (RuntimeException e) { fail("Screen capture interrupted: " + e.getClass().getSimpleName()); }
    }

    private void updateGeometry(boolean force) {
        if (closed) return;
        Rect bounds;
        if (Build.VERSION.SDK_INT >= 30) bounds = windows.getMaximumWindowMetrics().getBounds();
        else { Point size = new Point(); windows.getDefaultDisplay().getRealSize(size); bounds = new Rect(0, 0, size.x, size.y); }
        int nextRotation = windows.getDefaultDisplay().getRotation();
        if (!force && width == bounds.width() && height == bounds.height() && rotation == nextRotation) return;
        width = bounds.width(); height = bounds.height(); rotation = nextRotation;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        diameter = MonitorGeometry.mmToPx(.3f, metrics.xdpi, metrics.ydpi, metrics.densityDpi);
        int size = Math.max(dp(settings.ringDp() + 8), diameter + dp(16));
        if (ring == null) {
            ring = new Ring();
            params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    overlayFlags(), PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
            ring.setOnTouchListener(this::drag);
        }
        params.width = params.height = size;
        params.x = Math.max(0, Math.min(width - size, Math.round(settings.x(width, height) * width) - size / 2));
        params.y = Math.max(0, Math.min(height - size, Math.round(settings.y(width, height) * height) - size / 2));
        params.flags = overlayFlags();
        params.alpha = editing ? 1f : passiveAlpha();
        synchronized (gate) { ready = false; generation++; resetLocked(); }
        if (ring.getParent() == null) windows.addView(ring, params);
        else windows.updateViewLayout(ring, params);
        final int expectedGeneration = generation;
        // Read actual screen coordinates after layout, including cutouts/insets, before capture resumes.
        ring.post(() -> {
            if (closed || ring == null || expectedGeneration != generation) return;
            int[] xy = new int[2]; ring.getLocationOnScreen(xy);
            Geometry g;
            synchronized (gate) {
                ready = false; generation++; resetLocked();
                g = new Geometry(width, height, xy[0] + ring.getWidth() / 2, xy[1] + ring.getHeight() / 2,
                        diameter, metrics.densityDpi, generation);
                geometry = g;
            }
            capture.post(() -> resizeCapture(g));
        });
        // Initial geometry for capture setup; ready remains false until actual layout is published.
        synchronized (gate) {
            geometry = new Geometry(width, height, params.x + size / 2, params.y + size / 2,
                    diameter, metrics.densityDpi, generation);
        }
        render();
    }

    private int overlayFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | (editing ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }
    private float passiveAlpha() {
        return Build.VERSION.SDK_INT >= 31 ? Math.min(.6f, getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch()) : .6f;
    }
    private float grabX, grabY;
    private boolean drag(View view, MotionEvent event) {
        if (!editing) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> { grabX = event.getRawX() - params.x; grabY = event.getRawY() - params.y; return true; }
            case MotionEvent.ACTION_MOVE -> {
                params.x = Math.max(0, Math.min(width - params.width, Math.round(event.getRawX() - grabX)));
                params.y = Math.max(0, Math.min(height - params.height, Math.round(event.getRawY() - grabY)));
                windows.updateViewLayout(ring, params); return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                settings.save((params.x + params.width / 2f) / width, (params.y + params.height / 2f) / height, width, height);
                view.performClick(); return true;
            }
            default -> { return false; }
        }
    }

    private void resetLocked() {
        detector.reset();
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
    }
    private void render() {
        synchronized (gate) {
            if (closed) return;
            monitoring = sessionActive && !editing;
            renderedState = detector.state();
            status = editing ? "EDIT • Drag the ring, then Save / Start Monitor" : !ready ? "Starting screen capture…"
                    : !contentVisible ? "Capture not visible • Monitor paused" : switch (renderedState) {
                        case WAITING_FOR_WHITE -> "WAITING • 3 white frames to arm";
                        case ARMED -> "GREEN / ARMED • Monitor Freeze OFF";
                        case FIRED -> "RED / TRIGGERED • Monitor Freeze ON";
                    };
        }
        if (ring != null) ring.invalidate();
    }
    private void fail(String message) {
        synchronized (gate) { if (closed) return; ready = false; resetLocked(); }
        main.post(() -> { if (!closed) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); stopSelf(); } });
    }
    @Override public void onDestroy() {
        synchronized (gate) { closed = true; ready = false; resetLocked(); sessionActive = monitoring = false; status = "Monitor OFF"; }
        displays.unregisterDisplayListener(displayListener);
        unregisterReceiver(screenOff);
        main.removeCallbacksAndMessages(null);
        if (ring != null) { windows.removeView(ring); ring = null; }
        capture.post(() -> {
            if (reader != null) reader.setOnImageAvailableListener(null, null);
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (projection != null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection = null; }
            captureThread.quitSafely();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private static int half(int n) { return Math.max(1, Math.round(n * .5f)); }
    private record Geometry(int w, int h, int cx, int cy, int diameter, int density, int version) { }

    private final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ring() { super(FreezeMonitorService.this); setContentDescription("Visual Monitor position"); }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2, cy = getHeight() / 2;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            paint.setColor(editing ? 0xffb89aff : renderedState == PixelTriggerMonitorEngine.State.ARMED ? 0xff3cdc78
                    : renderedState == PixelTriggerMonitorEngine.State.FIRED ? 0xffff505f : 0xffffb84d);
            // Entire centre is transparent. Even the inner stroke edge stays well beyond the probes
            // and the half-resolution bilinear filter footprint. FLAG_SECURE would black out the ROI.
            float radius = Math.max(dp(settings.ringDp()) / 2f, diameter / 2f + dp(5));
            canvas.drawCircle(cx, cy, radius, paint);
            if (editing) {
                paint.setStrokeWidth(1); paint.setColor(0xffeeeeee);
                canvas.drawCircle(cx, cy, diameter / 2f, paint);
            }
        }
    }
}
