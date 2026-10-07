package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.AwgConfigParser;
import com.great.app.config.CapabilitySettingsStore;
import com.great.app.config.SecureConfigStore;
import com.great.app.core.EngineDiagnostics;
import com.great.app.core.FoxCapabilityCore;
import com.great.app.core.GreatEngine;
import com.great.app.vpn.GreatVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Small control surface. Packet logic remains in GreatEngine and the VPN transport. */
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 1001;
    private static final int VPN_PERMISSION = 1002;
    private static final int BG = 0xff0c0e14, SURFACE = 0xff151822, TEXT = 0xfff2f3fa,
            MUTED = 0xffa2aabc, ACCENT = 0xffb89aff;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            refreshStats();
            handler.postDelayed(this, 750);
        }
    };

    private TextView configState;
    private TextView start;
    private TextView stats;
    private TextView freezeDuration;
    private TextView teleportDuration;
    private boolean overlayPending;
    private CapabilitySettingsStore tuningStore;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tuningStore = new CapabilitySettingsStore(this);
        applySavedTuning();
        setContentView(content());
        refresh();
        handler.post(statsTick);
    }

    @Override protected void onResume() {
        super.onResume();
        if (overlayPending && Settings.canDrawOverlays(this)) {
            overlayPending = false;
            startOverlay();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statsTick);
        super.onDestroy();
    }

    private View content() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(32), dp(22), dp(28));

        add(root, text("GREAT", 14, ACCENT, true), 0);
        add(root, text("Connection", 32, TEXT, true), 12);
        add(root, text("One packet path. One capability engine.", 14, MUTED, false), 8);

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

        TextView controls = button("SHOW CONTROLS", false);
        controls.setOnClickListener(v -> requestOverlay());
        add(card, controls, 10);
        add(root, card, 26);

        LinearLayout tuningCard = column();
        tuningCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        tuningCard.setBackground(round(SURFACE, 20));
        add(tuningCard, text("AUTO RELEASE", 11, ACCENT, true), 0);
        add(tuningCard, text("Freeze and Teleport release automatically at the selected limit. Turning them off releases earlier.",
                12, MUTED, false), 10);
        freezeDuration = durationRow(tuningCard, "Freeze", true);
        teleportDuration = durationRow(tuningCard, "Teleport", false);
        refreshDurations();
        add(root, tuningCard, 14);

        LinearLayout diagnosticCard = column();
        diagnosticCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        diagnosticCard.setBackground(round(SURFACE, 20));
        add(diagnosticCard, text("ENGINE", 11, ACCENT, true), 0);
        stats = text("Waiting for packets…", 13, TEXT, false);
        add(diagnosticCard, stats, 12);
        add(root, diagnosticCard, 14);

        add(root, text("Build 2B • FOX capability core transplant + timed release + movable overlay",
                12, MUTED, false), 18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private TextView durationRow(LinearLayout parent, String label, boolean freeze) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text(label, 14, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(title, titleLp);

        TextView minus = smallButton("−");
        TextView value = text("5 s", 15, TEXT, true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(58));
        TextView plus = smallButton("+");

        minus.setOnClickListener(v -> adjustDuration(freeze, -1));
        plus.setOnClickListener(v -> adjustDuration(freeze, 1));

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

    private void adjustDuration(boolean freeze, int delta) {
        GreatEngine engine = GreatEngine.instance();
        if (freeze) {
            int value = tuningStore.setFreezeSeconds(tuningStore.freezeSeconds() + delta);
            engine.foxCapabilities().setFreezeDurationSeconds(value);
        } else {
            int value = tuningStore.setTeleportSeconds(tuningStore.teleportSeconds() + delta);
            engine.foxCapabilities().setTeleportDurationSeconds(value);
        }
        refreshDurations();
    }

    private void applySavedTuning() {
        GreatEngine engine = GreatEngine.instance();
        engine.foxCapabilities().setFreezeDurationSeconds(tuningStore.freezeSeconds());
        engine.foxCapabilities().setTeleportDurationSeconds(tuningStore.teleportSeconds());
    }

    private void refreshDurations() {
        if (freezeDuration != null) freezeDuration.setText(tuningStore.freezeSeconds() + " s");
        if (teleportDuration != null) teleportDuration.setText(tuningStore.teleportSeconds() + " s");
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
        startService(new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_STOP));
        startService(new Intent(this, CapabilityOverlayService.class).setAction(CapabilityOverlayService.ACTION_HIDE));
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
        FoxCapabilityCore fox = engine.foxCapabilities();
        String capabilities = engine.capabilities().snapshot().enabledCapabilities().toString();
        stats.setText("OUT " + s.outbound() + "   IN " + s.inbound() +
                "\nPASS " + s.passed() + "   DROP " + s.dropped() + "   HOLD " + s.held() +
                "\nRELEASE " + s.released() + "   REPLAY " + s.replayed() + "   BAD " + s.malformed() +
                "\nFREEZE Q " + fox.freezeQueueSize() + "   TELE Q " + fox.teleportQueueSize() +
                (fox.teleportReplayActive() ? "   REPLAYING" : "") +
                "\nACTIVE " + capabilities);
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
