package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
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
import com.great.app.config.TriggerSettingsStore;
import com.great.app.core.Capability;
import com.great.app.core.EngineDiagnostics;
import com.great.app.core.FreezeCore;
import com.great.app.core.GreatEngine;
import com.great.app.core.TargetPackages;
import com.great.app.shizuku.ShizukuTouchEngine;
import com.great.app.vpn.GreatVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/** Small control surface. Packet logic remains in GreatEngine and the VPN transport. */
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 1001;
    private static final int VPN_PERMISSION = 1002;
    private static final int SHIZUKU_PERMISSION = 1003;
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
    private final Shizuku.OnBinderDeadListener shizukuBinderDead = () -> {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        refreshShizukuState();
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionResult = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_PERMISSION) return;
        refreshShizukuState();
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku connected");
        } else {
            toast("Shizuku permission denied");
        }
    };

    private TextView configState;
    private TextView start;
    private TextView stats;
    private TextView freezeDuration;
    private TextView triggerDiameter;
    private TextView triggerEngineState;
    private TextView shizukuState;
    private TextView shizukuConnect;
    private TargetAppsStore targetStore;
    private EditText packageInput;
    private TextView targetHeading;
    private LinearLayout targetList;
    private boolean overlayPending;
    private String pendingTriggerAction;
    private CapabilitySettingsStore tuningStore;
    private TriggerSettingsStore triggerStore;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tuningStore = new CapabilitySettingsStore(this);
        triggerStore = new TriggerSettingsStore(this);
        targetStore = new TargetAppsStore(this);
        applySavedTuning();

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived);
        Shizuku.addBinderDeadListener(shizukuBinderDead);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult);

        setContentView(content());
        refresh();
        refreshShizukuState();
        refreshTriggerState();
        handler.post(statsTick);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) {
            if (overlayPending) {
                overlayPending = false;
                startOverlay();
            }
            if (pendingTriggerAction != null) {
                String action = pendingTriggerAction;
                pendingTriggerAction = null;
                startTriggerOverlay(action);
            }
        }
        refreshShizukuState();
        refreshTriggerState();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statsTick);
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived);
        Shizuku.removeBinderDeadListener(shizukuBinderDead);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult);
        super.onDestroy();
    }

    private View content() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(32), dp(22), dp(28));

        add(root, text("GREAT", 14, ACCENT, true), 0);
        add(root, text("Connection", 32, TEXT, true), 12);
        add(root, text("Freeze for the applications you choose.", 14, MUTED, false), 8);

        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(SURFACE, 20));
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
        controls.setOnClickListener(v -> requestOverlay());
        add(card, controls, 10);
        add(root, card, 26);

        add(root, shizukuCard(), 14);
        add(root, triggerCard(), 14);
        add(root, targetCard(), 14);

        LinearLayout tuningCard = column();
        tuningCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        tuningCard.setBackground(round(SURFACE, 20));
        add(tuningCard, text("AUTO RELEASE", 11, ACCENT, true), 0);
        add(tuningCard, text("Manual Freeze uses this safety limit. Holding the trigger circle stays active until you lift your finger.",
                12, MUTED, false), 10);
        freezeDuration = durationRow(tuningCard);
        refreshDurations();
        add(root, tuningCard, 14);

        LinearLayout diagnosticCard = column();
        diagnosticCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        diagnosticCard.setBackground(round(SURFACE, 20));
        add(diagnosticCard, text("ENGINE", 11, ACCENT, true), 0);
        stats = text("Waiting for packets…", 13, TEXT, false);
        add(diagnosticCard, stats, 12);
        add(root, diagnosticCard, 14);

        add(root, text("Freeze • Shizuku trigger • Editable targets • 10,000-packet limit",
                12, MUTED, false), 18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View shizukuCard() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(SURFACE, 20));

        add(card, text("SHIZUKU", 11, ACCENT, true), 0);
        add(card, text("Connect GREAT to Shizuku for the privileged touch forwarding engine.",
                12, MUTED, false), 8);

        shizukuState = text("Checking Shizuku…", 14, TEXT, false);
        add(card, shizukuState, 12);

        shizukuConnect = button("CONNECT SHIZUKU", true);
        shizukuConnect.setOnClickListener(v -> requestShizuku());
        add(card, shizukuConnect, 14);
        return card;
    }

    private View triggerCard() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(SURFACE, 20));

        add(card, text("FREEZE TRIGGER", 11, ACCENT, true), 0);
        add(card, text("Press inside the circle: Freeze ON. Drag outside while still holding: Freeze stays ON. Lift anywhere: Freeze OFF.",
                12, MUTED, false), 8);

        triggerEngineState = text("Touch engine idle", 12, TEXT, false);
        add(card, triggerEngineState, 10);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Diameter", 14, TEXT, true);
        sizeRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView minus = smallButton("−");
        triggerDiameter = text("", 14, TEXT, true);
        triggerDiameter.setGravity(Gravity.CENTER);
        triggerDiameter.setMinWidth(dp(78));
        TextView plus = smallButton("+");
        minus.setOnClickListener(v -> adjustTriggerDiameter(-TriggerSettingsStore.STEP_CM));
        plus.setOnClickListener(v -> adjustTriggerDiameter(TriggerSettingsStore.STEP_CM));
        sizeRow.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        sizeRow.addView(triggerDiameter, new LinearLayout.LayoutParams(dp(86), dp(42)));
        sizeRow.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        add(card, sizeRow, 14);
        refreshTriggerDiameter();

        TextView lock = button("SHOW / LOCK TRIGGER", true);
        lock.setOnClickListener(v -> requestTriggerOverlay(FreezeTriggerOverlayService.ACTION_LOCK));
        add(card, lock, 14);

        TextView edit = button("EDIT TRIGGER POSITION", false);
        edit.setOnClickListener(v -> requestTriggerOverlay(FreezeTriggerOverlayService.ACTION_EDIT));
        add(card, edit, 10);

        TextView hide = button("HIDE TRIGGER", false);
        hide.setOnClickListener(v -> startTriggerOverlay(FreezeTriggerOverlayService.ACTION_HIDE));
        add(card, hide, 10);
        return card;
    }

    private void adjustTriggerDiameter(float deltaCm) {
        triggerStore.setDiameterCm(triggerStore.diameterCm() + deltaCm);
        refreshTriggerDiameter();
        startTriggerOverlay(FreezeTriggerOverlayService.ACTION_REFRESH);
    }

    private void refreshTriggerDiameter() {
        if (triggerDiameter != null) {
            triggerDiameter.setText(String.format(Locale.US, "%.2f cm", triggerStore.diameterCm()));
        }
    }

    private void refreshTriggerState() {
        if (triggerEngineState == null) return;
        ShizukuTouchEngine touch = ShizukuTouchEngine.instance();
        String state = touch.status();
        triggerEngineState.setText(state == null ? "Touch engine idle" : state);
    }

    private void requestTriggerOverlay(String action) {
        if (!Settings.canDrawOverlays(this)) {
            pendingTriggerAction = action;
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            return;
        }
        startTriggerOverlay(action);
    }

    private void startTriggerOverlay(String action) {
        if (FreezeTriggerOverlayService.ACTION_HIDE.equals(action)) {
            startService(new Intent(this, FreezeTriggerOverlayService.class).setAction(action));
            return;
        }
        if (!isShizukuGranted() && !FreezeTriggerOverlayService.ACTION_EDIT.equals(action)) {
            toast("Connect Shizuku before locking the Freeze trigger");
            return;
        }
        startService(new Intent(this, FreezeTriggerOverlayService.class).setAction(action));
        handler.postDelayed(this::refreshTriggerState, 350);
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) {
            refreshShizukuState();
            toast("Start Shizuku first, then try again");
            return;
        }

        int permission;
        try {
            permission = Shizuku.checkSelfPermission();
        } catch (Throwable e) {
            refreshShizukuState();
            toast("Shizuku is not available");
            return;
        }

        if (permission == PackageManager.PERMISSION_GRANTED) {
            refreshShizukuState();
            toast("Shizuku already connected");
            return;
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            toast("Shizuku permission was denied. Allow GREAT from Shizuku permissions.");
            return;
        }

        Shizuku.requestPermission(SHIZUKU_PERMISSION);
    }

    private boolean isShizukuGranted() {
        if (!Shizuku.pingBinder()) return false;
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable e) { return false; }
    }

    private void refreshShizukuState() {
        handler.post(() -> {
            if (shizukuState == null || shizukuConnect == null) return;

            if (!Shizuku.pingBinder()) {
                shizukuState.setText("Disconnected • Shizuku service unavailable");
                shizukuConnect.setText("CONNECT SHIZUKU");
                shizukuConnect.setAlpha(1f);
                refreshTriggerState();
                return;
            }

            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuState.setText("Connected • Permission granted");
                    shizukuConnect.setText("SHIZUKU CONNECTED");
                    shizukuConnect.setAlpha(.75f);
                } else {
                    shizukuState.setText("Ready • Permission required");
                    shizukuConnect.setText("CONNECT SHIZUKU");
                    shizukuConnect.setAlpha(1f);
                }
            } catch (Throwable e) {
                shizukuState.setText("Disconnected • Shizuku unavailable");
                shizukuConnect.setText("CONNECT SHIZUKU");
                shizukuConnect.setAlpha(1f);
            }
            refreshTriggerState();
        });
    }

    private TextView durationRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("Freeze", 14, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(title, titleLp);

        TextView minus = smallButton("−");
        TextView value = text("5 s", 15, TEXT, true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(58));
        TextView plus = smallButton("+");

        minus.setOnClickListener(v -> adjustDuration(-1));
        plus.setOnClickListener(v -> adjustDuration(1));

        row.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        row.addView(value, new LinearLayout.LayoutParams(dp(64), dp(42)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        add(parent, row, 14);
        return value;
    }

    private TextView smallButton(String label) {
        TextView v = text(label, 20, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(0xff202431, 12));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void adjustDuration(int delta) {
        int value = tuningStore.setFreezeSeconds(tuningStore.freezeSeconds() + delta);
        GreatEngine.instance().freezeCore().setFreezeDurationSeconds(value);
        refreshDurations();
    }

    private void applySavedTuning() {
        GreatEngine.instance().freezeCore().setFreezeDurationSeconds(tuningStore.freezeSeconds());
    }

    private void refreshDurations() {
        if (freezeDuration != null) freezeDuration.setText(tuningStore.freezeSeconds() + " s");
    }

    private View targetCard() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(SURFACE, 20));
        targetHeading = text("", 14, ACCENT, true);
        targetHeading.setMinHeight(dp(48));
        targetHeading.setGravity(Gravity.CENTER_VERTICAL);
        targetHeading.setContentDescription("Manage targeted applications");
        add(card, targetHeading, 0);
        add(card, text("Tap to edit. Enter a package name, then press Add. Only listed apps are affected by Freeze.",
                12, MUTED, false), 4);

        LinearLayout editor = column();
        editor.setVisibility(View.GONE);
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setTextColor(TEXT);
        packageInput.setHintTextColor(MUTED);
        packageInput.setHint("com.android.chrome");
        packageInput.setContentDescription("Application package name");
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        add(editor, packageInput, 12);
        TextView addButton = button("ADD APPLICATION", true);
        addButton.setOnClickListener(v -> {
            try {
                targetStore.add(packageInput.getText().toString());
                packageInput.setText("");
                refreshTargets();
                toast("Application added. Active Freeze ends when the target list changes.");
            } catch (IllegalArgumentException e) { packageInput.setError(e.getMessage()); }
        });
        add(editor, addButton, 10);
        targetList = column();
        add(editor, targetList, 8);
        add(card, editor, 0);
        targetHeading.setOnClickListener(v -> editor.setVisibility(editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        card.setOnClickListener(v -> editor.setVisibility(editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        refreshTargets();
        return card;
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
            TextView label = text(name, 13, TEXT, false);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView remove = text("Remove", 13, ACCENT, true);
            remove.setMinHeight(dp(48));
            remove.setGravity(Gravity.CENTER);
            remove.setPadding(dp(10), 0, dp(4), 0);
            remove.setContentDescription("Remove " + name);
            remove.setOnClickListener(v -> { targetStore.remove(name); refreshTargets(); });
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            add(targetList, row, 4);
        }
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, PICK_CONFIG);
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
        Intent service = new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_START);
        startService(service);
        toast("Starting GREAT…");
    }

    private void stopGreat() {
        GreatEngine.instance().freezeCore().setHoldTrigger(false);
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_STOP));
        startService(new Intent(this, CapabilityOverlayService.class).setAction(CapabilityOverlayService.ACTION_HIDE));
        startService(new Intent(this, FreezeTriggerOverlayService.class).setAction(FreezeTriggerOverlayService.ACTION_HIDE));
        toast("Stopping GREAT…");
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startOverlay();
            return;
        }
        overlayPending = true;
        Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(permission);
    }

    private void startOverlay() {
        startService(new Intent(this, CapabilityOverlayService.class)
                .setAction(CapabilityOverlayService.ACTION_SHOW));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION && resultCode == RESULT_OK) {
            startGreat();
            return;
        }
        if (requestCode != PICK_CONFIG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importConfig(data.getData());
    }

    private void importConfig(Uri uri) {
        byte[] raw = null;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open file");
            raw = readBounded(input, AwgConfigParser.MAX_CONFIG_BYTES);
            new AwgConfigParser().parse(raw);
            new SecureConfigStore(this).save(raw);
            toast("Configuration imported securely");
            refresh();
        } catch (Exception e) {
            toast("Invalid AmneziaWG configuration");
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    private void refresh() {
        boolean ready = new SecureConfigStore(this).exists();
        configState.setText(ready ? "Configuration ready" : "Import an AmneziaWG .conf file");
        start.setAlpha(ready ? 1f : .45f);
    }

    private void refreshStats() {
        if (stats == null) return;
        GreatEngine engine = GreatEngine.instance();
        EngineDiagnostics.Snapshot s = engine.diagnostics().snapshot();
        FreezeCore freeze = engine.freezeCore();
        String active = engine.capabilities().snapshot().enabled(Capability.FREEZE) ? "ON" : "OFF";
        String held = freeze.holdTriggerActive() ? "HELD" : "IDLE";
        stats.setText("OUT " + s.outbound() + "   IN " + s.inbound() +
                "\nPASS " + s.passed() + "   HOLD " + s.held() +
                "\nRELEASE " + s.released() + "   EVICTED " + s.schedulerRejected() +
                "\nFREEZE Q " + freeze.freezeQueueSize() + "/" + FreezeCore.CAPACITY +
                "\nFREEZE " + active + "   TRIGGER " + held + "   TARGETS " + targetStore.names().size());
        refreshTriggerState();
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
        v.setContentDescription(label);
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
}
