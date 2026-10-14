package com.great.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.SecureConfigStore;

/** Adds a real DELETE VPN CONFIG action directly below STOP VPN. */
final class DeleteVpnConfigUi {
    private static final String TAG = "great.delete_vpn_config";

    private DeleteVpnConfigUi() { }

    static void ensure(Activity activity, View root) {
        if (findByTag(root) != null) return;
        TextView stop = findText(root, "STOP VPN");
        if (stop == null || !(stop.getParent() instanceof LinearLayout)) return;

        LinearLayout parent = (LinearLayout) stop.getParent();
        int stopIndex = parent.indexOfChild(stop);
        TextView delete = new TextView(activity);
        delete.setTag(TAG);
        delete.setText("DELETE VPN CONFIG");
        delete.setTextSize(16f);
        delete.setTextColor(Color.WHITE);
        delete.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        delete.setGravity(Gravity.CENTER);
        delete.setMinHeight(dp(activity, 58));
        delete.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        delete.setClickable(true);
        delete.setFocusable(true);

        Drawable background = stop.getBackground();
        if (background != null && background.getConstantState() != null) {
            delete.setBackground(background.getConstantState().newDrawable().mutate());
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 10);
        parent.addView(delete, Math.min(stopIndex + 1, parent.getChildCount()), params);

        delete.setOnClickListener(v -> confirmDelete(activity, root, stop));
    }

    private static void confirmDelete(Activity activity, View root, TextView stopButton) {
        SecureConfigStore store = new SecureConfigStore(activity);
        if (!store.exists()) {
            refreshConfigState(root);
            Toast.makeText(activity, "No VPN configuration is stored", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Delete VPN config?")
                .setMessage("This stops GREAT VPN and permanently deletes the imported VPN configuration stored by GREAT.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> {
                    // Reuse the app's existing STOP VPN behavior before removing the stored config.
                    stopButton.performClick();
                    SecureConfigStore current = new SecureConfigStore(activity);
                    current.delete();
                    refreshConfigState(root);
                    Toast.makeText(activity, "VPN configuration deleted", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private static void refreshConfigState(View root) {
        TextView ready = findText(root, "Configuration ready");
        if (ready != null) ready.setText("Import an AmneziaWG .conf file");
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence value = text.getText();
            if (value != null && expected.equals(value.toString())) return text;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findByTag(View view) {
        Object tag = view.getTag();
        if (TAG.equals(tag)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByTag(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
