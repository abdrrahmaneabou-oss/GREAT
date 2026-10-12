package com.great.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.FreezeEntrySettingsStore;
import com.great.app.core.FreezeCore;
import com.great.app.core.GreatEngine;
import com.great.app.monitor.RobotFreezeOrchestrator;

/** Adds Freeze-entry ramp and Robot skip tuning cards without disturbing the main activity layout. */
final class FreezeEntryTuningUi {
    private static final int SURFACE = 0xff151822;
    private static final int TEXT = 0xfff2f3fa;
    private static final int MUTED = 0xffa2aabc;
    private static final int ACCENT = 0xffb89aff;
    private static final String RAMP_TAG = "great_freeze_entry_ramp_card";
    private static final String SKIP_TAG = "great_robot_skip_card";

    private FreezeEntryTuningUi() { }

    static void ensure(Activity activity, View decorRoot) {
        FreezeEntrySettingsStore store = new FreezeEntrySettingsStore(activity);
        apply(store);

        ScrollView scroll = findScrollView(decorRoot);
        if (scroll == null || scroll.getChildCount() == 0) return;
        View content = scroll.getChildAt(0);
        if (!(content instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) content;

        int historyIndex = findHistoryIndex(root);
        if (findTagged(root, RAMP_TAG) == null) {
            View card = rampCard(activity, store);
            card.setTag(RAMP_TAG);
            addAt(root, card, historyIndex);
            if (historyIndex < root.getChildCount()) historyIndex++;
        }
        if (findTagged(root, SKIP_TAG) == null) {
            View card = robotSkipCard(activity, store);
            card.setTag(SKIP_TAG);
            addAt(root, card, historyIndex);
        }
    }

    private static void apply(FreezeEntrySettingsStore store) {
        GreatEngine.instance().freezeCore().setRampProfile(
                store.rampDurationMinMs(), store.rampDurationMaxMs(),
                store.rampStage1MinPercent(), store.rampStage1MaxPercent(),
                store.rampStage2MinPercent(), store.rampStage2MaxPercent());
        RobotFreezeOrchestrator.instance().setSkipPercent(store.robotSkipPercent());
    }

    private static View rampCard(Activity activity, FreezeEntrySettingsStore store) {
        LinearLayout card = card(activity);
        add(activity, card, text(activity, "تدرّج الدخول إلى Freeze", 11, ACCENT, true), 0);
        add(activity, card, text(activity,
                "يتحكم في سرعة وقوة الانتقال إلى التجميد. أثناء مدة Ramp يستخدم التطبيق مرحلتين لاحتمال حجز الحزم، وبعد انتهاء المدة يصبح الحجز 100% للحزم المطابقة.",
                12, MUTED, false), 8);

        add(activity, card, text(activity, "مدة Ramp • ms", 11, ACCENT, true), 16);
        EditText durationMin = numeric(activity, "Min ms", store.rampDurationMinMs());
        EditText durationMax = numeric(activity, "Max ms", store.rampDurationMaxMs());
        add(activity, card, pair(activity, durationMin, durationMax), 8);

        add(activity, card, text(activity, "المرحلة الأولى • Hold %", 11, ACCENT, true), 16);
        EditText stage1Min = numeric(activity, "Min %", store.rampStage1MinPercent());
        EditText stage1Max = numeric(activity, "Max %", store.rampStage1MaxPercent());
        add(activity, card, pair(activity, stage1Min, stage1Max), 8);

        add(activity, card, text(activity, "المرحلة الثانية • Hold %", 11, ACCENT, true), 16);
        EditText stage2Min = numeric(activity, "Min %", store.rampStage2MinPercent());
        EditText stage2Max = numeric(activity, "Max %", store.rampStage2MaxPercent());
        add(activity, card, pair(activity, stage2Min, stage2Max), 8);

        add(activity, card, text(activity,
                "الافتراضي الحالي: 70–130 ms • المرحلة 1: 20–40% • المرحلة 2: 55–75%. المسموح للمدة 0–5000 ms وللنسب 0–100%.",
                11, MUTED, false), 8);

        TextView save = button(activity, "SAVE FREEZE ENTRY RAMP");
        save.setOnClickListener(v -> {
            Integer dMin = read(durationMin, 0, 5000, "0..5000 ms");
            Integer dMax = read(durationMax, 0, 5000, "0..5000 ms");
            Integer s1Min = read(stage1Min, 0, 100, "0..100%");
            Integer s1Max = read(stage1Max, 0, 100, "0..100%");
            Integer s2Min = read(stage2Min, 0, 100, "0..100%");
            Integer s2Max = read(stage2Max, 0, 100, "0..100%");
            if (dMin == null || dMax == null || s1Min == null || s1Max == null || s2Min == null || s2Max == null) return;
            if (!ordered(durationMin, dMin, dMax) || !ordered(stage1Min, s1Min, s1Max) || !ordered(stage2Min, s2Min, s2Max)) return;
            store.setRampProfile(dMin, dMax, s1Min, s1Max, s2Min, s2Max);
            apply(store);
            Toast.makeText(activity, "Freeze entry ramp saved", Toast.LENGTH_SHORT).show();
        });
        add(activity, card, save, 14);
        return card;
    }

    private static View robotSkipCard(Activity activity, FreezeEntrySettingsStore store) {
        LinearLayout card = card(activity);
        add(activity, card, text(activity, "نسبة تجاهل Robot", 11, ACCENT, true), 0);
        add(activity, card, text(activity,
                "تحدد احتمال تجاهل محاولة Robot بالكامل. النطاق 0% إلى 25%. إذا لم تحفظ أي قيمة فالسلوك يبقى 25% كما كان سابقًا.",
                12, MUTED, false), 8);

        TextView value = text(activity, store.robotSkipPercent() + "%", 22, TEXT, true);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        add(activity, card, value, 14);

        SeekBar seek = new SeekBar(activity);
        seek.setMax(FreezeEntrySettingsStore.MAX_ROBOT_SKIP_PERCENT);
        seek.setProgress(store.robotSkipPercent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        add(activity, card, seek, 8);

        TextView save = button(activity, "SAVE ROBOT SKIP");
        save.setOnClickListener(v -> {
            store.setRobotSkipPercent(seek.getProgress());
            RobotFreezeOrchestrator.instance().setSkipPercent(seek.getProgress());
            Toast.makeText(activity, "Robot skip set to " + seek.getProgress() + "%", Toast.LENGTH_SHORT).show();
        });
        add(activity, card, save, 12);
        return card;
    }

    private static LinearLayout card(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18));
        card.setBackground(round(activity, SURFACE, 20));
        return card;
    }

