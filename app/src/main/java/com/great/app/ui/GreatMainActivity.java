package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.AwgConfigParser;
import com.great.app.config.CapabilitySettingsStore;
import com.great.app.config.MonitorSettingsStore;
import com.great.app.config.SecureConfigStore;
import com.great.app.config.TargetAppsStore;
import com.great.app.core.Capability;
import com.great.app.core.GreatEngine;
import com.great.app.core.TargetPackages;
import com.great.app.vpn.GreatVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/** Minimal GREAT control surface: config, VPN, targets and the two floating circles. */
public final class GreatMainActivity extends Activity {
    public static final String ACTION_REQUEST_MONITOR_CAPTURE = "com.great.app.action.REQUEST_MONITOR_CAPTURE";
    public static final String ACTION_REQUEST_MONITOR_EDIT = "com.great.app.action.REQUEST_MONITOR_EDIT";

    private static final int PICK_CONFIG = 1001;
    private static final int VPN_PERMISSION = 1002;
    private static final int MONITOR_CAPTURE = 1004;
    private static final int BG = 0xff0c0e14;
    private static final int SURFACE = 0xff151822;
    private static final int TEXT = 0xfff2f3fa;
    private static final int MUTED = 0xffa2aabc;
    private static final int ACCENT = 0xffb89aff;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TargetAppsStore targetStore;
    private MonitorSettingsStore monitorSettings;
    private MediaProjectionManager projectionManager;
    private TextView configState;
    private TextView targetHeading;
    private TextView robotTimeoutValue;
    private LinearLayout targetList;
    private EditText packageInput;
    private boolean overlayPending;
    private boolean monitorCapturePending;
    private boolean monitorEditAfterStart;
    private boolean monitorStartActiveAfterCapture;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        targetStore = new TargetAppsStore(this);
        monitorSettings = new MonitorSettingsStore(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        GreatEngine.instance().freezeCore().setFreezeDurationSeconds(new CapabilitySettingsStore(this).freezeSeconds());
        setContentView(buildContent());
        refreshConfig();
        handleAction(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAction(intent);
    }

    private void handleAction(Intent intent) {
        if (intent == null) return;
        if (ACTION_REQUEST_MONITOR_CAPTURE.equals(intent.getAction())) {
            handler.post(() -> requestMonitorCapture(true, false));
        } else if (ACTION_REQUEST_MONITOR_EDIT.equals(intent.getAction())) {
            handler.post(() -> requestMonitorCapture(false, true));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) {
            if (overlayPending) {
                overlayPending = false;
                showControlOverlay();
                ensureMonitorSessionForCircles();
            }
            if (monitorCapturePending) {
                monitorCapturePending = false;
                launchCapturePrompt();
            }
        }
    }

    private View buildContent() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(32), dp(22), dp(28));
        add(root, text("GREAT", 14, ACCENT, true), 0);
        add(root, text("Control", 32, TEXT, true), 12);
        add(root, text("VPN • Visual monitor • Manual Freeze", 13, MUTED, false), 8);
        add(root, configCard(), 26);
        add(root, vpnCard(), 14);
        add(root, targetCard(), 14);
        add(root, robotTimeoutCard(), 14);
        add(root, showCirclesCard(), 14);
        add(root, hideCirclesCard(), 14);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View configCard() {
        LinearLayout card = card();
        add(card, text("VPN CONFIG", 11, ACCENT, true), 0);
        configState = text("Checking configuration…", 14, TEXT, false);
        add(card, configState, 10);
        TextView importButton = button("IMPORT CONFIG", true);
        importButton.setOnClickListener(v -> chooseConfig());
        add(card, importButton, 14);
        return card;
    }

    private View vpnCard() {
        LinearLayout card = card();
        add(card, text("VPN", 11, ACCENT, true), 0);
        TextView start = button("START VPN", true);
        start.setOnClickListener(v -> requestVpn());
        add(card, start, 12);
        TextView stop = button("STOP VPN", false);
        stop.setOnClickListener(v -> stopGreat());
        add(card, stop, 10);
        return card;
    }

