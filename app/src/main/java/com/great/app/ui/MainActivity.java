package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.AwgConfigParser;
import com.great.app.config.SecureConfigStore;
import com.great.app.vpn.GreatVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Build 1 UI: intentionally small. Networking logic lives outside this class. */
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 1001;
    private static final int VPN_PERMISSION = 1002;
    private static final int BG = 0xff0c0e14, SURFACE = 0xff151822, TEXT = 0xfff2f3fa, MUTED = 0xffa2aabc, ACCENT = 0xffb89aff;
    private TextView configState;
    private TextView start;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(content());
        refresh();
    }

    private View content() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(32), dp(22), dp(28));

        TextView brand = text("GREAT", 14, ACCENT, true);
        add(root, brand, 0);
        TextView title = text("Connection", 32, TEXT, true);
        add(root, title, 12);
        TextView subtitle = text("A small engine with one clear packet path.", 14, MUTED, false);
        add(root, subtitle, 8);

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
        add(root, card, 26);

        TextView note = text("Build 1 • official AmneziaWG transport", 12, MUTED, false);
        add(root, note, 18);
        return root;
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, PICK_CONFIG);
    }

    private void requestVpn() {
        if (!new SecureConfigStore(this).exists()) {
            toast("Import an AmneziaWG .conf file first"); return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION);
        else startGreat();
    }

    private void startGreat() {
        Intent service = new Intent(this, GreatVpnService.class).setAction(GreatVpnService.ACTION_START);
        startService(service);
        toast("Starting GREAT with the official AmneziaWG engine…");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION && resultCode == RESULT_OK) { startGreat(); return; }
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

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false); return v;
    }
    private TextView button(String label, boolean primary) {
        TextView v = text(label, 16, primary ? Color.BLACK : TEXT, true);
        v.setGravity(Gravity.CENTER); v.setMinHeight(dp(58)); v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(round(primary ? ACCENT : 0xff202431, 18)); v.setClickable(true); v.setFocusable(true); v.setContentDescription(label);
        return v;
    }
    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(1), 0xff292e3d); return d;
    }
    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(topDp); parent.addView(child, p);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
