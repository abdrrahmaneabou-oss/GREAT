package com.great.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the long legacy control column into a dense, single-screen dashboard.
 * Every original card remains fully functional and opens as an on-demand detail sheet,
 * so this layer changes presentation only and does not duplicate business logic.
 */
final class GeniusDashboardUi {
    private static final String DASHBOARD_TAG = "great_genius_dashboard";
    private static final int BG = 0xff090b10;
    private static final int PANEL = 0xff12151d;
    private static final int PANEL_ALT = 0xff171b25;
    private static final int STROKE = 0xff292f40;
    private static final int TEXT = 0xfff5f7ff;
    private static final int MUTED = 0xff8f98aa;
    private static final int ACCENT = 0xffb89aff;
    private static final int GREEN = 0xff57d39b;

    private GeniusDashboardUi() { }

    static void ensure(Activity activity, View decorRoot) {
        if (!(decorRoot instanceof ViewGroup)) return;
        ViewGroup decor = (ViewGroup) decorRoot;
        if (findTagged(decor, DASHBOARD_TAG) != null) return;

        ScrollView sourceScroll = findPrimaryScrollView(decorRoot);
        if (sourceScroll == null || sourceScroll.getChildCount() == 0) return;
        View content = sourceScroll.getChildAt(0);
        if (!(content instanceof LinearLayout)) return;
        LinearLayout sourceColumn = (LinearLayout) content;

        List<View> cards = collectCards(sourceColumn);
        if (cards.size() < 4) return; // wait until the supplemental cards have been injected.

        sourceScroll.setVisibility(View.GONE);
        View dashboard = buildDashboard(activity, sourceColumn, cards);
        dashboard.setTag(DASHBOARD_TAG);

        ViewGroup host = (ViewGroup) sourceScroll.getParent();
        if (host == null) return;
        host.addView(dashboard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static View buildDashboard(Activity activity, LinearLayout sourceColumn, List<View> cards) {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = label(activity, "GREAT / CONTROL MATRIX", 9, ACCENT, true);
        TextView title = label(activity, "لوحة التحكم", 22, TEXT, true);
        brand.addView(eyebrow);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(activity, 1);
        brand.addView(title, titleParams);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView live = label(activity, "●  LIVE", 9, GREEN, true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        live.setBackground(round(activity, 0xff10231d, 999, 0xff285846));
        header.addView(live, new LinearLayout.LayoutParams(-2, -2));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = label(activity,
                "كل الأدوات أمامك في شاشة واحدة • اضغط أي وحدة لفتح تحكمها الكامل",
                9, MUTED, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(activity, 5);
        hintParams.bottomMargin = dp(activity, 10);
        page.addView(hint, hintParams);

        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int columns = landscape ? 5 : 3;
        int rows = (cards.size() + columns - 1) / columns;

        LinearLayout matrix = new LinearLayout(activity);
        matrix.setOrientation(LinearLayout.VERTICAL);
        page.addView(matrix, new LinearLayout.LayoutParams(-1, 0, 1f));

        int index = 0;
        for (int row = 0; row < rows; row++) {
            LinearLayout strip = new LinearLayout(activity);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (row > 0) stripParams.topMargin = dp(activity, 7);
            matrix.addView(strip, stripParams);

            for (int col = 0; col < columns; col++) {
                if (index < cards.size()) {
                    View original = cards.get(index++);
                    View tile = tile(activity, sourceColumn, original);
                    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -1, 1f);
                    if (col > 0) tileParams.leftMargin = dp(activity, 7);
                    strip.addView(tile, tileParams);
                } else {
                    View spacer = new View(activity);
                    LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, -1, 1f);
                    if (col > 0) spacerParams.leftMargin = dp(activity, 7);
                    strip.addView(spacer, spacerParams);
                }
            }
        }
        return page;
    }

    private static View tile(Activity activity, LinearLayout sourceColumn, View originalCard) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 9), dp(activity, 8));
        tile.setBackground(round(activity, PANEL, 14, STROKE));
        tile.setClickable(true);
        tile.setFocusable(true);

        String title = bestTitle(originalCard);
        String icon = iconFor(title);
        TextView iconView = label(activity, icon, 15, ACCENT, true);
        tile.addView(iconView, new LinearLayout.LayoutParams(-2, -2));

        TextView titleView = label(activity, compactTitle(title), 10, TEXT, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(activity, 3);
        tile.addView(titleView, titleParams);

        TextView summary = label(activity, compactSummary(originalCard), 8, MUTED, false);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.topMargin = dp(activity, 3);
        tile.addView(summary, summaryParams);

        TextView arrow = label(activity, "↗", 11, ACCENT, true);
        arrow.setGravity(Gravity.END);
        tile.addView(arrow, new LinearLayout.LayoutParams(-1, -2));

        tile.setOnClickListener(v -> showDetail(activity, sourceColumn, originalCard, title));
        return tile;
    }

    private static void showDetail(Activity activity, LinearLayout sourceColumn,
                                   View card, String title) {
        ViewGroup parent = (ViewGroup) card.getParent();
        if (parent == null) return;
        int originalIndex = parent.indexOfChild(card);
        ViewGroup.LayoutParams originalParams = card.getLayoutParams();
        parent.removeView(card);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        shell.setBackground(round(activity, PANEL_ALT, 22, 0xff33394b));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label(activity, compactTitle(title), 13, TEXT, true);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(activity, "×", 22, TEXT, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 4));
        close.setBackground(round(activity, 0xff222735, 999, 0xff32394b));
        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(activity, 8);
        shell.addView(scroll, scrollParams);
        scroll.addView(card, new ScrollView.LayoutParams(-1, -2));