    private View robotTimeoutCard() {
        LinearLayout card = card();
        add(card, text("ROBOT MAX FREEZE", 11, ACCENT, true), 0);
        add(card, text("After this limit, robot Freeze stops and cannot fire again until white returns and re-arms it.",
                12, MUTED, false), 8);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = smallButton("−");
        robotTimeoutValue = text(formatRobotTimeout(), 18, TEXT, true);
        robotTimeoutValue.setGravity(Gravity.CENTER);
        TextView plus = smallButton("+");
        minus.setOnClickListener(v -> adjustRobotTimeout(-1));
        plus.setOnClickListener(v -> adjustRobotTimeout(1));

        row.addView(minus, new LinearLayout.LayoutParams(dp(54), dp(48)));
        row.addView(robotTimeoutValue, new LinearLayout.LayoutParams(0, dp(48), 1f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(54), dp(48)));
        add(card, row, 14);
        add(card, text("0.10 s  →  5.00 s   •   step 0.10 s", 11, MUTED, false), 8);
        return card;
    }

    private View showCirclesCard() {
        LinearLayout card = card();
        add(card, text("SHOW CIRCLES", 11, ACCENT, true), 0);
        add(card, text("Shows 🤖 and ❄️ and prepares screen capture immediately.", 12, MUTED, false), 8);
        TextView show = button("SHOW CIRCLES", true);
        show.setOnClickListener(v -> requestControlOverlay());
        add(card, show, 14);
        return card;
    }

    private View hideCirclesCard() {
        LinearLayout card = card();
        add(card, text("HIDE CIRCLES", 11, ACCENT, true), 0);
        TextView hide = button("HIDE CIRCLES", false);
        hide.setOnClickListener(v -> hideControlOverlay());
        add(card, hide, 12);
        return card;
    }

    private View targetCard() {
        LinearLayout card = card();
        targetHeading = text("", 14, ACCENT, true);
        targetHeading.setMinHeight(dp(48));
        targetHeading.setGravity(Gravity.CENTER_VERTICAL);
        add(card, targetHeading, 0);
        add(card, text("Required for Freeze. Tap to add or remove package names.", 12, MUTED, false), 4);

        LinearLayout editor = column();
        editor.setVisibility(View.GONE);
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setTextColor(TEXT);
        packageInput.setHintTextColor(MUTED);
        packageInput.setHint("com.dts.freefireth");
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        add(editor, packageInput, 12);

        TextView addButton = button("ADD APPLICATION", true);
        addButton.setOnClickListener(v -> addTarget());
        add(editor, addButton, 10);
        targetList = column();
        add(editor, targetList, 8);
        add(card, editor, 0);
        targetHeading.setOnClickListener(v -> editor.setVisibility(
                editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        refreshTargets();
        return card;
    }

    private void adjustRobotTimeout(int deltaTenths) {
        monitorSettings.setMaxFreezeTenths(monitorSettings.maxFreezeTenths() + deltaTenths);
        if (robotTimeoutValue != null) robotTimeoutValue.setText(formatRobotTimeout());
    }

    private String formatRobotTimeout() {
        return String.format(Locale.US, "%.2f s", monitorSettings.maxFreezeTenths() / 10.0f);
    }

    private void requestVpn() {
        if (!new SecureConfigStore(this).exists()) {
            toast("Import an AmneziaWG .conf file first");
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION);
        else startGreat();
    }

    private void startGreat() {
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_START));
        toast("Starting VPN…");
    }

