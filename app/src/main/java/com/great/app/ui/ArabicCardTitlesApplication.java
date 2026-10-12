package com.great.app.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Prepares the real tuning controls, localizes them, then installs the comfort-first settings UI. */
public final class ArabicCardTitlesApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (!(activity instanceof GreatMainActivity)) return;
                View root = activity.getWindow().getDecorView();
                if (!ComfortSettingsUi.isInstalled(root)) {
                    ReleaseTuningUi.prepare(activity, root);
                    FreezeEntryTuningUi.ensure(activity, root);
                    ConfigManagementUi.ensure(activity, root);
                    translateTree(root);
                    ComfortSettingsUi.ensure(activity, root);
                }
                root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (ComfortSettingsUi.isInstalled(root)) return;
                    ReleaseTuningUi.ensureCard(activity, root);
                    FreezeEntryTuningUi.ensure(activity, root);
                    ConfigManagementUi.ensure(activity, root);
                    translateTree(root);
                    ComfortSettingsUi.ensure(activity, root);
                });
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void translateTree(View view) {
        if (view instanceof TextView) translate((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) translateTree(group.getChildAt(i));
    }

    private static void translate(TextView view) {
        CharSequence value = view.getText();
        if (value == null) return;
        String text = value.toString();
        String translated = null;

        if ("VPN CONFIG".equals(text)) translated = "إعدادات الـ VPN";
        else if ("VPN".equals(text)) translated = "تشغيل وإيقاف الـ VPN";
        else if (text.startsWith("TARGET APPLICATIONS  ")) {
            translated = "التطبيقات المستهدفة  " + text.substring("TARGET APPLICATIONS  ".length());
        }
        else if ("PACKET RANGE".equals(text)) translated = "فلترة Payload";
        else if ("ROBOT LIGHT OUTBOUND THROTTLE".equals(text)) translated = "الخنق الخفيف بالتوازي مع التجميد";
        else if ("ROBOT WHITE-RETURN OUTBOUND THROTTLE".equals(text)) translated = "الخنق بعد عودة الأبيض";
        else if ("FREEZE HISTORY • LAST 5".equals(text)) translated = "سجل دورات التجميد • آخر 5";
        else if ("DETAILED FREEZE LOG • LAST 3   ▸".equals(text)) translated = "السجل التفصيلي للتجميد • آخر 3   ▸";
        else if ("DETAILED FREEZE LOG • LAST 3   ▾".equals(text)) translated = "السجل التفصيلي للتجميد • آخر 3   ▾";
        else if ("ROBOT MAX FREEZE".equals(text)) translated = "الحد الأقصى لمدة تجميد الروبوت";
        else if ("SHOW CIRCLES".equals(text)) translated = "إظهار الدوائر";
        else if ("HIDE CIRCLES".equals(text)) translated = "إخفاء الدوائر";
        else if ("المسموح: 0–100%   •   Min ≤ Max   •   الحالي الافتراضي 15–33%".equals(text)) {
            translated = "المسموح: 0–100%   •   Min ≤ Max   •   الافتراضي 15–35%";
        }
        else if ("المسموح: 1–100 حزمة   •   Min ≤ Max   •   الحالي الافتراضي 1–4".equals(text)) {
            translated = "المسموح: 1–100 حزمة   •   Min ≤ Max   •   الافتراضي 2–5";
        }
        else if ("المسموح: 0–5000 ms   •   Min ≤ Max   •   الحالي الافتراضي 30–100 ms".equals(text)) {
            translated = "المسموح: 0–5000 ms   •   Min ≤ Max   •   الافتراضي 25–75 ms";
        }
        else if (text.startsWith("الحقول الرقمية تظهر فارغة أول مرة.")) {
            translated = "تظهر القيم الافتراضية في أول تشغيل، وأي قيم تحفظها لاحقًا تصبح هي القيم المستخدمة.";
        }

        if (translated != null && !translated.equals(text)) view.setText(translated);
    }
}
