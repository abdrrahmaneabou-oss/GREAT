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
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.Capability;
import com.great.app.core.GreatEngine;

import java.util.EnumMap;
import java.util.Locale;

/** Floating capability controls. Packet behavior remains entirely in GreatEngine. */
public final class CapabilityOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_CAPABILITIES";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_CAPABILITIES";

    private static final String PREFS = "great_overlay";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final int BG = 0xee12151d;
    private static final int ACTIVE = 0xffb89aff;
    private static final int INACTIVE = 0xff252a37;
    private static final int TEXT = 0xfff3f4fa;
    private static final int MUTED = 0xffa2aabc;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Capability, TextView> buttons = new EnumMap<>(Capability.class);
    private final Runnable renderTick = new Runnable() {
        @Override public void run() {
            renderAll();
            renderMonitorToggle();
            if (root != null) handler.postDelayed(this, 250);
        }
    };

    private WindowManager windowManager;
    private LinearLayout root;
    private WindowManager.LayoutParams overlayParams;
    private SharedPreferences prefs;
    private MonitorSettingsStore monitorSettings;
    private LinearLayout monitorMenu;
    private TextView monitorSizeLabel;
    private TextView monitorToggle;

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
        if (root != null) return;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackground(round(BG, 18));

        TextView drag = new TextView(this);
        drag.setText("⋮⋮   GREAT");
        drag.setTextSize(12);
        drag.setTextColor(MUTED);
        drag.setGravity(Gravity.CENTER);
        drag.setMinHeight(dp(28));
        drag.setContentDescription("Drag controls");
        drag.setOnTouchListener(this::dragOverlay);
        root.addView(drag, new LinearLayout.LayoutParams(-1, -2));

        addCapability(Capability.FREEZE, "Freeze");
        addMonitorControls();

        Point screen = screenSize();
        int savedX = prefs.getInt(KEY_X, Math.max(0, screen.x - dp(190)));
        int savedY = prefs.getInt(KEY_Y, dp(140));
        overlayParams = new WindowManager.LayoutParams(
                dp(178), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = savedX;
        overlayParams.y = savedY;
        windowManager.addView(root, overlayParams);
        root.post(this::clampAndUpdate);
        handler.post(renderTick);
    }

    private void addMonitorControls() {
        TextView monitor = menuButton("VISUAL MONITOR");
        monitor.setOnClickListener(v -> {
            boolean open = monitorMenu.getVisibility() == View.VISIBLE;
            monitorMenu.setVisibility(open ? View.GONE : View.VISIBLE);
            if (!open) {
                updateMonitorSizeLabel();
                renderMonitorToggle();
            }
            root.post(this::clampAndUpdate);
        });
        add(root, monitor, 6);

        monitorMenu = new LinearLayout(this);
        monitorMenu.setOrientation(LinearLayout.VERTICAL);
        monitorMenu.setPadding(dp(8), dp(8), dp(8), dp(8));
        monitorMenu.setBackground(round(0xff1b1f2a, 12));
        monitorMenu.setVisibility(View.GONE);

        monitorToggle = smallAction("");
        monitorToggle.setOnClickListener(v -> {
            boolean next = !FreezeMonitorService.isMonitoring();
            renderMonitorToggle();
            sendMonitorAction(next
                    ? FreezeMonitorService.ACTION_START
                    : FreezeMonitorService.ACTION_STOP);
            Toast.makeText(this, next ? "Monitor enabled" : "Monitor disabled", Toast.LENGTH_SHORT).show();
        });
        renderMonitorToggle();
        add(monitorMenu, monitorToggle, 0);

        monitorSizeLabel = new TextView(this);
        monitorSizeLabel.setTextSize(12);
        monitorSizeLabel.setTextColor(TEXT);
        updateMonitorSizeLabel();
        add(monitorMenu, monitorSizeLabel, 6);

        SeekBar size = new SeekBar(this);
        int steps = Math.round((MonitorSettingsStore.MAX_RING_DP - MonitorSettingsStore.MIN_RING_DP)
                / MonitorSettingsStore.STEP_DP);
        size.setMax(steps);
        size.setProgress(Math.round((monitorSettings.ringDp() - MonitorSettingsStore.MIN_RING_DP)
                / MonitorSettingsStore.STEP_DP));
        size.setContentDescription("Visual ring size (sampling fixed at 0.3 mm)");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = MonitorSettingsStore.MIN_RING_DP + progress * MonitorSettingsStore.STEP_DP;
                monitorSettings.setRingDp(value);
                updateMonitorSizeLabel();
                sendMonitorAction(FreezeMonitorService.ACTION_REFRESH);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        add(monitorMenu, size, 4);

        TextView position = smallAction("ADJUST POSITION");
        position.setOnClickListener(v -> sendMonitorAction(FreezeMonitorService.ACTION_EDIT));
        add(monitorMenu, position, 6);

        TextView save = smallAction("SAVE / START MONITOR");
        save.setTextColor(Color.BLACK);
        save.setBackground(round(ACTIVE, 12));
        save.setOnClickListener(v -> {
            sendMonitorAction(FreezeMonitorService.ACTION_LOCK);
            monitorMenu.setVisibility(View.GONE);
            root.post(this::clampAndUpdate);
            Toast.makeText(this, "Monitor saved", Toast.LENGTH_SHORT).show();
        });
        add(monitorMenu, save, 6);

        add(root, monitorMenu, 6);
    }

    private void renderMonitorToggle() {
        if (monitorToggle == null) return;
        boolean enabled = FreezeMonitorService.isMonitoring();
        monitorToggle.setText(enabled ? "VISUAL MONITOR  ON" : "VISUAL MONITOR  OFF");
        monitorToggle.setTextColor(enabled ? Color.BLACK : TEXT);
        monitorToggle.setBackground(round(enabled ? ACTIVE : INACTIVE, 12));
    }

    private void sendMonitorAction(String action) {
        if (!Settings.canDrawOverlays(this)) return;
        FreezeMonitorService.request(this, action);
    }

    private void updateMonitorSizeLabel() {
        if (monitorSizeLabel == null) return;
        monitorSizeLabel.setText(String.format(Locale.US, "RING  %d dp", monitorSettings.ringDp()));
    }

    private TextView menuButton(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(13);
        v.setTextColor(TEXT);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(40));
        v.setBackground(round(INACTIVE, 14));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private TextView smallAction(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(11);
        v.setTextColor(TEXT);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(38));
        v.setBackground(round(INACTIVE, 12));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void addCapability(Capability capability, String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setOnClickListener(v -> {
            GreatEngine engine = GreatEngine.instance();
            if (!engine.capabilities().snapshot().enabled(capability) && engine.targetCount() == 0) {
                Toast.makeText(this, "Start GREAT with an installed target application first", Toast.LENGTH_LONG).show();
                return;
            }
            engine.capabilities().toggle(capability);
        });
        buttons.put(capability, button);
        render(button, capability);
        add(root, button, 6);
    }

    private boolean dragOverlay(android.view.View view, MotionEvent event) {
        if (overlayParams == null || root == null) return false;
        DragState state = (DragState) view.getTag();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                state = new DragState(overlayParams.x, overlayParams.y, event.getRawX(), event.getRawY());
                view.setTag(state);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (state == null) return false;
                int x = state.startX + Math.round(event.getRawX() - state.touchX);
                int y = state.startY + Math.round(event.getRawY() - state.touchY);
                moveTo(x, y);
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state != null) {
                    clampAndUpdate();
                    prefs.edit().putInt(KEY_X, overlayParams.x).putInt(KEY_Y, overlayParams.y).apply();
                }
                view.setTag(null);
                return true;
            }
            default -> { return false; }
        }
    }

    private void moveTo(int x, int y) {
        Point screen = screenSize();
        int width = root.getWidth() > 0 ? root.getWidth() : dp(178);
        int height = root.getHeight() > 0 ? root.getHeight() : dp(150);
        overlayParams.x = clamp(x, 0, Math.max(0, screen.x - width));
        overlayParams.y = clamp(y, 0, Math.max(0, screen.y - height));
        try { windowManager.updateViewLayout(root, overlayParams); } catch (Exception ignored) { }
    }

    private void clampAndUpdate() {
        if (root == null || overlayParams == null) return;
        moveTo(overlayParams.x, overlayParams.y);
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

    private void renderAll() {
        for (Capability capability : Capability.values()) {
            TextView button = buttons.get(capability);
            if (button != null) render(button, capability);
        }
    }

    private void render(TextView button, Capability capability) {
        boolean enabled = GreatEngine.instance().capabilities().snapshot().enabled(capability);
        button.setTextColor(enabled ? Color.BLACK : TEXT);
        button.setBackground(round(enabled ? ACTIVE : INACTIVE, 14));
        button.setAlpha(enabled ? 1f : .92f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void removeOverlay() {
        handler.removeCallbacks(renderTick);
        buttons.clear();
        if (root == null) return;
        try { windowManager.removeView(root); } catch (Exception ignored) { }
        root = null;
        overlayParams = null;
        monitorMenu = null;
        monitorSizeLabel = null;
        monitorToggle = null;
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

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