    private void stopGreat() {
        GreatEngine.instance().capabilities().set(Capability.FREEZE, false);
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_STOP));
        if (FreezeMonitorService.isRunning()) {
            startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_STOP));
        }
        toast("VPN stopped");
    }

    private void requestControlOverlay() {
        if (Settings.canDrawOverlays(this)) {
            showControlOverlay();
            ensureMonitorSessionForCircles();
            return;
        }
        overlayPending = true;
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void showControlOverlay() {
        startService(new Intent(this, CapabilityOverlayService.class)
                .setAction(CapabilityOverlayService.ACTION_SHOW));
    }

    private void hideControlOverlay() {
        startService(new Intent(this, CapabilityOverlayService.class)
                .setAction(CapabilityOverlayService.ACTION_HIDE));
    }

    /** SHOW CIRCLES establishes MediaProjection immediately but leaves robot monitoring OFF/gray. */
    private void ensureMonitorSessionForCircles() {
        if (FreezeMonitorService.isRunning()) return;
        monitorSettings.setMonitoringEnabled(false);
        monitorStartActiveAfterCapture = false;
        monitorEditAfterStart = false;
        launchCapturePrompt();
    }

    /** Used by the robot itself when capture was not prepared yet. */
    private void requestMonitorCapture(boolean startActive, boolean editAfterStart) {
        monitorEditAfterStart = editAfterStart;
        monitorStartActiveAfterCapture = startActive;
        if (FreezeMonitorService.isRunning()) {
            startService(new Intent(this, FreezeMonitorService.class)
                    .setAction(editAfterStart ? FreezeMonitorService.ACTION_EDIT
                            : FreezeMonitorService.ACTION_MONITORING_ON));
            return;
        }
        monitorSettings.setMonitoringEnabled(startActive);
        if (!Settings.canDrawOverlays(this)) {
            monitorCapturePending = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        launchCapturePrompt();
    }

    private void launchCapturePrompt() {
        if (projectionManager == null) {
            toast("Screen capture is unavailable");
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), MONITOR_CAPTURE);
    }

    private void chooseConfig() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), PICK_CONFIG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MONITOR_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                monitorSettings.setMonitoringEnabled(monitorStartActiveAfterCapture);
                Intent service = new Intent(this, FreezeMonitorService.class)
                        .setAction(FreezeMonitorService.ACTION_START)
                        .putExtra(FreezeMonitorService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(FreezeMonitorService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                else startService(service);
                if (monitorEditAfterStart) {
                    handler.postDelayed(() -> startService(new Intent(this, FreezeMonitorService.class)
                            .setAction(FreezeMonitorService.ACTION_EDIT)), 450);
                }
            } else {
                toast("Screen capture permission is required for the monitor");
            }
            monitorEditAfterStart = false;
            monitorStartActiveAfterCapture = false;
            return;
        }
        if (requestCode == VPN_PERMISSION && resultCode == RESULT_OK) {
            startGreat();
            return;
        }
        if (requestCode == PICK_CONFIG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importConfig(data.getData());
        }
    }

    private void importConfig(Uri uri) {
        byte[] raw = null;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open file");
            raw = readBounded(input, AwgConfigParser.MAX_CONFIG_BYTES);
            new AwgConfigParser().parse(raw);
            new SecureConfigStore(this).save(raw);
            toast("Configuration imported");
            refreshConfig();
        } catch (Exception e) {
            toast("Invalid AmneziaWG configuration");
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    private void refreshConfig() {
        boolean ready = new SecureConfigStore(this).exists();
        if (configState != null) configState.setText(
                ready ? "Configuration ready" : "Import an AmneziaWG .conf file");
    }

    private void addTarget() {
        try {
            targetStore.add(packageInput.getText().toString());
            packageInput.setText("");
            refreshTargets();
        } catch (IllegalArgumentException e) {
            packageInput.setError(e.getMessage());
        }
    }

    private void refreshTargets() {
        if (targetHeading == null || targetList == null) return;
        java.util.List<String> names = targetStore.names();
        targetHeading.setText("TARGET APPLICATIONS  " + names.size() + "/" + TargetPackages.LIMIT);
        targetList.removeAllViews();
        if (names.isEmpty()) {
            add(targetList, text("No target applications added.", 12, MUTED, false), 8);
            return;
        }
        for (String name : names) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(name, 13, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView remove = text("Remove", 13, ACCENT, true);
            remove.setMinHeight(dp(48));
            remove.setGravity(Gravity.CENTER);
            remove.setOnClickListener(v -> {
                targetStore.remove(name);
                refreshTargets();
            });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            add(targetList, row, 4);
        }
    }

    private LinearLayout card() {
        LinearLayout v = column();
        v.setPadding(dp(18), dp(18), dp(18), dp(18));
        v.setBackground(round(SURFACE, 20));
        return v;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 16, primary ? Color.BLACK : TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(58));
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(round(primary ? ACCENT : 0xff202431, 18));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private TextView smallButton(String label) {
        TextView v = text(label, 22, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(0xff202431, 14));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), 0xff292e3d);
        return d;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        parent.addView(child, p);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static byte[] readBounded(InputStream input, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > max) throw new IllegalArgumentException("too large");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
}
