package com.great.app.ui;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.great.app.config.TriggerSettingsStore;
import com.great.app.shizuku.ShizukuTouchEngine;

/**
 * Visual Freeze trigger circle.
 *
 * Locked/running mode is deliberately FLAG_NOT_TOUCHABLE: the physical touch goes directly to
 * the app/game below. Edit mode is the only mode allowed to receive touches so the user can move
 * the circle. Trigger detection is intentionally not performed by this overlay.
 */
public final class FreezeTriggerOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_FREEZE_TRIGGER";
    public static final String ACTION_EDIT = "com.great.app.action.EDIT_FREEZE_TRIGGER";
    public static final String ACTION_LOCK = "com.great.app.action.LOCK_FREEZE_TRIGGER";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_FREEZE_TRIGGER";
    public static final String ACTION_REFRESH = "com.great.app.action.REFRESH_FREEZE_TRIGGER";

    private final ShizukuTouchEngine touchEngine = ShizukuTouchEngine.instance();

    private WindowManager windowManager;
    private TriggerSettingsStore settings;
    private TriggerView circle;
    private WindowManager.LayoutParams params;
    private boolean editMode;
    private DragState dragState;

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        settings = new TriggerSettingsStore(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();

        if (ACTION_HIDE.equals(action) || !settings.enabled()) {
            removeCircle();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY;

        if (ACTION_EDIT.equals(action)) {
            ensureCircle();
            setEditMode(true);
        } else if (ACTION_SHOW.equals(action) || ACTION_LOCK.equals(action)) {
            ensureCircle();
            refreshGeometry();
            setEditMode(false);
            bindTouchDiagnostics();
        } else if (ACTION_REFRESH.equals(action)) {
            ensureCircle();
            refreshGeometry();
            if (!editMode) {
                applyLockedFlags();
                bindTouchDiagnostics();
            }
        }
        return START_NOT_STICKY;
    }

    private void bindTouchDiagnostics() {
        touchEngine.ensureBound(this, (ready, status) -> { });
    }

    private void ensureCircle() {
        if (circle != null) return;
        circle = new TriggerView(this);
        circle.setOnTouchListener(this::onCircleTouch);

        int size = diameterPx();
        Point screen = screenSize();
        params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                lockedFlags(),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(Math.round(settings.centerXFraction() * screen.x - size / 2f),
                0, Math.max(0, screen.x - size));
        params.y = clamp(Math.round(settings.centerYFraction() * screen.y - size / 2f),
                0, Math.max(0, screen.y - size));
        windowManager.addView(circle, params);
    }

    private boolean onCircleTouch(View view, MotionEvent event) {
        if (!editMode || params == null) return false;
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
            default -> {
                return true;
            }
        }
    }

    private void setEditMode(boolean edit) {
        editMode = edit;
        dragState = null;
        if (circle == null || params == null) return;
        params.flags = edit ? editFlags() : lockedFlags();
        try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
        circle.invalidate();
    }

    private void applyLockedFlags() {
        if (circle == null || params == null) return;
        params.flags = lockedFlags();
        try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
        circle.invalidate();
    }

    private void refreshGeometry() {
        if (circle == null || params == null) return;
        int size = diameterPx();
        Point screen = screenSize();
        params.width = size;
        params.height = size;
        params.x = clamp(Math.round(settings.centerXFraction() * screen.x - size / 2f),
                0, Math.max(0, screen.x - size));
        params.y = clamp(Math.round(settings.centerYFraction() * screen.y - size / 2f),
                0, Math.max(0, screen.y - size));
        try { windowManager.updateViewLayout(circle, params); } catch (Throwable ignored) { }
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

    private void removeCircle() {
        if (circle != null) {
            try { windowManager.removeView(circle); } catch (Throwable ignored) { }
        }
        circle = null;
        params = null;
        editMode = false;
        dragState = null;
        touchEngine.unbind();
    }

    @Override public void onDestroy() {
        removeCircle();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final class TriggerView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        TriggerView(Service context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int color = editMode ? 0xffffc857 : 0xff71798c;
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
