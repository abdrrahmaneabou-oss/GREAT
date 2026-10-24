package com.great.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.great.app.config.CapabilitySettingsStore;
import com.great.app.config.ReleaseSettingsStore;
import com.great.app.core.FreezeCore;
import com.great.app.core.GreatEngine;
import com.great.app.transport.GlobalRobotOutboundThrottle;

import java.util.WeakHashMap;

/** Adds the gradual-release controls and keeps first-use numeric fields visually empty. */
final class ReleaseTuningUi {
    private static final int SURFACE = 0xff151822;
    private static final int TEXT = 0xfff2f3fa;
    private static final int MUTED = 0xffa2aabc;
    private static final int ACCENT = 0xffb89aff;
    private static final String CARD_TAG = "great_gradual_release_card";
    private static final String CAPABILITY_PREFS = "great_capability_settings";
    private static final WeakHashMap<Activity, Boolean> PREPARED = new WeakHashMap<>();

    private ReleaseTuningUi() { }

    static void prepare(Activity activity, View decorRoot) {
        applyReleaseSettings(activity);
        configureLightThrottleSave(activity, decorRoot);
        if (Boolean.TRUE.equals(PREPARED.get(activity))) {
            ensureCard(activity, decorRoot);
            return;
        }
        PREPARED.put(activity, true);
        blankFirstUseNumericFields(activity, decorRoot);
        ensureCard(activity, decorRoot);
    }

    static void ensureCard(Activity activity, View decorRoot) {
        ScrollView scroll = findScrollView(decorRoot);
        if (scroll == null || scroll.getChildCount() == 0) return;
        View content = scroll.getChildAt(0);
        if (!(content instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) content;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (CARD_TAG.equals(root.getChildAt(i).getTag())) return;
        }
        View card = buildCard(activity);
        card.setTag(CARD_TAG);
        int insertAt = root.getChildCount();
        for (int i = 0; i < root.getChildCount(); i++) {
            if (containsText(root.getChildAt(i), "FREEZE HISTORY • LAST 5")
                    || containsText(root.getChildAt(i), "سجل دورات التجميد • آخر 5")) {
                insertAt = i;
                break;
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 14);
        root.addView(card, insertAt, params);
    }

    private static void applyReleaseSettings(Context context) {
        ReleaseSettingsStore.Snapshot store = new ReleaseSettingsStore(context).snapshot();
        FreezeCore core = GreatEngine.instance().freezeCore();
        core.setReleaseRanges(store.dropMin(), store.dropMax(), store.burstMin(),
                store.burstMax(), store.delayMinMs(), store.delayMaxMs());
        core.setReleaseDropEnabled(store.dropEnabled());
        core.setReleaseBurstEnabled(store.burstEnabled());
        core.setReleaseDelayEnabled(store.delayEnabled());
    }

    private static void configureLightThrottleSave(Activity activity, View root) {
        View card = findDirectCard(root, "ROBOT LIGHT OUTBOUND THROTTLE");
        if (card == null) card = findDirectCard(root, "الخنق الخفيف بالتوازي مع التجميد");
        if (card == null) return;

        EditText startMinInput = findEditTextByHint(card, "Min Start Delay");
        EditText startMaxInput = findEditTextByHint(card, "Max Start Delay");
        EditText durationMinInput = findEditTextByHint(card, "Min Duration");
        EditText durationMaxInput = findEditTextByHint(card, "Max Duration");
        Switch whiteReturn = findSwitchByLabel(card, "STOP ON WHITE RETURN");
        TextView save = findTextView(card, "SAVE LIGHT THROTTLE");
        if (startMinInput == null || startMaxInput == null || durationMinInput == null
                || durationMaxInput == null || whiteReturn == null || save == null) return;

        save.setOnClickListener(v -> saveLightThrottle(activity, startMinInput, startMaxInput,
                durationMinInput, durationMaxInput, whiteReturn.isChecked()));
    }

    private static void saveLightThrottle(Activity activity,
                                          EditText startMinInput, EditText startMaxInput,
                                          EditText durationMinInput, EditText durationMaxInput,
                                          boolean stopOnWhiteReturn) {
        Integer startMin = parseRequired(startMinInput, "Minimum start delay is required");
        Integer startMax = parseRequired(startMaxInput, "Maximum start delay is required");
        if (startMin == null || startMax == null) return;

        int low = GlobalRobotOutboundThrottle.MIN_CONFIGURABLE_LIGHT_MS;
        int high = GlobalRobotOutboundThrottle.MAX_CONFIGURABLE_LIGHT_MS;
        if (!validateRange(startMinInput, startMaxInput, startMin, startMax, low, high, "ms")) return;

        String durationMinText = durationMinInput.getText().toString().trim();
        String durationMaxText = durationMaxInput.getText().toString().trim();
        boolean bothDurationEmpty = durationMinText.isEmpty() && durationMaxText.isEmpty();

        CapabilitySettingsStore settings = new CapabilitySettingsStore(activity);
        GlobalRobotOutboundThrottle throttle = GlobalRobotOutboundThrottle.instance();

        if (stopOnWhiteReturn && bothDurationEmpty) {
            activity.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("robot_light_start_min_ms", startMin)
                    .putInt("robot_light_start_max_ms", startMax)
                    .apply();
            throttle.setLightRange(startMin, startMax,
                    settings.robotLightDurationMinMs(), settings.robotLightDurationMaxMs());
            Toast.makeText(activity,
                    "Light throttle saved; Duration is ignored until white-return link is disabled",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Integer durationMin = parseRequired(durationMinInput, "Minimum duration is required");
        Integer durationMax = parseRequired(durationMaxInput, "Maximum duration is required");
        if (durationMin == null || durationMax == null) return;
        if (!validateRange(durationMinInput, durationMaxInput,
                durationMin, durationMax, low, high, "ms")) return;

        settings.setRobotLightThrottleRange(startMin, startMax, durationMin, durationMax);
        throttle.setLightRange(startMin, startMax, durationMin, durationMax);
        Toast.makeText(activity, "Robot light throttle ranges saved", Toast.LENGTH_LONG).show();
    }

    private static boolean validateRange(EditText minInput, EditText maxInput,
                                         int min, int max, int low, int high, String unit) {
        if (min < low || min > high) {
            minInput.setError("Allowed: " + low + ".." + high + " " + unit);
            return false;
        }
        if (max < low || max > high) {
            maxInput.setError("Allowed: " + low + ".." + high + " " + unit);
            return false;
        }
        if (min > max) {
            minInput.setError("Minimum must be ≤ maximum");
            return false;
        }
        return true;
    }

    private static Integer parseRequired(EditText input, String message) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            input.setError(message);
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            input.setError("Enter a valid number");
            return null;
        }
    }

    private static View buildCard(Activity activity) {
        ReleaseSettingsStore store = new ReleaseSettingsStore(activity);
        FreezeCore core = GreatEngine.instance().freezeCore();
        LinearLayout card = column(activity);
        card.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18));
        card.setBackground(round(activity, SURFACE, 20));