        dialog.setContentView(shell);
        dialog.setOnDismissListener(d -> {
            ViewGroup detailParent = (ViewGroup) card.getParent();
            if (detailParent != null) detailParent.removeView(card);
            int safeIndex = Math.max(0, Math.min(originalIndex, parent.getChildCount()));
            parent.addView(card, safeIndex, originalParams);
        });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams p = new WindowManager.LayoutParams();
            p.copyFrom(window.getAttributes());
            p.width = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.94f);
            p.height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.86f);
            p.gravity = Gravity.CENTER;
            window.setAttributes(p);
            if (Build.VERSION.SDK_INT >= 31) window.setDimAmount(0.55f);
        }
    }

    private static List<View> collectCards(LinearLayout sourceColumn) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < sourceColumn.getChildCount(); i++) {
            View child = sourceColumn.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            if (containsInteractive(child) || containsCardLikeHeading(child)) out.add(child);
        }
        return out;
    }

    private static boolean containsInteractive(View view) {
        if (view instanceof EditText || view instanceof Switch) return true;
        if (view instanceof TextView && view.isClickable()) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsInteractive(group.getChildAt(i))) return true;
        }
        return false;
    }

    private static boolean containsCardLikeHeading(View view) {
        if (!(view instanceof ViewGroup)) return false;
        String title = bestTitle(view);
        return !title.isEmpty() && !"GREAT".equals(title) && !"Control".equals(title);
    }

    private static String bestTitle(View card) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(card, texts);
        for (TextView t : texts) {
            CharSequence value = t.getText();
            if (value == null) continue;
            String s = value.toString().trim();
            if (s.isEmpty()) continue;
            if (s.length() <= 64) return s;
        }
        return "إعداد";
    }

    private static String compactSummary(View card) {
        List<Switch> switches = new ArrayList<>();
        List<EditText> edits = new ArrayList<>();
        collectSwitches(card, switches);
        collectEdits(card, edits);

        if (!switches.isEmpty()) {
            int on = 0;
            for (Switch s : switches) if (s.isChecked()) on++;
            return on + "/" + switches.size() + " مفعّل";
        }
        if (!edits.isEmpty()) {
            StringBuilder b = new StringBuilder();
            int count = Math.min(2, edits.size());
            for (int i = 0; i < count; i++) {
                String v = edits.get(i).getText().toString().trim();
                if (v.isEmpty()) v = "—";
                if (i > 0) b.append("  •  ");
                b.append(v);
            }
            return b.toString();
        }
        List<TextView> texts = new ArrayList<>();
        collectTexts(card, texts);
        if (texts.size() > 1) {
            String value = texts.get(1).getText() == null ? "" : texts.get(1).getText().toString().trim();
            if (!value.isEmpty()) return value;
        }
        return "اضغط للتفاصيل";
    }

    private static String compactTitle(String title) {
        String t = title;
        t = t.replace("ROBOT LIGHT OUTBOUND THROTTLE", "Robot Light Throttle");
        t = t.replace("ROBOT WHITE-RETURN OUTBOUND THROTTLE", "Robot Post Throttle");
        t = t.replace("FREEZE HISTORY • LAST 5", "Freeze History");
        t = t.replace("DETAILED FREEZE LOG • LAST 3   ▸", "Freeze Log");
        t = t.replace("DETAILED FREEZE LOG • LAST 3   ▾", "Freeze Log");
        return t;
    }

    private static String iconFor(String title) {
        String s = title.toLowerCase();
        if (s.contains("vpn") || s.contains("config") || s.contains("إعدادات")) return "◈";
        if (s.contains("payload") || s.contains("packet") || s.contains("فلترة")) return "⌁";
        if (s.contains("robot") || s.contains("روبوت")) return "◎";
        if (s.contains("freeze") || s.contains("تجميد")) return "✣";
        if (s.contains("history") || s.contains("سجل")) return "▤";
        if (s.contains("target") || s.contains("تطبيق")) return "⌾";
        if (s.contains("circles") || s.contains("دوائر")) return "◉";
        if (s.contains("release") || s.contains("إفراج")) return "⇢";
        return "◇";
    }

    private static void collectTexts(View view, List<TextView> out) {
        if (view instanceof TextView && !(view instanceof EditText)) out.add((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out);
    }

    private static void collectSwitches(View view, List<Switch> out) {
        if (view instanceof Switch) out.add((Switch) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectSwitches(group.getChildAt(i), out);
    }

    private static void collectEdits(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectEdits(group.getChildAt(i), out);
    }

    private static ScrollView findPrimaryScrollView(View view) {
        if (view instanceof ScrollView && view.getVisibility() == View.VISIBLE) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findPrimaryScrollView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static View findTagged(ViewGroup group, String tag) {
        if (tag.equals(group.getTag())) return group;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof ViewGroup) {
                View nested = findTagged((ViewGroup) child, tag);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static TextView label(Activity activity, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static GradientDrawable round(Activity activity, int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(activity, radiusDp));
        if (strokeColor != 0) d.setStroke(dp(activity, 1), strokeColor);
        return d;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
