package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import com.great.app.config.SecureConfigStore;
import com.great.app.config.TargetAppsStore;
import com.great.app.core.Capability;
import com.great.app.core.EngineDiagnostics;
import com.great.app.core.FreezeCore;
import com.great.app.core.GreatEngine;
import com.great.app.core.TargetPackages;
import com.great.app.vpn.GreatVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import rikka.shizuku.Shizuku;

/** GREAT control surface after replacing the touch trigger with a visual white monitor. */
public final class GreatMainActivity extends Activity {
    public static final String ACTION_REQUEST_MONITOR_CAPTURE = "com.great.app.action.REQUEST_MONITOR_CAPTURE";

    private static final int PICK_CONFIG = 1001;
    private static final int VPN_PERMISSION = 1002;
    private static final int SHIZUKU_PERMISSION = 1003;
    private static final int MONITOR_CAPTURE = 1004;
    private static final int BG = 0xff0c0e14, SURFACE = 0xff151822, TEXT = 0xfff2f3fa,
            MUTED = 0xffa2aabc, ACCENT = 0xffb89aff;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            refreshStats();
            handler.postDelayed(this, 750);
        }
    };
    private final Shizuku.OnBinderReceivedListener shizukuBinderReceived = this::refreshShizukuState;
    private final Shizuku.OnBinderDeadListener shizukuBinderDead = this::refreshShizukuState;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionResult = (code, result) -> {
        if (code != SHIZUKU_PERMISSION) return;
        refreshShizukuState();
        toast(result == PackageManager.PERMISSION_GRANTED ? "Shizuku connected" : "Shizuku permission denied");
    };

    private CapabilitySettingsStore tuningStore;
    private TargetAppsStore targetStore;
    private MediaProjectionManager projectionManager;
    private TextView configState, start, stats, freezeDuration, monitorState, shizukuState, shizukuConnect, targetHeading;
    private LinearLayout targetList;
    private EditText packageInput;
    private boolean overlayPending;
    private boolean monitorCapturePending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        tuningStore = new CapabilitySettingsStore(this);
        targetStore = new TargetAppsStore(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        GreatEngine.instance().freezeCore().setFreezeDurationSeconds(tuningStore.freezeSeconds());

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived);
        Shizuku.addBinderDeadListener(shizukuBinderDead);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult);

        setContentView(buildContent());
        refreshConfig();
        refreshShizukuState();
        refreshMonitorState();
        handler.post(statsTick);
        if (ACTION_REQUEST_MONITOR_CAPTURE.equals(getIntent().getAction())) handler.post(this::requestMonitorCapture);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_REQUEST_MONITOR_CAPTURE.equals(intent.getAction())) requestMonitorCapture();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) {
            if (overlayPending) { overlayPending = false; showControlOverlay(); }
            if (monitorCapturePending) { monitorCapturePending = false; launchCapturePrompt(); }
        }
        refreshShizukuState();
        refreshMonitorState();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statsTick);
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived);
        Shizuku.removeBinderDeadListener(shizukuBinderDead);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult);
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(32), dp(22), dp(28));
        add(root, text("GREAT", 14, ACCENT, true), 0);
        add(root, text("Connection", 32, TEXT, true), 12);
        add(root, text("Freeze for the applications you choose.", 14, MUTED, false), 8);
        add(root, connectionCard(), 26);
        add(root, shizukuCard(), 14);
        add(root, monitorCard(), 14);
        add(root, targetCard(), 14);
        add(root, tuningCard(), 14);
        add(root, diagnosticsCard(), 14);
        add(root, text("Freeze • Visual white monitor • Editable targets • 10,000-packet limit", 12, MUTED, false), 18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View connectionCard() {
        LinearLayout card = card();
        add(card, text("AMNEZIAWG", 11, ACCENT, true), 0);
        configState = text("Checking configuration…", 14, TEXT, false);
        add(card, configState, 12);
        TextView importer = button("Import .conf", true);
        importer.setOnClickListener(v -> chooseConfig());
        add(card, importer, 18);
        start = button("START GREAT", false);
        start.setOnClickListener(v -> requestVpn());
        add(card, start, 10);
        TextView stop = button("STOP GREAT", false);
        stop.setOnClickListener(v -> stopGreat());
        add(card, stop, 10);
        TextView controls = button("SHOW FREEZE CONTROL", false);
        controls.setOnClickListener(v -> requestControlOverlay());
        add(card, controls, 10);
        return card;
    }

    private View shizukuCard() {
        LinearLayout card = card();
        add(card, text("SHIZUKU", 11, ACCENT, true), 0);
        add(card, text("Connect GREAT to Shizuku for privileged features used by future engines.", 12, MUTED, false), 8);
        shizukuState = text("Checking Shizuku…", 14, TEXT, false);
        add(card, shizukuState, 12);
        shizukuConnect = button("CONNECT SHIZUKU", true);
        shizukuConnect.setOnClickListener(v -> requestShizuku());
        add(card, shizukuConnect, 14);
        return card;
    }

    private View monitorCard() {
        LinearLayout card = card();
        add(card, text("VISUAL FREEZE MONITOR", 11, ACCENT, true), 0);
        add(card, text("PixelTrigger-style 0.30 mm sensor. GREEN = white detected / armed / Freeze OFF. RED = white disappeared / Freeze ON.",
                12, MUTED, false), 8);
        monitorState = text("Monitor stopped", 12, TEXT, false);
        add(card, monitorState, 10);
        TextView arm = button("START / ARM MONITOR", true);
        arm.setOnClickListener(v -> requestMonitorCapture());
        add(card, arm, 14);
        TextView edit = button("EDIT MONITOR POSITION", false);
        edit.setOnClickListener(v -> editMonitor());
        add(card, edit, 10);
        TextView stop = button("STOP MONITOR", false);
        stop.setOnClickListener(v -> stopMonitor());
        add(card, stop, 10);
        return card;
    }

    private View tuningCard() {
        LinearLayout card = card();
        add(card, text("AUTO RELEASE", 11, ACCENT, true), 0);
        add(card, text("Manual Freeze uses this safety limit. Visual-monitor Freeze stays active until the white target returns.",
                12, MUTED, false), 10);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("Freeze", 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView minus = smallButton("−");
        freezeDuration = text(tuningStore.freezeSeconds() + " s", 15, TEXT, true);
        freezeDuration.setGravity(Gravity.CENTER);
        TextView plus = smallButton("+");
        minus.setOnClickListener(v -> adjustDuration(-1));
        plus.setOnClickListener(v -> adjustDuration(1));
        row.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        row.addView(freezeDuration, new LinearLayout.LayoutParams(dp(64), dp(42)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        add(card, row, 14);
        return card;
    }

    private View diagnosticsCard() {
        LinearLayout card = card();
        add(card, text("ENGINE", 11, ACCENT, true), 0);
        stats = text("Waiting for packets…", 13, TEXT, false);
        add(card, stats, 12);
        return card;
    }

    private View targetCard() {
        LinearLayout card = card();
        targetHeading = text("", 14, ACCENT, true);
        targetHeading.setMinHeight(dp(48));
        targetHeading.setGravity(Gravity.CENTER_VERTICAL);
        add(card, targetHeading, 0);
        add(card, text("Tap to edit. Enter a package name, then press Add. Only listed apps are affected by Freeze.", 12, MUTED, false), 4);
        LinearLayout editor = column();
        editor.setVisibility(View.GONE);
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setTextColor(TEXT);
        packageInput.setHintTextColor(MUTED);
        packageInput.setHint("com.android.chrome");
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        add(editor, packageInput, 12);
        TextView addButton = button("ADD APPLICATION", true);
        addButton.setOnClickListener(v -> addTarget());
        add(editor, addButton, 10);
        targetList = column();
        add(editor, targetList, 8);
        add(card, editor, 0);
        targetHeading.setOnClickListener(v -> editor.setVisibility(editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        refreshTargets();
        return card;
    }

    private void requestMonitorCapture() {
        if (FreezeMonitorService.isRunning()) {
            startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_LOCK));
            toast("Visual monitor armed");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            monitorCapturePending = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        launchCapturePrompt();
    }

    private void launchCapturePrompt() {
        if (projectionManager == null) { toast("Screen capture is unavailable"); return; }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), MONITOR_CAPTURE);
    }

    private void editMonitor() {
        if (!FreezeMonitorService.isRunning()) { toast("Start the visual monitor first"); return; }
        startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_EDIT));
        handler.postDelayed(this::refreshMonitorState, 100);
    }

    private void stopMonitor() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_STOP));
        handler.postDelayed(this::refreshMonitorState, 100);
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) { refreshShizukuState(); toast("Start Shizuku first, then try again"); return; }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuState(); toast("Shizuku already connected"); return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Shizuku permission was denied. Allow GREAT from Shizuku permissions."); return;
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION);
        } catch (Throwable e) { refreshShizukuState(); toast("Shizuku is not available"); }
    }

    private void refreshShizukuState() {
        handler.post(() -> {
            if (shizukuState == null || shizukuConnect == null) return;
            if (!Shizuku.pingBinder()) {
                shizukuState.setText("Disconnected • Shizuku service unavailable");
                shizukuConnect.setText("CONNECT SHIZUKU");
                shizukuConnect.setAlpha(1f);
                return;
            }
            try {
                boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                shizukuState.setText(granted ? "Connected • Permission granted" : "Ready • Permission required");
                shizukuConnect.setText(granted ? "SHIZUKU CONNECTED" : "CONNECT SHIZUKU");
                shizukuConnect.setAlpha(granted ? .75f : 1f);
            } catch (Throwable e) {
                shizukuState.setText("Disconnected • Shizuku unavailable");
            }
        });
    }

    private void refreshMonitorState() {
        if (monitorState != null) monitorState.setText(FreezeMonitorService.status());
    }

    private void adjustDuration(int delta) {
        int value = tuningStore.setFreezeSeconds(tuningStore.freezeSeconds() + delta);
        GreatEngine.instance().freezeCore().setFreezeDurationSeconds(value);
        freezeDuration.setText(value + " s");
    }

    private void addTarget() {
        try {
            targetStore.add(packageInput.getText().toString());
            packageInput.setText("");
            refreshTargets();
            toast("Application added. Active Freeze ends when the target list changes.");
        } catch (IllegalArgumentException e) { packageInput.setError(e.getMessage()); }
    }

    private void refreshTargets() {
        if (targetHeading == null || targetList == null) return;
        java.util.List<String> names = targetStore.names();
        targetHeading.setText("TARGET APPLICATIONS  " + names.size() + "/" + TargetPackages.LIMIT);
        targetList.removeAllViews();
        if (names.isEmpty()) {
            add(targetList, text("No applications added. Freeze affects nothing until you add a target.", 12, MUTED, false), 8);
            return;
        }
        for (String name : names) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(name, 13, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView remove = text("Remove", 13, ACCENT, true);
            remove.setMinHeight(dp(48));
            remove.setGravity(Gravity.CENTER);
            remove.setOnClickListener(v -> { targetStore.remove(name); refreshTargets(); });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            add(targetList, row, 4);
        }
    }

    private void requestVpn() {
        if (!new SecureConfigStore(this).exists()) { toast("Import an AmneziaWG .conf file first"); return; }
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION); else startGreat();
    }

    private void startGreat() {
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_START));
        toast("Starting GREAT…");
    }

    private void stopGreat() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_STOP));
        startService(new Intent(this, CapabilityOverlayService.class).setAction(CapabilityOverlayService.ACTION_HIDE));
        startService(new Intent(this, FreezeMonitorService.class).setAction(FreezeMonitorService.ACTION_STOP));
        toast("Stopping GREAT…");
    }

    private void requestControlOverlay() {
        if (Settings.canDrawOverlays(this)) { showControlOverlay(); return; }
        overlayPending = true;
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void showControlOverlay() {
        startService(new Intent(this, CapabilityOverlayService.class).setAction(CapabilityOverlayService.ACTION_SHOW));
    }

    private void chooseConfig() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_CONFIG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MONITOR_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, FreezeMonitorService.class)
                        .setAction(FreezeMonitorService.ACTION_START)
                        .putExtra(FreezeMonitorService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(FreezeMonitorService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service); else startService(service);
                toast("Starting visual monitor…");
                handler.postDelayed(this::refreshMonitorState, 500);
            } else toast("Screen capture permission is required for the visual monitor");
            return;
        }
        if (requestCode == VPN_PERMISSION && resultCode == RESULT_OK) { startGreat(); return; }
        if (requestCode == PICK_CONFIG && resultCode == RESULT_OK && data != null && data.getData() != null) importConfig(data.getData());
    }

    private void importConfig(Uri uri) {
        byte[] raw = null;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open file");
            raw = readBounded(input, AwgConfigParser.MAX_CONFIG_BYTES);
            new AwgConfigParser().parse(raw);
            new SecureConfigStore(this).save(raw);
            toast("Configuration imported securely");
            refreshConfig();
        } catch (Exception e) { toast("Invalid AmneziaWG configuration"); }
        finally { if (raw != null) Arrays.fill(raw, (byte) 0); }
    }

    private void refreshConfig() {
        boolean ready = new SecureConfigStore(this).exists();
        configState.setText(ready ? "Configuration ready" : "Import an AmneziaWG .conf file");
        start.setAlpha(ready ? 1f : .45f);
    }

    private void refreshStats() {
        if (stats == null) return;
        GreatEngine engine = GreatEngine.instance();
        EngineDiagnostics.Snapshot s = engine.diagnostics().snapshot();
        FreezeCore freeze = engine.freezeCore();
        String manual = engine.capabilities().snapshot().enabled(Capability.FREEZE) ? "ON" : "OFF";
        String visual = freeze.holdTriggerActive() ? "FIRED" : "IDLE";
        stats.setText("OUT " + s.outbound() + "   IN " + s.inbound()
                + "\nPASS " + s.passed() + "   HOLD " + s.held()
                + "\nRELEASE " + s.released() + "   EVICTED " + s.schedulerRejected()
                + "\nFREEZE Q " + freeze.freezeQueueSize() + "/" + FreezeCore.CAPACITY
                + "\nFREEZE " + manual + "   MONITOR " + visual + "   TARGETS " + targetStore.names().size());
        refreshMonitorState();
    }

    private LinearLayout card() {
        LinearLayout v = column();
        v.setPadding(dp(18), dp(18), dp(18), dp(18));
        v.setBackground(round(SURFACE, 20));
        return v;
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(sp); v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 16, primary ? Color.BLACK : TEXT, true);
        v.setGravity(Gravity.CENTER); v.setMinHeight(dp(58)); v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(round(primary ? ACCENT : 0xff202431, 18)); v.setClickable(true); v.setFocusable(true);
        return v;
    }

    private TextView smallButton(String label) {
        TextView v = text(label, 20, TEXT, true); v.setGravity(Gravity.CENTER);
        v.setBackground(round(0xff202431, 12)); v.setClickable(true); v.setFocusable(true); return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(1), 0xff292e3d); return d;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(topDp); parent.addView(child, p);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

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