        add(activity, card, text(activity, "التدرج في الإفراج عن الحزم", 11, ACCENT, true), 0);
        add(activity, card, text(activity,
                "يتحكم في مرحلة الإفراج عن الحزم المحتجزة بعد Freeze: حذف اختياري، تقسيم إلى دفعات، وانتظار اختياري بين الدفعات. الخلط يبقى فعالًا.",
                12, MUTED, false), 8);

        Switch dropToggle = switchRow(activity, card, "حذف حزم قبل الإفراج", store.dropEnabled(), 14);
        final EditText dropMin = numericInput(activity, "Min Drop %",
                store.hasDropRange() ? store.dropMinPercent() : null);
        final EditText dropMax = numericInput(activity, "Max Drop %",
                store.hasDropRange() ? store.dropMaxPercent() : null);
        add(activity, card, inputPair(activity, dropMin, dropMax), 8);
        add(activity, card, text(activity, "المسموح: 0–100%   •   Min ≤ Max   •   الحالي الافتراضي 15–33%", 11, MUTED, false), 6);
        dropToggle.setOnCheckedChangeListener((buttonView, enabled) -> {
            store.setDropEnabled(enabled);
            core.setReleaseDropEnabled(enabled);
        });

        Switch burstToggle = switchRow(activity, card, "تقسيم الإفراج إلى دفعات", store.burstEnabled(), 16);
        final EditText burstMin = numericInput(activity, "Min Burst",
                store.hasBurstRange() ? store.burstMin() : null);
        final EditText burstMax = numericInput(activity, "Max Burst",
                store.hasBurstRange() ? store.burstMax() : null);
        add(activity, card, inputPair(activity, burstMin, burstMax), 8);
        add(activity, card, text(activity, "المسموح: 1–100 حزمة   •   Min ≤ Max   •   الحالي الافتراضي 1–4", 11, MUTED, false), 6);
        burstToggle.setOnCheckedChangeListener((buttonView, enabled) -> {
            store.setBurstEnabled(enabled);
            core.setReleaseBurstEnabled(enabled);
        });

