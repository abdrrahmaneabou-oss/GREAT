package com.great.app.ui;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.great.app.config.TriggerSettingsStore;
import com.great.app.core.GreatEngine;
import com.great.app.shizuku.ShizukuTouchEngine;

/**
 * Visible Freeze trigger. In locked mode a gesture that begins inside the circle pins Freeze
 * until that same gesture ends, even if the finger moves outside the circle.
 */
public final class FreezeTriggerOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_FREEZE_TRIGGER";
    public static final String ACTION_EDIT = "com.great.app.action.EDIT_FREEZE_TRIGGER";
    public static final String ACTION_LOCK = "com.great.app.action.LOCK_FREEZE_TRIGGER";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_FREEZE_TRIGGER";
    public static final String ACTION_REFRESH = "com.great.app.action.REFRESH_FREEZE_TRIGGER";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShizukuTouchEngine engine = ShizukuTouchEngine.instance();

    private WindowManager windowManager;
    private TriggerSettingsStore settings;
    private TriggerView circle;
    private WindowManager.LayoutParams params;
    private boolean editMode;
    private boolean armed;
    private boolean gestureTriggersFreeze;
    private DragState dragState;

    private final ShizukuTouchEngine.Listener engineListener = (ready, status) ->
            main.post(() -> applyEngineState(ready));

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        settings = new TriggerSettingsStore(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            removeCircle();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY;

        if (ACTION_EDIT.equals(action)) {
            ensureCircle();
            setEditMode(true);
        } else if (ACTION_LOCK.equals(action) || ACTION_SHOW.equals(action)) {
            ensureCircle();
            setEditMode(false);
            engine.ensureBound(this, engineListener);
            applyEngineState(engine.ready());
        } else if (ACTION_REFRESH.equals(action)) {
            ensureCircle();
            refreshGeometry();
            if (!editMode) {
                engine.ensureBound(this, engineListener);
                applyEngineState(engine.ready());
            }
        }
        return START_NOT_STICKY;
    }

    private void ensureCircle() {
        if (circle != null) return;
        circle = new TriggerView();
        circle.setOnTouchListener(this::onCircleTouch);

        int size = diameterPx();
        Point screen = screenSize();
        params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(Math.round(settings.centerXFraction() * screen.x - size / 2f), 0,
                Math.max(0, screen.x - size));
        params.y = clamp(Math.round(settings.centerYFraction() * screen.y - size / 2f), 0,
                Math.max(0, screen.y - size));
        windowManager.addView(circle, params);
    }

    private boolean onCircleTouch(View view, MotionEvent event) {
        if (editMode) return handleEditTouch(event);
        if (!armed || !engine.ready()) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - view.getWidth() / 2f;
            float dy = event.getY() - view.getHeight() / 2f;
            float radius = Math.min(view.getWidth(), view.getHeight()) / 2f;
            gestureTriggersFreeze = dx * dx + dy * dy <= radius * radius;
            if (gestureTriggersFreeze) GreatEngine.instance().freezeCore().setHoldTrigger(true);

            // Hide only from hit testing while the injected DOWN is synchronously routed.
            // WAIT_FOR_RESULT in the Shizuku service makes it safe to restore immediately after.
            boolean forwarded = forwardDownPastOverlay(event);
            if (!forwarded) failClosed();
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!engine.forward(event, displayId())) failClosed();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean forwarded = engine.forward(event, displayId());
            if (gestureTriggersFreeze) GreatEngine.instance().freezeCore().setHoldTrigger(false);
            gestureTriggersFreeze = false;
            if (!forwarded) failClosed();
            return true;
        }
        return true;
    }

    private boolean forwardDownPastOverlay(MotionEvent event) {
        if (params == null || circle == null) return false;
        int originalFlags = params.flags;
        try {
            params.flags = originalFlags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(circle, params);
            return engine.forward(event, displayId());
        } catch (Throwable e) {
            return false;
        } finally {
            if (circle != null && params != null && !editMode && engine.ready()) {
                try {
                    params.flags = originalFlags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(circle, params);
                } catch (Throwable ignored) { }
            }
        }
    }

    private boolean handleEditTouch(MotionEvent event) {
        if (params == null || circle == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragState = new DragState(params.x, params.y, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragState == null) return false;
                int x = dragState.startX + Math.round(event.getRawX() - dragState.touchX);
                int y = dragState.startY + Math.round(event.getRawY() - dragState.touchY);
                moveTo(x, y);
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

    private void setEditMode(boolean edit) {
        editMode = edit;
        armed = false;
        gestureTriggersFreeze = false;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (circle != null) circle.invalidate();
        if (params == null || circle == null) return;

        if (edit) {
            params.flags = baseFlags() & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(circle, params);
        } else {
            params.flags = baseFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(circle, params);
        }
    }

    private void applyEngineState(boolean ready) {
        if (circle == null || params == null || editMode) return;
        armed = ready;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        params.flags = baseFlags() | (ready ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
        circle.invalidate();
    }

    private void failClosed() {
        armed = false;
        gestureTriggersFreeze = false;
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        if (params != null && circle != null) {
            params.flags = baseFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
            circle.invalidate();
        }
    }

    private void refreshGeometry() {
        if (circle == null || params == null) return;
        int size = diameterPx();
        Point screen = screenSize();
        params.width = size;
        params.height = size;
        params.x = clamp(Math.round(settings.centerXFraction() * screen.x - size / 2f), 0,
                Math.max(0, screen.x - size));
        params.y = clamp(Math.round(settings.centerYFraction() * screen.y - size / 2f), 0,
                Math.max(0, screen.y - size));
        windowManager.updateViewLayout(circle, params);
        circle.invalidate();
    }

    private void moveTo(int x, int y) {
        if (circle == null || params == null) return;
        Point screen = screenSize();
        int size = params.width;
        params.x = clamp(x, 0, Math.max(0, screen.x - size));
        params.y = clamp(y, 0, Math.max(0, screen.y - size));
        try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
    }

    private void saveCenter() {
        if (params == null) return;
        Point screen = screenSize();
        if (screen.x <= 0 || screen.y <= 0) return;
        float cx = (params.x + params.width / 2f) / screen.x;
        float cy = (params.y + params.height / 2f) / screen.y;
        settings.setCenterFractions(cx, cy);
    }

    private int diameterPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpi = (dm.xdpi + dm.ydpi) / 2f;
        if (!Float.isFinite(dpi) || dpi < 100f || dpi > 1000f) dpi = dm.densityDpi;
        return Math.max(1, Math.round(settings.diameterCm() * dpi / 2.54f));
    }

    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point point = new Point();
        windowManager.getDefaultDisplay().getSize(point);
        return point;
    }

    private int displayId() {
        Display display = circle == null ? null : circle.getDisplay();
        return display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }

    private static int baseFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    private void removeCircle() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        engine.unbind();
        if (circle != null) {
            try { windowManager.removeView(circle); } catch (Throwable ignored) { }
        }
        circle = null;
        params = null;
        armed = false;
        editMode = false;
        gestureTriggersFreeze = false;
        dragState = null;
    }

    @Override public void onDestroy() {
        removeCircle();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final class TriggerView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        TriggerView() {
            super(FreezeTriggerOverlayService.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int color;
            if (editMode) color = 0xffffc857;
            else if (armed) color = 0xffb89aff;
            else color = 0xff71798c;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor((color & 0x00ffffff) | 0x33000000);
            stroke.setColor(color);
            float radius = Math.min(getWidth(), getHeight()) / 2f - stroke.getStrokeWidth();
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.max(1f, radius), fill);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.max(1f, radius), stroke);
            if (editMode) {
                float r = Math.max(4f, radius * .28f);
                canvas.drawLine(getWidth() / 2f - r, getHeight() / 2f,
                        getWidth() / 2f + r, getHeight() / 2f, stroke);
                canvas.drawLine(getWidth() / 2f, getHeight() / 2f - r,
                        getWidth() / 2f, getHeight() / 2f + r, stroke);
            }
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
