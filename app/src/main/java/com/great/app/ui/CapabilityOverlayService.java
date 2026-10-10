package com.great.app.ui;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.Capability;
import com.great.app.core.GreatEngine;
import com.great.app.transport.GlobalUdpThrottleTest;

/** Three independent floating controls: robot, manual Freeze, and a global UDP throttle test. */
public final class CapabilityOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_CAPABILITIES";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_CAPABILITIES";

    private static final String PREFS = "great_overlay";
    private static final String KEY_ROBOT_X = "robot_x";
    private static final String KEY_ROBOT_Y = "robot_y";
    private static final String KEY_FREEZE_X = "freeze_x";
    private static final String KEY_FREEZE_Y = "freeze_y";
    private static final String KEY_THROTTLE_X = "throttle_x";
    private static final String KEY_THROTTLE_Y = "throttle_y";
    private static final int ACTIVE = 0xffef5350;
    private static final int INACTIVE = 0xff7b808c;
    private static final int EDITING = 0xffffc107;
    private static final int SIZE_DP = 58;
    private static final long LONG_PRESS_MS = 300L;
    private static final int CONTROL_ROBOT = 0;
    private static final int CONTROL_FREEZE = 1;
    private static final int CONTROL_THROTTLE = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable renderTick = new Runnable() {
        @Override public void run() {
            renderStates();
            if (robotView != null || freezeView != null || throttleView != null) {
                handler.postDelayed(this, 150);
            }
        }
    };

    private WindowManager windowManager;
    private SharedPreferences prefs;
    private MonitorSettingsStore monitorSettings;
    private TextView robotView;
    private TextView freezeView;
    private TextView throttleView;
    private WindowManager.LayoutParams robotParams;
    private WindowManager.LayoutParams freezeParams;
    private WindowManager.LayoutParams throttleParams;

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        monitorSettings = new MonitorSettingsStore(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action) && Settings.canDrawOverlays(this)) showOverlay();
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        if (robotView != null || freezeView != null || throttleView != null) return;
        Point screen = screenSize();
        int size = dp(SIZE_DP);
        int x = Math.max(0, screen.x - size - dp(18));

        throttleView = circle("🛜");
        throttleParams = params(
                prefs.getInt(KEY_THROTTLE_X, x),
                prefs.getInt(KEY_THROTTLE_Y, dp(300)), size);
        throttleView.setOnTouchListener(new CircleTouch(CONTROL_THROTTLE));
        windowManager.addView(throttleView, throttleParams);

        freezeView = circle("❄️");
        freezeParams = params(
                prefs.getInt(KEY_FREEZE_X, x),
                prefs.getInt(KEY_FREEZE_Y, dp(230)), size);
        freezeView.setOnTouchListener(new CircleTouch(CONTROL_FREEZE));
        windowManager.addView(freezeView, freezeParams);

        robotView = circle("🤖");
        robotParams = params(
                prefs.getInt(KEY_ROBOT_X, x),
                prefs.getInt(KEY_ROBOT_Y, dp(160)), size);
        robotView.setOnTouchListener(new CircleTouch(CONTROL_ROBOT));
        windowManager.addView(robotView, robotParams);

        clampAndUpdate(robotView, robotParams, KEY_ROBOT_X, KEY_ROBOT_Y, false);
        clampAndUpdate(freezeView, freezeParams, KEY_FREEZE_X, KEY_FREEZE_Y, false);
        clampAndUpdate(throttleView, throttleParams, KEY_THROTTLE_X, KEY_THROTTLE_Y, false);
        renderStates();
        handler.post(renderTick);
    }

    private TextView circle(String emoji) {
        TextView view = new TextView(this);
        view.setText(emoji);
        view.setTextSize(27);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setIncludeFontPadding(false);
        view.setClickable(true);
        view.setFocusable(false);
        return view;
    }

    private WindowManager.LayoutParams params(int x, int y, int size) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    private void renderStates() {
        if (robotView != null) {
            int color;
            if (FreezeMonitorService.isEditing()) color = EDITING;
            else color = FreezeMonitorService.isMonitoringActive() ? ACTIVE : INACTIVE;
            robotView.setBackground(circleBg(color));
        }
        if (freezeView != null) {
            boolean active = GreatEngine.instance().capabilities().snapshot().enabled(Capability.FREEZE);
            freezeView.setBackground(circleBg(active ? ACTIVE : INACTIVE));
        }
        if (throttleView != null) {
            throttleView.setBackground(circleBg(
                    GlobalUdpThrottleTest.instance().enabled() ? ACTIVE : INACTIVE));
        }
    }

    private void onRobotClick() {
        if (FreezeMonitorService.isEditing()) {
            startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_LOCK));
            monitorSettings.setMonitoringEnabled(true);
            Toast.makeText(this, "Monitor position saved", Toast.LENGTH_SHORT).show();
            renderStates();
            return;
        }
        if (!FreezeMonitorService.isRunning()) {
            Intent activity = new Intent(this, GreatMainActivity.class)
                    .setAction(GreatMainActivity.ACTION_REQUEST_MONITOR_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(activity);
            return;
        }
        boolean next = !FreezeMonitorService.isMonitoringActive();
        monitorSettings.setMonitoringEnabled(next);
        startService(new Intent(this, FreezeMonitorService.class)
                .setAction(next ? FreezeMonitorService.ACTION_MONITORING_ON
                        : FreezeMonitorService.ACTION_MONITORING_OFF));
        renderStates();
    }

    private void onRobotLongPress() {
        if (FreezeMonitorService.isRunning()) {
            startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_EDIT));
            Toast.makeText(this, "Move the tiny monitor circle, then tap 🤖 to save", Toast.LENGTH_LONG).show();
        } else {
            Intent activity = new Intent(this, GreatMainActivity.class)
                    .setAction(GreatMainActivity.ACTION_REQUEST_MONITOR_EDIT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(activity);
        }
        handler.postDelayed(this::renderStates, 30);
    }

    private void onFreezeClick() {
        GreatEngine engine = GreatEngine.instance();
        boolean enabled = engine.capabilities().snapshot().enabled(Capability.FREEZE);
        if (!enabled && engine.targetCount() == 0) {
            Toast.makeText(this, "Add a target application and start the VPN first", Toast.LENGTH_LONG).show();
            return;
        }
        engine.capabilities().toggle(Capability.FREEZE);
        renderStates();
    }

    private void onThrottleClick() {
        GlobalUdpThrottleTest throttle = GlobalUdpThrottleTest.instance();
        boolean next = !throttle.enabled();
        throttle.setEnabled(next);
        Toast.makeText(this,
                next ? "Global IPv4 UDP throttle: 100 ms ON" : "Global IPv4 UDP throttle OFF",
                Toast.LENGTH_SHORT).show();
        renderStates();
    }

    private GradientDrawable circleBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), 0x99ffffff);
        return d;
    }

    private final class CircleTouch implements View.OnTouchListener {
        private final int control;
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging;
        private boolean longPressed;
        private Runnable longPress;

        CircleTouch(int control) { this.control = control; }

        @Override public boolean onTouch(View view, MotionEvent event) {
            WindowManager.LayoutParams p = paramsFor(control);
            if (p == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = p.x;
                    startY = p.y;
                    dragging = false;
                    longPressed = false;
                    if (control == CONTROL_ROBOT) {
                        longPress = () -> {
                            if (!dragging) {
                                longPressed = true;
                                onRobotLongPress();
                            }
                        };
                        handler.postDelayed(longPress, LONG_PRESS_MS);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > dp(8)) {
                        dragging = true;
                        cancelLongPress();
                    }
                    if (dragging) move(view, p, startX + Math.round(dx), startY + Math.round(dy));
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    cancelLongPress();
                    if (dragging) {
                        saveControlPosition(control, p);
                    } else if (!longPressed) {
                        if (control == CONTROL_ROBOT) onRobotClick();
                        else if (control == CONTROL_FREEZE) onFreezeClick();
                        else onThrottleClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress();
                    return true;
                }
                default -> { return true; }
            }
        }

        private void cancelLongPress() {
            if (longPress != null) handler.removeCallbacks(longPress);
            longPress = null;
        }
    }

    private WindowManager.LayoutParams paramsFor(int control) {
        if (control == CONTROL_ROBOT) return robotParams;
        if (control == CONTROL_FREEZE) return freezeParams;
        return throttleParams;
    }

    private void saveControlPosition(int control, WindowManager.LayoutParams p) {
        if (control == CONTROL_ROBOT) savePosition(p, KEY_ROBOT_X, KEY_ROBOT_Y);
        else if (control == CONTROL_FREEZE) savePosition(p, KEY_FREEZE_X, KEY_FREEZE_Y);
        else savePosition(p, KEY_THROTTLE_X, KEY_THROTTLE_Y);
    }

    private void move(View view, WindowManager.LayoutParams p, int x, int y) {
        Point screen = screenSize();
        p.x = clamp(x, 0, Math.max(0, screen.x - p.width));
        p.y = clamp(y, 0, Math.max(0, screen.y - p.height));
        try { windowManager.updateViewLayout(view, p); } catch (Throwable ignored) { }
    }

    private void savePosition(WindowManager.LayoutParams p, String keyX, String keyY) {
        prefs.edit().putInt(keyX, p.x).putInt(keyY, p.y).apply();
    }

    private void clampAndUpdate(View view, WindowManager.LayoutParams p,
                                String keyX, String keyY, boolean save) {
        if (view == null || p == null) return;
        move(view, p, p.x, p.y);
        if (save) savePosition(p, keyX, keyY);
    }

    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point point = new Point();
        windowManager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private void removeOverlay() {
        handler.removeCallbacks(renderTick);
        if (robotView != null) try { windowManager.removeView(robotView); } catch (Throwable ignored) { }
        if (freezeView != null) try { windowManager.removeView(freezeView); } catch (Throwable ignored) { }
        if (throttleView != null) try { windowManager.removeView(throttleView); } catch (Throwable ignored) { }
        robotView = null;
        freezeView = null;
        throttleView = null;
        robotParams = null;
        freezeParams = null;
        throttleParams = null;
    }

    @Override public void onDestroy() { removeOverlay(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
