package com.great.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.WeakHashMap;

/** Keeps the original open, vertically scrolling cards while applying the newer calm palette. */
final class LegacyPaletteUi {
    private static final int BG = 0xff0a0c11;
    private static final int SURFACE = 0xff12161f;
    private static final int FIELD = 0xff0f131b;
    private static final int STROKE = 0xff2a3140;
    private static final int TEXT = 0xfff5f7fb;
    private static final int MUTED = 0xff929bad;
    private static final int ACCENT = 0xffb69cff;
    // Each card/view only needs its base styling once. Re-creating a background
    // on every global layout caused redundant draws and could overwrite live UI.
    private static final WeakHashMap<View, Boolean> STYLED = new WeakHashMap<>();

    private LegacyPaletteUi() { }

    static void ensure(Activity activity, View root) {
        ScrollView scroll = findScroll(root);
        if (scroll == null || scroll.getChildCount() == 0) return;
        View child = scroll.getChildAt(0);
        if (!(child instanceof LinearLayout)) return;
        LinearLayout column = (LinearLayout) child;
        column.setBackgroundColor(BG);

        for (int i = 0; i < column.getChildCount(); i++) {
            View item = column.getChildAt(i);
            if (i >= 3 && item instanceof ViewGroup
                    && !Boolean.TRUE.equals(STYLED.get(item))) {
                item.setBackground(cardBg(activity));
            }
            styleTree(activity, item);
        }
    }

    private static void styleTree(Activity activity, View view) {
        if (Boolean.TRUE.equals(STYLED.put(view, Boolean.TRUE))) return;
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTextColor(TEXT);
            input.setHintTextColor(MUTED);
            input.setBackground(fieldBg(activity));
        } else if (view instanceof Switch) {
            ((Switch) view).setTextColor(TEXT);
        } else if (view instanceof SeekBar) {
            view.setMinimumHeight(dp(activity, 42));
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence value = text.getText();
            String label = value == null ? "" : value.toString();
            if (text.isClickable()) {
                boolean danger = containsAny(label.toLowerCase(), "stop", "delete", "remove", "hide", "إيقاف", "حذف", "إخفاء");
                text.setTextColor(danger ? 0xffff7d8f : TEXT);
                if (text.getBackground() != null) {
                    text.setBackground(buttonBg(activity, danger));
                }
            } else if (looksLikeHeading(label)) {
                text.setTextColor(ACCENT);
            }
        }

        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) styleTree(activity, group.getChildAt(i));
    }

    private static boolean looksLikeHeading(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty() || s.length() > 90) return false;
        return s.equals(s.toUpperCase())
                || s.contains("Freeze") || s.contains("Robot") || s.contains("Payload")
                || s.contains("VPN") || s.contains("التجميد") || s.contains("روبوت")
                || s.contains("السجل") || s.contains("التطبيقات المستهدفة");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static GradientDrawable cardBg(Activity activity) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(dp(activity, 20));
        d.setStroke(Math.max(1, dp(activity, 1)), STROKE);
        return d;
    }

    private static GradientDrawable fieldBg(Activity activity) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(FIELD);
        d.setCornerRadius(dp(activity, 12));
        d.setStroke(Math.max(1, dp(activity, 1)), STROKE);
        return d;
    }

    private static GradientDrawable buttonBg(Activity activity, boolean danger) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(danger ? 0xff24151a : 0xff211a31);
        d.setCornerRadius(dp(activity, 14));
        d.setStroke(Math.max(1, dp(activity, 1)), danger ? 0xff63313a : 0xff594a78);
        return d;
    }

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView && view.getVisibility() == View.VISIBLE) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findScroll(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