        Switch delayToggle = switchRow(activity, card, "انتظار بين الدفعات", store.delayEnabled(), 16);
        final EditText delayMin = numericInput(activity, "Min Delay ms",
                store.hasDelayRange() ? store.delayMinMs() : null);
        final EditText delayMax = numericInput(activity, "Max Delay ms",
                store.hasDelayRange() ? store.delayMaxMs() : null);
        add(activity, card, inputPair(activity, delayMin, delayMax), 8);
        add(activity, card, text(activity, "المسموح: 0–5000 ms   •   Min ≤ Max   •   الحالي الافتراضي 30–100 ms", 11, MUTED, false), 6);

        delayToggle.setOnCheckedChangeListener((buttonView, enabled) -> {
            store.setDelayEnabled(enabled);
            core.setReleaseDelayEnabled(enabled);
        });

        add(activity, card, text(activity,
                "الحقول الرقمية تظهر فارغة أول مرة. بقاء الحقل فارغًا لا يغيّر القيمة الداخلية الحالية؛ يتم اعتماد القيم التي تكتبها فقط بعد الحفظ.",
                11, MUTED, false), 12);

        TextView save = button(activity, "SAVE RELEASE SETTINGS");
        save.setOnClickListener(v -> {
            // Parse into local values first. No settings can change after a partial validation.
            final int[] drop = {store.dropMinPercent(), store.dropMaxPercent()};
            final int[] burst = {store.burstMin(), store.burstMax()};
            final int[] delay = {store.delayMinMs(), store.delayMaxMs()};
            if (!saveOptionalRange(dropMin, dropMax,
                    FreezeCore.MIN_RELEASE_DROP_LIMIT_PERCENT,
                    FreezeCore.MAX_RELEASE_DROP_LIMIT_PERCENT, "%",
                    (min, max) -> { drop[0] = min; drop[1] = max; })) return;
            if (!saveOptionalRange(burstMin, burstMax,
                    FreezeCore.MIN_RELEASE_BURST_LIMIT,
                    FreezeCore.MAX_RELEASE_BURST_LIMIT, "packets",
                    (min, max) -> { burst[0] = min; burst[1] = max; })) return;
            if (!saveOptionalRange(delayMin, delayMax,
                    FreezeCore.MIN_RELEASE_DELAY_LIMIT_MS,
                    FreezeCore.MAX_RELEASE_DELAY_LIMIT_MS, "ms",
                    (min, max) -> { delay[0] = min; delay[1] = max; })) return;
            store.setRanges(drop[0], drop[1], burst[0], burst[1], delay[0], delay[1]);
            core.setReleaseRanges(drop[0], drop[1], burst[0], burst[1], delay[0], delay[1]);
            Toast.makeText(activity, "Gradual release settings saved", Toast.LENGTH_LONG).show();
        });
        add(activity, card, save, 14);
        return card;
    }

    private interface RangeSaver { void save(int min, int max); }

    private static boolean saveOptionalRange(EditText minInput, EditText maxInput,
                                             int low, int high, String unit, RangeSaver saver) {
        String minText = minInput.getText().toString().trim();
        String maxText = maxInput.getText().toString().trim();
        if (minText.isEmpty() && maxText.isEmpty()) return true;
        if (minText.isEmpty()) { minInput.setError("Minimum is required"); return false; }
        if (maxText.isEmpty()) { maxInput.setError("Maximum is required"); return false; }
        int min;
        int max;
        try {
            min = Integer.parseInt(minText);
            max = Integer.parseInt(maxText);
        } catch (NumberFormatException e) {
            minInput.setError("Enter valid numbers");
            return false;
        }
        if (min < low || min > high) {
            minInput.setError("Allowed: " + low + ".." + high + " " + unit);
            return false;
        }
        if (max < low || max > high) {
            maxInput.setError("Allowed: " + low + ".." + high + " " + unit);
            return false;
        }
        if (min > max) {
            minInput.setError("Minimum must be ≤ maximum");
            return false;
        }
        saver.save(min, max);
        return true;
    }

    private static void blankFirstUseNumericFields(Activity activity, View root) {
        SharedPreferences prefs = activity.getSharedPreferences(CAPABILITY_PREFS, Context.MODE_PRIVATE);
        boolean fixedPayloadSaved = prefs.contains("freeze_payload_min") && prefs.contains("freeze_payload_max");
        boolean randomPayloadSaved = prefs.contains("freeze_payload_min_from")
                && prefs.contains("freeze_payload_min_to")
                && prefs.contains("freeze_payload_max_from")
                && prefs.contains("freeze_payload_max_to");
        boolean lightStartSaved = prefs.contains("robot_light_start_min_ms")
                && prefs.contains("robot_light_start_max_ms");
        boolean lightDurationSaved = prefs.contains("robot_light_duration_min_ms")
                && prefs.contains("robot_light_duration_max_ms");
        boolean postSaved = prefs.contains("robot_white_return_start_min_ms")
                && prefs.contains("robot_white_return_start_max_ms")
                && prefs.contains("robot_white_return_duration_min_ms")
                && prefs.contains("robot_white_return_duration_max_ms");
        blankRecursive(root, fixedPayloadSaved, randomPayloadSaved,
                lightStartSaved, lightDurationSaved, postSaved);
    }

    private static void blankRecursive(View view, boolean fixedPayloadSaved, boolean randomPayloadSaved,
                                       boolean lightStartSaved, boolean lightDurationSaved,
                                       boolean postSaved) {
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            CharSequence hintValue = input.getHint();
            String hint = hintValue == null ? "" : hintValue.toString();
            if (!fixedPayloadSaved && ("Minimum".equals(hint) || "Maximum".equals(hint))) {
                input.setText("");
            } else if (!randomPayloadSaved && ("Min From".equals(hint) || "Min To".equals(hint)
                    || "Max From".equals(hint) || "Max To".equals(hint))) {
                input.setText("");
            } else if (!lightStartSaved && ("Min Start Delay".equals(hint) || "Max Start Delay".equals(hint))) {
                input.setText("");
            } else if (!lightDurationSaved && ("Min Duration".equals(hint) || "Max Duration".equals(hint))
                    && insideCard(input, "ROBOT LIGHT OUTBOUND THROTTLE")) {
                input.setText("");
            } else if (!postSaved && ("Min Start".equals(hint) || "Max Start".equals(hint)
                    || (("Min Duration".equals(hint) || "Max Duration".equals(hint))
                    && insideCard(input, "ROBOT WHITE-RETURN OUTBOUND THROTTLE")))) {
                input.setText("");
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            blankRecursive(group.getChildAt(i), fixedPayloadSaved, randomPayloadSaved,
                    lightStartSaved, lightDurationSaved, postSaved);
        }
    }

    /** Only search sibling cards, never their shared ScrollView ancestor. */
    private static View findDirectCard(View root, String expected) {
        ScrollView scroll = findScrollView(root);
        if (scroll == null || scroll.getChildCount() == 0
                || !(scroll.getChildAt(0) instanceof ViewGroup)) return null;
        ViewGroup cards = (ViewGroup) scroll.getChildAt(0);
        for (int i = 0; i < cards.getChildCount(); i++) {
            View child = cards.getChildAt(i);
            if (containsText(child, expected)) return child;
        }
        return null;
    }

    private static View findContainingView(View view, String expected) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (containsText(child, expected)) return child;
            View nested = findContainingView(child, expected);
            if (nested != null) return nested;
        }
        return null;
    }

    private static EditText findEditTextByHint(View view, String expectedHint) {
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            if (hint != null && expectedHint.equals(hint.toString())) return (EditText) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = findEditTextByHint(group.getChildAt(i), expectedHint);
            if (found != null) return found;
        }
        return null;
    }

    private static TextView findTextView(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && expected.equals(text.toString())) return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findTextView(group.getChildAt(i), expected);
            if (found != null) return found;
        }
        return null;
    }

    private static Switch findSwitchByLabel(View view, String label) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup && containsText(child, label)) {
                Switch found = findFirstSwitch(child);
                if (found != null) return found;
            }
            Switch nested = findSwitchByLabel(child, label);
            if (nested != null) return nested;
        }
        return null;
    }

    private static Switch findFirstSwitch(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            Switch found = findFirstSwitch(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean insideCard(View view, String title) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            if (containsText(parentView, title)) return true;
            parent = parentView.getParent();
        }
        return false;
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && expected.equals(text.toString())) return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsText(group.getChildAt(i), expected)) return true;
        }
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

    private static Switch switchRow(Activity activity, LinearLayout card, String label,
                                    boolean checked, int topDp) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(activity, label, 13, TEXT, true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(activity);
        toggle.setChecked(checked);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        add(activity, card, row, topDp);
        return toggle;
    }

    private static LinearLayout inputPair(Activity activity, EditText first, EditText second) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(activity, 54), 1f));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(activity, 54), 1f);
        secondParams.leftMargin = dp(activity, 10);
        row.addView(second, secondParams);
        return row;
    }

    private static EditText numericInput(Activity activity, String hint, Integer value) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        if (value != null) input.setText(String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        input.setBackground(round(activity, 0xff202431, 14));
        return input;
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static TextView text(Activity activity, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private static TextView button(Activity activity, String label) {
        TextView view = text(activity, label, 16, Color.BLACK, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 58));
        view.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        view.setBackground(round(activity, ACCENT, 18));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static GradientDrawable round(Activity activity, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(activity, radiusDp));
        drawable.setStroke(dp(activity, 1), 0xff292e3d);
        return drawable;
    }

    private static void add(Activity activity, LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, topDp);
        parent.addView(child, params);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
