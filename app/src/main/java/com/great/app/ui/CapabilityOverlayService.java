package com.great.app.ui;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.great.app.core.Capability;
import com.great.app.core.GreatEngine;

/** Small, dumb overlay: taps only change capability state in GreatEngine. */
public final class CapabilityOverlayService extends Service {
    public static final String ACTION_SHOW = "com.great.app.action.SHOW_CAPABILITIES";
    public static final String ACTION_HIDE = "com.great.app.action.HIDE_CAPABILITIES";

    private static final int BG = 0xee12151d;
    private static final int ACTIVE = 0xffb89aff;
    private static final int INACTIVE = 0xff252a37;
    private static final int TEXT = 0xfff3f4fa;

    private WindowManager windowManager;
    private LinearLayout root;

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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

        addCapability(Capability.FREEZE, "Freeze");
        addCapability(Capability.GHOST, "Ghost");
        addCapability(Capability.TELEPORT, "Teleport");

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(132), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(12);
        params.y = dp(140);
        windowManager.addView(root, params);
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
            boolean enabled = engine.capabilities().snapshot().enabled(capability);
            engine.capabilities().set(capability, !enabled);
            render(button, capability);
        });
        render(button, capability);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = root.getChildCount() == 0 ? 0 : dp(6);
        root.addView(button, lp);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void removeOverlay() {
        if (root == null) return;
        try { windowManager.removeView(root); } catch (Exception ignored) { }
        root = null;
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