    private static TextView text(Activity activity, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static EditText numeric(Activity activity, String hint, int value) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setText(String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        return input;
    }

    private static LinearLayout pair(Activity activity, View left, View right) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1f);
        b.leftMargin = dp(activity, 10);
        row.addView(left, a);
        row.addView(right, b);
        return row;
    }

    private static TextView button(Activity activity, String label) {
        TextView view = text(activity, label, 14, TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13));
        view.setBackground(round(activity, 0xff202431, 16));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static Integer read(EditText input, int min, int max, String allowed) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) { input.setError("Required"); return null; }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) { input.setError("Allowed: " + allowed); return null; }
            return parsed;
        } catch (NumberFormatException e) {
            input.setError("Enter a valid number");
            return null;
        }
    }

    private static boolean ordered(EditText minView, int min, int max) {
        if (min <= max) return true;
        minView.setError("Minimum must be ≤ maximum");
        return false;
    }

    private static GradientDrawable round(Activity activity, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(activity, radiusDp));
        d.setStroke(dp(activity, 1), 0xff292e3d);
        return d;
    }

    private static void add(Activity activity, LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(activity, topDp);
        parent.addView(child, p);
    }

    private static void addAt(LinearLayout root, View card, int index) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp((Activity) root.getContext(), 14);
        int safe = Math.max(0, Math.min(index, root.getChildCount()));
        root.addView(card, safe, p);
    }

    private static View findTagged(ViewGroup root, String tag) {
        for (int i = 0; i < root.getChildCount(); i++) if (tag.equals(root.getChildAt(i).getTag())) return root.getChildAt(i);
        return null;
    }

    private static int findHistoryIndex(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            if (containsText(root.getChildAt(i), "FREEZE HISTORY • LAST 5")
                    || containsText(root.getChildAt(i), "سجل دورات التجميد • آخر 5")) return i;
        }
        return root.getChildCount();
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            return t != null && expected.contentEquals(t);
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), expected)) return true;
        return false;
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findScrollView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
