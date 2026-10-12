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
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.FreezeEntrySettingsStore;
import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.Capability;
import com.great.app.core.FreezeCore;
import com.great.app.core.GreatEngine;

import java.util.Map;
import java.util.TreeMap;

/** Two floating capability controls plus a compact latest-cycle packet summary. */
public final class CapabilityOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_CAPABILITIES";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_CAPABILITIES";

    private static final String PREFS = "great_overlay";
    private static final String KEY_ROBOT_X = "robot_x";
    private static final String KEY_ROBOT_Y = "robot_y";
    private static final String KEY_FREEZE_X = "freeze_x";
    private static final String KEY_FREEZE_Y = "freeze_y";
    private static final String KEY_SUMMARY_X = "packet_summary_x";
    private static final String KEY_SUMMARY_Y = "packet_summary_y";
    private static final String KEY_SUMMARY_COLLAPSED = "packet_summary_collapsed";
    private static final int ACTIVE = 0xffef5350;
    private static final int INACTIVE = 0xff7b808c;
    private static final int EDITING = 0xffffc107;
    private static final int SIZE_DP = 58;
    private static final int SUMMARY_CONTROL_DP = 28;
    private static final long LONG_PRESS_MS = 300L;
    private static final float SUMMARY_BASE_TEXT_SP = 13f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable renderTick = new Runnable() {
        @Override public void run() {
            renderStates();
            updatePacketSummary();
            updateSummaryExpandPosition();
            if (robotView != null || freezeView != null || packetSummaryView != null) {
                handler.postDelayed(this, 150);
            }
        }
    };

    private WindowManager windowManager;
    private SharedPreferences prefs;
    private MonitorSettingsStore monitorSettings;
    private FreezeEntrySettingsStore freezeEntrySettings;
    private TextView robotView;
    private TextView freezeView;
    private FrameLayout packetSummaryView;
    private TextView packetSummaryText;
    private TextView summaryCollapseView;
    private TextView summaryResizeView;
    private TextView summaryExpandView;
    private WindowManager.LayoutParams robotParams;
    private WindowManager.LayoutParams freezeParams;
    private WindowManager.LayoutParams packetSummaryParams;
    private WindowManager.LayoutParams summaryExpandParams;
    private float summaryScale = FreezeEntrySettingsStore.DEFAULT_SUMMARY_SCALE;
    private boolean summaryCollapsed;
    private long renderedSummaryCycle = Long.MIN_VALUE;

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        monitorSettings = new MonitorSettingsStore(this);
        freezeEntrySettings = new FreezeEntrySettingsStore(this);
        summaryScale = freezeEntrySettings.summaryScale();
        summaryCollapsed = prefs.getBoolean(KEY_SUMMARY_COLLAPSED, false);
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
        if (robotView != null || freezeView != null || packetSummaryView != null) return;
        Point screen = screenSize();
        int size = dp(SIZE_DP);
        int x = Math.max(0, screen.x - size - dp(18));

        freezeView = circle("❄️");
        freezeParams = circleParams(
                prefs.getInt(KEY_FREEZE_X, x),
                prefs.getInt(KEY_FREEZE_Y, dp(230)), size);
        freezeView.setOnTouchListener(new CircleTouch(false));
        windowManager.addView(freezeView, freezeParams);

        robotView = circle("🤖");
        robotParams = circleParams(
                prefs.getInt(KEY_ROBOT_X, x),
                prefs.getInt(KEY_ROBOT_Y, dp(160)), size);
        robotView.setOnTouchListener(new CircleTouch(true));
        windowManager.addView(robotView, robotParams);

        packetSummaryView = packetSummaryView();
        packetSummaryParams = summaryParams(
                prefs.getInt(KEY_SUMMARY_X, dp(18)),
                prefs.getInt(KEY_SUMMARY_Y, dp(110)));
        packetSummaryView.setOnTouchListener(new SummaryMoveTouch());
        packetSummaryView.setVisibility(View.GONE);
        windowManager.addView(packetSummaryView, packetSummaryParams);
        applySummaryScale(false);

        summaryExpandView = summaryExpandView();
        summaryExpandParams = circleParams(0, 0, dp(SUMMARY_CONTROL_DP));
        summaryExpandView.setVisibility(summaryCollapsed ? View.VISIBLE : View.GONE);
        windowManager.addView(summaryExpandView, summaryExpandParams);

        clampAndUpdate(robotView, robotParams, KEY_ROBOT_X, KEY_ROBOT_Y, false);
        clampAndUpdate(freezeView, freezeParams, KEY_FREEZE_X, KEY_FREEZE_Y, false);
        updateSummaryExpandPosition();
        renderStates();
        updatePacketSummary();
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

    private FrameLayout packetSummaryView() {
        FrameLayout root = new FrameLayout(this);
        root.setClickable(true);
        root.setFocusable(false);

        packetSummaryText = new TextView(this);
        packetSummaryText.setTextColor(Color.WHITE);
        packetSummaryText.setTypeface(android.graphics.Typeface.MONOSPACE);
        packetSummaryText.setGravity(Gravity.START);
        packetSummaryText.setIncludeFontPadding(false);
        packetSummaryText.setSingleLine(false);
        packetSummaryText.setLineSpacing(0f, 1.0f);
        packetSummaryText.setClickable(false);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        root.addView(packetSummaryText, textParams);

        summaryCollapseView = summaryControl("−");
        summaryCollapseView.setContentDescription("Hide packet summary");
        summaryCollapseView.setOnClickListener(v -> collapseSummary());
        FrameLayout.LayoutParams collapseParams = new FrameLayout.LayoutParams(
                dp(SUMMARY_CONTROL_DP), dp(SUMMARY_CONTROL_DP), Gravity.TOP | Gravity.END);
        root.addView(summaryCollapseView, collapseParams);

        summaryResizeView = summaryControl("↘");
        summaryResizeView.setContentDescription("Resize packet summary");
        summaryResizeView.setOnTouchListener(new SummaryResizeTouch());
        FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(
                dp(SUMMARY_CONTROL_DP), dp(SUMMARY_CONTROL_DP), Gravity.BOTTOM | Gravity.END);
        root.addView(summaryResizeView, resizeParams);

        return root;
    }

    private TextView summaryControl(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setBackground(controlBg(0xb3262a35));
        view.setClickable(true);
        view.setFocusable(false);
        return view;
    }

    private TextView summaryExpandView() {
        TextView view = summaryControl("+");
        view.setTextSize(20);
        view.setBackground(controlBg(0xee242833));
        view.setContentDescription("Show packet summary");
        view.setOnClickListener(v -> expandSummary());
        return view;
    }

    private GradientDrawable controlBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), 0x99ffffff);
        return d;
    }

    private WindowManager.LayoutParams circleParams(int x, int y, int size) {
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

    private WindowManager.LayoutParams summaryParams(int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
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
    }

    private void updatePacketSummary() {
        if (packetSummaryView == null || packetSummaryParams == null || packetSummaryText == null) return;
        FreezeCore.CycleStats stats = GreatEngine.instance().freezeCore().latestCycleStats();
        if (stats == null) return;
        if (stats.startedAtMillis() == renderedSummaryCycle) return;

        String summary = buildPacketSummary(stats);
        renderedSummaryCycle = stats.startedAtMillis();
        packetSummaryText.setText(summary);
        if (summary.isEmpty()) {
            packetSummaryView.setVisibility(View.GONE);
            return;
        }
        if (!summaryCollapsed) {
            packetSummaryView.setVisibility(View.VISIBLE);
            packetSummaryView.requestLayout();
            packetSummaryView.post(this::clampSummaryToScreen);
        }
    }

    private static String buildPacketSummary(FreezeCore.CycleStats stats) {
        TreeMap<Integer, Integer> notFrozen = new TreeMap<>();
        TreeMap<Integer, Integer> frozen = new TreeMap<>();
        for (int i = 0; i < stats.traceCount(); i++) {
            if (!stats.traceHas(i, FreezeCore.TRACE_REACHED_PAYLOAD)) continue;
            int bytes = stats.tracePayloadBytes(i);
            boolean wasFrozen = stats.traceHas(i, FreezeCore.TRACE_FROZEN);
            TreeMap<Integer, Integer> target = wasFrozen ? frozen : notFrozen;
            target.put(bytes, target.getOrDefault(bytes, 0) + 1);
        }
        if (notFrozen.isEmpty() && frozen.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        appendGroups(out, notFrozen, false);
        if (!notFrozen.isEmpty() && !frozen.isEmpty()) out.append('\n');
        appendGroups(out, frozen, true);
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        return out.toString();
    }

    private static void appendGroups(StringBuilder out, TreeMap<Integer, Integer> groups, boolean frozen) {
        for (Map.Entry<Integer, Integer> entry : groups.entrySet()) {
            out.append(entry.getKey()).append("B ×").append(entry.getValue())
                    .append(frozen ? " Freeze" : " No Freeze").append('\n');
        }
    }

    private void applySummaryScale(boolean persist) {
        summaryScale = clamp(summaryScale,
                FreezeEntrySettingsStore.MIN_SUMMARY_SCALE,
                FreezeEntrySettingsStore.MAX_SUMMARY_SCALE);
        if (packetSummaryView != null && packetSummaryText != null) {
            packetSummaryText.setTextSize(SUMMARY_BASE_TEXT_SP * summaryScale);
            int horizontal = Math.max(dp(4), Math.round(dp(6) * summaryScale));
            int vertical = Math.max(dp(3), Math.round(dp(4) * summaryScale));
            int controlRoom = dp(SUMMARY_CONTROL_DP);
            packetSummaryView.setPadding(horizontal, vertical, horizontal, vertical);
            packetSummaryText.setPadding(0, controlRoom, controlRoom, controlRoom);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xd91a1d25);
            background.setCornerRadius(Math.max(1f, dp(7) * summaryScale));
            background.setStroke(Math.max(1, Math.round(dp(1) * summaryScale)), 0x99ffffff);
            packetSummaryView.setBackground(background);
            packetSummaryView.requestLayout();
            packetSummaryView.post(this::clampSummaryToScreen);
        }
        if (persist) freezeEntrySettings.setSummaryScale(summaryScale);
    }

    private void collapseSummary() {
        summaryCollapsed = true;
        prefs.edit().putBoolean(KEY_SUMMARY_COLLAPSED, true).apply();
        if (packetSummaryView != null) packetSummaryView.setVisibility(View.GONE);
        if (summaryExpandView != null) summaryExpandView.setVisibility(View.VISIBLE);
        updateSummaryExpandPosition();
    }

    private void expandSummary() {
        summaryCollapsed = false;
        prefs.edit().putBoolean(KEY_SUMMARY_COLLAPSED, false).apply();
        if (summaryExpandView != null) summaryExpandView.setVisibility(View.GONE);
        if (packetSummaryView != null && packetSummaryText != null
                && packetSummaryText.getText() != null && packetSummaryText.length() > 0) {
            packetSummaryView.setVisibility(View.VISIBLE);
            packetSummaryView.requestLayout();
            packetSummaryView.post(this::clampSummaryToScreen);
        }
    }

    private void updateSummaryExpandPosition() {
        if (summaryExpandView == null || summaryExpandParams == null || robotParams == null) return;
        int size = Math.max(dp(SUMMARY_CONTROL_DP), summaryExpandParams.width);
        Point screen = screenSize();
        int targetX = robotParams.x + robotParams.width - size / 2;
        int targetY = robotParams.y - size / 2;
        summaryExpandParams.x = clamp(targetX, 0, Math.max(0, screen.x - size));
        summaryExpandParams.y = clamp(targetY, 0, Math.max(0, screen.y - size));
        try { windowManager.updateViewLayout(summaryExpandView, summaryExpandParams); } catch (Throwable ignored) { }
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

    private GradientDrawable circleBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), 0x99ffffff);
        return d;
    }

    private final class CircleTouch implements View.OnTouchListener {
        private final boolean robot;
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging;
        private boolean longPressed;
        private Runnable longPress;

        CircleTouch(boolean robot) { this.robot = robot; }

        @Override public boolean onTouch(View view, MotionEvent event) {
            WindowManager.LayoutParams p = robot ? robotParams : freezeParams;
            if (p == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = p.x;
                    startY = p.y;
                    dragging = false;
                    longPressed = false;
                    if (robot) {
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
                    if (dragging) {
                        move(view, p, startX + Math.round(dx), startY + Math.round(dy));
                        if (robot) updateSummaryExpandPosition();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    cancelLongPress();
                    if (dragging) {
                        if (robot) {
                            savePosition(p, KEY_ROBOT_X, KEY_ROBOT_Y);
                            updateSummaryExpandPosition();
                        } else savePosition(p, KEY_FREEZE_X, KEY_FREEZE_Y);
                    } else if (!longPressed) {
                        if (robot) onRobotClick(); else onFreezeClick();
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

    private final class SummaryMoveTouch implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (packetSummaryParams == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = packetSummaryParams.x;
                    startY = packetSummaryParams.y;
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > dp(5)) dragging = true;
                    if (dragging) moveSummary(startX + Math.round(dx), startY + Math.round(dy));
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (dragging) saveSummaryPosition();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> { return true; }
                default -> { return true; }
            }
        }
    }

    private final class SummaryResizeTouch implements View.OnTouchListener {
        private float downRawX, downRawY;
        private float startScale;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startScale = summaryScale;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    float delta = (dx + dy) * 0.5f;
                    float factor = 1f + delta / Math.max(1f, dp(150));
                    summaryScale = clamp(startScale * factor,
                            FreezeEntrySettingsStore.MIN_SUMMARY_SCALE,
                            FreezeEntrySettingsStore.MAX_SUMMARY_SCALE);
                    applySummaryScale(false);
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applySummaryScale(true);
                    return true;
                }
                default -> { return true; }
            }
        }
    }

    private void move(View view, WindowManager.LayoutParams p, int x, int y) {
        Point screen = screenSize();
        p.x = clamp(x, 0, Math.max(0, screen.x - p.width));
        p.y = clamp(y, 0, Math.max(0, screen.y - p.height));
        try { windowManager.updateViewLayout(view, p); } catch (Throwable ignored) { }
    }

    private void moveSummary(int x, int y) {
        if (packetSummaryView == null || packetSummaryParams == null) return;
        Point screen = screenSize();
        int width = Math.max(1, packetSummaryView.getWidth());
        int height = Math.max(1, packetSummaryView.getHeight());
        packetSummaryParams.x = clamp(x, 0, Math.max(0, screen.x - width));
        packetSummaryParams.y = clamp(y, 0, Math.max(0, screen.y - height));
        try { windowManager.updateViewLayout(packetSummaryView, packetSummaryParams); } catch (Throwable ignored) { }
    }

    private void clampSummaryToScreen() {
        if (packetSummaryView == null || packetSummaryParams == null || packetSummaryView.getVisibility() != View.VISIBLE) return;
        moveSummary(packetSummaryParams.x, packetSummaryParams.y);
    }

    private void savePosition(WindowManager.LayoutParams p, String keyX, String keyY) {
        prefs.edit().putInt(keyX, p.x).putInt(keyY, p.y).apply();
    }

    private void saveSummaryPosition() {
        if (packetSummaryParams == null) return;
        savePosition(packetSummaryParams, KEY_SUMMARY_X, KEY_SUMMARY_Y);
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
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private void removeOverlay() {
        handler.removeCallbacks(renderTick);
        if (robotView != null) try { windowManager.removeView(robotView); } catch (Throwable ignored) { }
        if (freezeView != null) try { windowManager.removeView(freezeView); } catch (Throwable ignored) { }
        if (packetSummaryView != null) try { windowManager.removeView(packetSummaryView); } catch (Throwable ignored) { }
        if (summaryExpandView != null) try { windowManager.removeView(summaryExpandView); } catch (Throwable ignored) { }
        robotView = null;
        freezeView = null;
        packetSummaryView = null;
        packetSummaryText = null;
        summaryCollapseView = null;
        summaryResizeView = null;
        summaryExpandView = null;
        robotParams = null;
        freezeParams = null;
        packetSummaryParams = null;
        summaryExpandParams = null;
        renderedSummaryCycle = Long.MIN_VALUE;
    }

    @Override public void onDestroy() { removeOverlay(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
