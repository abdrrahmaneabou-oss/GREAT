package com.great.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.SecureConfigStore;

/** Adds config deletion to the existing VPN config card without changing import behavior. */
final class ConfigManagementUi {
    private static final int TEXT = 0xfff2f3fa;
    private static final String DELETE_TAG = "great_delete_vpn_config";

    private ConfigManagementUi() { }

    static void ensure(Activity activity, View root) {
        View card = findCard(root);
        if (!(card instanceof LinearLayout)) return;
        LinearLayout layout = (LinearLayout) card;
        for (int i = 0; i < layout.getChildCount(); i++) {
            if (DELETE_TAG.equals(layout.getChildAt(i).getTag())) {
                updateButtonState(activity, (TextView) layout.getChildAt(i));
                return;
            }
        }

        TextView delete = button(activity, "DELETE CONFIG");
        delete.setTag(DELETE_TAG);
        delete.setOnClickListener(v -> {
            SecureConfigStore store = new SecureConfigStore(activity);
            if (!store.exists()) {
                Toast.makeText(activity, "No saved VPN config", Toast.LENGTH_LONG).show();
                updateButtonState(activity, delete);
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Delete VPN config?")
                    .setMessage("The saved encrypted VPN config will be removed. You can import a new .conf file afterward.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("DELETE", (dialog, which) -> {
                        store.delete();
                        updateConfigState(root);
                        updateButtonState(activity, delete);
                        Toast.makeText(activity, "VPN config deleted", Toast.LENGTH_LONG).show();
                    })
                    .show();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 10);
        layout.addView(delete, params);
        updateButtonState(activity, delete);
    }

    private static void updateButtonState(Activity activity, TextView button) {
        boolean exists = new SecureConfigStore(activity).exists();
        button.setEnabled(exists);
        button.setAlpha(exists ? 1f : 0.45f);
    }

    private static void updateConfigState(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence value = textView.getText();
            if (value != null && "Configuration ready".contentEquals(value)) {
                textView.setText("Import an AmneziaWG .conf file");
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) updateConfigState(group.getChildAt(i));
    }

    private static View findCard(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (containsText(group, "VPN CONFIG") || containsText(group, "إعدادات الـ VPN")) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child instanceof LinearLayout
                            && (containsText(child, "VPN CONFIG") || containsText(child, "إعدادات الـ VPN"))) {
                        return child;
                    }
                }
                if (group instanceof LinearLayout) return group;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCard(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            return value != null && expected.contentEquals(value);
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private static TextView button(Activity activity, String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 58));
        view.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff202431);
        bg.setCornerRadius(dp(activity, 18));
        bg.setStroke(dp(activity, 1), 0xff292e3d);
        view.setBackground(bg);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
