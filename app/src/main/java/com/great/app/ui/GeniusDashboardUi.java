package com.great.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * High-density single-screen shell for GREAT.
 *
 * The legacy cards remain the single source of truth. This class only projects them into a
 * cinematic control deck, preserving every listener, input and save action. A module opens its
 * original card in a detail sheet and restores it when the sheet closes.
 */
final class GeniusDashboardUi {
    private static final String DASHBOARD_TAG = "great_genius_dashboard";

    private static final int BG = 0xff06080d;
    private static final int BG_2 = 0xff0a0d14;
    private static final int GLASS = 0xe6121620;
    private static final int GLASS_2 = 0xf0181d29;
    private static final int STROKE = 0xff283142;
    private static final int STROKE_HOT = 0xff46536d;
    private static final int TEXT = 0xfff7f9ff;
    private static final int MUTED = 0xff8d98aa;
    private static final int DIM = 0xff616c7d;
    private static final int VIOLET = 0xffb993ff;
    private static final int CYAN = 0xff67d8ff;
    private static final int GREEN = 0xff5ce0a5;
    private static final int AMBER = 0xffffc86b;

    private GeniusDashboardUi() { }

    private enum Zone {
        CORE("CORE", "البنية", "◆", CYAN),
        FREEZE("FREEZE", "التجميد", "✣", VIOLET),
        ROBOT("ROBOT", "الروبوت", "◎", GREEN),
        TRACE("TRACE", "السجل والتدفق", "⌁", AMBER);

        final String code;
        final String arabic;
        final String glyph;
        final int accent;

        Zone(String code, String arabic, String glyph, int accent) {
            this.code = code;
            this.arabic = arabic;
            this.glyph = glyph;
            this.accent = accent;
        }
    }

    private static final class Module {
        final View card;
        final String title;
        final Zone zone;

        Module(View card, String title, Zone zone) {
            this.card = card;
            this.title = title;
            this.zone = zone;
        }
    }

    private static final class TileBinding {
        final Module module;
        final TextView value;

        TileBinding(Module module, TextView value) {
            this.module = module;
            this.value = value;
        }
    }

    static void ensure(Activity activity, View decorRoot) {
        if (!(decorRoot instanceof ViewGroup)) return;
        ViewGroup decor = (ViewGroup) decorRoot;
        if (findTagged(decor, DASHBOARD_TAG) != null) return;

        ScrollView sourceScroll = findPrimaryScrollView(decorRoot);
        if (sourceScroll == null || sourceScroll.getChildCount() == 0) return;
        View child = sourceScroll.getChildAt(0);
        if (!(child instanceof LinearLayout)) return;
        LinearLayout sourceColumn = (LinearLayout) child;

        List<View> cards = collectCards(sourceColumn);
        if (cards.size() < 8) return; // wait until supplemental cards are injected.

        List<Module> modules = new ArrayList<>();
        for (View card : cards) {
            String title = bestTitle(card);
            modules.add(new Module(card, title, classify(title)));
        }

        View dashboard = buildDashboard(activity, sourceColumn, modules);
        dashboard.setTag(DASHBOARD_TAG);

        ViewGroup host = (ViewGroup) sourceScroll.getParent();
        if (host == null) return;
        sourceScroll.setVisibility(View.GONE);
        host.addView(dashboard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static View buildDashboard(Activity activity, LinearLayout sourceColumn,
                                       List<Module> modules) {
        final List<TileBinding> bindings = new ArrayList<>();
        final boolean landscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        FrameLayout scene = new FrameLayout(activity);
        scene.setBackgroundColor(BG);
        scene.addView(new BlueprintView(activity), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout deck = new LinearLayout(activity);
        deck.setOrientation(LinearLayout.VERTICAL);
        int side = landscape ? 18 : 12;
        deck.setPadding(dp(activity, side), dp(activity, landscape ? 10 : 12),
                dp(activity, side), dp(activity, landscape ? 10 : 12));
        scene.addView(deck, new FrameLayout.LayoutParams(-1, -1));

        deck.addView(commandHeader(activity, modules.size(), landscape),
                new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(activity);
        divider.setBackgroundColor(0x55364250);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(activity, 1));
        dividerParams.topMargin = dp(activity, 8);
        dividerParams.bottomMargin = dp(activity, 8);
        deck.addView(divider, dividerParams);

        EnumMap<Zone, List<Module>> groups = new EnumMap<>(Zone.class);
        for (Zone zone : Zone.values()) groups.put(zone, new ArrayList<>());
        for (Module module : modules) groups.get(module.zone).add(module);

        LinearLayout matrix = new LinearLayout(activity);
        matrix.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        deck.addView(matrix, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (landscape) {
            for (Zone zone : Zone.values()) {
                View pod = pod(activity, sourceColumn, zone, groups.get(zone), bindings, true);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
                if (zone != Zone.CORE) p.leftMargin = dp(activity, 7);
                matrix.addView(pod, p);
            }
        } else {
            Zone[] zones = Zone.values();
            for (int r = 0; r < 2; r++) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(-1, 0, 1f);
                if (r > 0) rowP.topMargin = dp(activity, 7);
                matrix.addView(row, rowP);

                for (int c = 0; c < 2; c++) {
                    Zone zone = zones[r * 2 + c];
                    View pod = pod(activity, sourceColumn, zone, groups.get(zone), bindings, false);
                    LinearLayout.LayoutParams podP = new LinearLayout.LayoutParams(0, -1, 1f);
                    if (c > 0) podP.leftMargin = dp(activity, 7);
                    row.addView(pod, podP);
                }
            }
        }

        LinearLayout rail = new LinearLayout(activity);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setPadding(dp(activity, 2), dp(activity, 7), dp(activity, 2), 0);
        TextView left = mono(activity, "GREAT // SYSTEM DECK 02", 8, DIM, true);
        rail.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView right = mono(activity, "TAP MODULE  ↗", 8, MUTED, true);
        rail.addView(right, new LinearLayout.LayoutParams(-2, -2));
        deck.addView(rail, new LinearLayout.LayoutParams(-1, -2));

        deck.setAlpha(0f);
        deck.setTranslationY(dp(activity, 8));
        deck.animate().alpha(1f).translationY(0f).setDuration(320).start();

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            for (TileBinding binding : bindings) {
                binding.value.setText(compactSummary(binding.module.card));
            }
            if (scene.getWindowToken() != null) scene.postDelayed(refresh[0], 850L);
        };
        scene.postDelayed(refresh[0], 850L);
        return scene;
    }

    private static View commandHeader(Activity activity, int moduleCount, boolean landscape) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        OrbitMark orb = new OrbitMark(activity);
        int orbSize = dp(activity, landscape ? 42 : 46);
        row.addView(orb, new LinearLayout.LayoutParams(orbSize, orbSize));

        LinearLayout identity = new LinearLayout(activity);
        identity.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams idP = new LinearLayout.LayoutParams(0, -2, 1f);
        idP.leftMargin = dp(activity, 10);
        row.addView(identity, idP);

        TextView eyebrow = mono(activity, "GREAT / COMMAND SURFACE", landscape ? 8 : 9, VIOLET, true);
        identity.addView(eyebrow);
        TextView title = text(activity, "مصفوفة التحكم", landscape ? 18 : 20, TEXT, true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-2, -2);
        titleP.topMargin = dp(activity, 1);
        identity.addView(title, titleP);

        LinearLayout telemetry = new LinearLayout(activity);
        telemetry.setOrientation(LinearLayout.VERTICAL);
        telemetry.setGravity(Gravity.END);
        TextView live = mono(activity, "●  LIVE", 9, GREEN, true);
        live.setGravity(Gravity.END);
        telemetry.addView(live, new LinearLayout.LayoutParams(-1, -2));
        TextView modules = mono(activity, String.format(Locale.US, "%02d MODULES / 01 SURFACE", moduleCount),
                8, MUTED, true);
        LinearLayout.LayoutParams modP = new LinearLayout.LayoutParams(-2, -2);
        modP.topMargin = dp(activity, 3);
        telemetry.addView(modules, modP);
        row.addView(telemetry, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static View pod(Activity activity, LinearLayout sourceColumn, Zone zone,
                            List<Module> modules, List<TileBinding> bindings, boolean landscape) {
        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(activity, 8), dp(activity, 7), dp(activity, 8), dp(activity, 7));
        shell.setBackground(glassPanel(activity, zone.accent));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = mono(activity, zone.glyph, 13, zone.accent, true);
        head.addView(glyph, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout naming = new LinearLayout(activity);
        naming.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams namingP = new LinearLayout.LayoutParams(0, -2, 1f);
        namingP.leftMargin = dp(activity, 6);
        head.addView(naming, namingP);
        naming.addView(mono(activity, zone.code, 8, zone.accent, true));
        naming.addView(text(activity, zone.arabic, 9, TEXT, true));

        TextView count = mono(activity, String.format(Locale.US, "%02d", modules.size()), 8, MUTED, true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(activity, 6), dp(activity, 3), dp(activity, 6), dp(activity, 3));
        count.setBackground(round(activity, 0x771d2431, 999, 0x664b5870));
        head.addView(count, new LinearLayout.LayoutParams(-2, -2));
        shell.addView(head, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(activity);
        line.setBackgroundColor(withAlpha(zone.accent, 72));
        LinearLayout.LayoutParams lineP = new LinearLayout.LayoutParams(-1, dp(activity, 1));
        lineP.topMargin = dp(activity, 5);
        lineP.bottomMargin = dp(activity, 5);
        shell.addView(line, lineP);

        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        shell.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (modules.isEmpty()) {
            TextView empty = mono(activity, "NO MODULES", 8, DIM, true);
            empty.setGravity(Gravity.CENTER);
            grid.addView(empty, new LinearLayout.LayoutParams(-1, -1));
            return shell;
        }

        int columns = landscape ? 1 : 2;
        int rows = (modules.size() + columns - 1) / columns;
        int index = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) rowP.topMargin = dp(activity, 4);
            grid.addView(row, rowP);

            for (int c = 0; c < columns; c++) {
                if (index < modules.size()) {
                    Module module = modules.get(index++);
                    View tile = moduleTile(activity, sourceColumn, module, bindings, landscape);
                    LinearLayout.LayoutParams tileP = new LinearLayout.LayoutParams(0, -1, 1f);
                    if (c > 0) tileP.leftMargin = dp(activity, 4);
                    row.addView(tile, tileP);
                } else {
                    View spacer = new View(activity);
                    LinearLayout.LayoutParams spacerP = new LinearLayout.LayoutParams(0, -1, 1f);
                    if (c > 0) spacerP.leftMargin = dp(activity, 4);
                    row.addView(spacer, spacerP);
                }
            }
        }
        return shell;
    }

    private static View moduleTile(Activity activity, LinearLayout sourceColumn,
                                   Module module, List<TileBinding> bindings, boolean landscape) {
        FrameLayout tile = new FrameLayout(activity);
        tile.setBackground(moduleBackground(activity, module.zone.accent));
        tile.setClickable(true);
        tile.setFocusable(true);

        View accent = new View(activity);
        accent.setBackgroundColor(withAlpha(module.zone.accent, 210));
        FrameLayout.LayoutParams accentP = new FrameLayout.LayoutParams(dp(activity, 2), -1);
        accentP.gravity = Gravity.START;
        tile.addView(accent, accentP);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(activity, landscape ? 8 : 7), dp(activity, 4),
                dp(activity, 5), dp(activity, 4));
        tile.addView(content, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(activity, displayTitle(module.title), landscape ? 8 : 8, TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView value = mono(activity, compactSummary(module.card), landscape ? 8 : 8,
                module.zone.accent, true);
        value.setMaxLines(1);
        value.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams valueP = new LinearLayout.LayoutParams(-1, -2);
        valueP.topMargin = dp(activity, 2);
        content.addView(value, valueP);
        bindings.add(new TileBinding(module, value));

        TextView arrow = mono(activity, "↗", 8, withAlpha(module.zone.accent, 185), true);
        FrameLayout.LayoutParams arrowP = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        arrowP.topMargin = dp(activity, 2);
        arrowP.rightMargin = dp(activity, 4);
        tile.addView(arrow, arrowP);

        tile.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(55).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).withEndAction(() ->
                            showDetail(activity, sourceColumn, module)).start()).start();
        });
        return tile;
    }

    private static void showDetail(Activity activity, LinearLayout sourceColumn, Module module) {
        View card = module.card;
        if (!(card.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) card.getParent();
        int originalIndex = parent.indexOfChild(card);
        ViewGroup.LayoutParams originalParams = card.getLayoutParams();
        parent.removeView(card);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout stage = new FrameLayout(activity);
        stage.setPadding(dp(activity, 7), dp(activity, 7), dp(activity, 7), dp(activity, 7));
        stage.setBackground(new ColorDrawable(Color.TRANSPARENT));

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(activity, 12), dp(activity, 11), dp(activity, 12), dp(activity, 11));
        shell.setBackground(detailBackground(activity, module.zone.accent));
        stage.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = mono(activity, module.zone.glyph, 16, module.zone.accent, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(activity, withAlpha(module.zone.accent, 20), 10,
                withAlpha(module.zone.accent, 95)));
        mark.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6));
        bar.addView(mark, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams namesP = new LinearLayout.LayoutParams(0, -2, 1f);
        namesP.leftMargin = dp(activity, 9);
        bar.addView(names, namesP);
        names.addView(mono(activity, module.zone.code + " / MODULE", 8, module.zone.accent, true));
        TextView heading = text(activity, displayTitle(module.title), 14, TEXT, true);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(heading);

        TextView close = mono(activity, "×", 20, TEXT, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(activity, 11), dp(activity, 5), dp(activity, 11), dp(activity, 5));
        close.setBackground(round(activity, 0xaa222a38, 999, STROKE_HOT));
        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        View light = new View(activity);
        light.setBackgroundColor(withAlpha(module.zone.accent, 92));
        LinearLayout.LayoutParams lightP = new LinearLayout.LayoutParams(-1, dp(activity, 1));
        lightP.topMargin = dp(activity, 9);
        shell.addView(light, lightP);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(activity, 8), 0, 0);
        LinearLayout.LayoutParams scrollP = new LinearLayout.LayoutParams(-1, 0, 1f);
        shell.addView(scroll, scrollP);
        scroll.addView(card, new ScrollView.LayoutParams(-1, -2));

        TextView footer = mono(activity, "GREAT // " + module.zone.code + " // LIVE CONTROL", 7, DIM, true);
        footer.setGravity(Gravity.END);
        LinearLayout.LayoutParams footerP = new LinearLayout.LayoutParams(-1, -2);
        footerP.topMargin = dp(activity, 5);
        shell.addView(footer, footerP);

        dialog.setContentView(stage);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (card.getParent() instanceof ViewGroup) ((ViewGroup) card.getParent()).removeView(card);
            int safe = Math.max(0, Math.min(originalIndex, parent.getChildCount()));
            parent.addView(card, safe, originalParams);
        });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams p = new WindowManager.LayoutParams();
            p.copyFrom(window.getAttributes());
            int sw = activity.getResources().getDisplayMetrics().widthPixels;
            int sh = activity.getResources().getDisplayMetrics().heightPixels;
            boolean landscape = sw > sh;
            p.width = Math.round(sw * (landscape ? 0.72f : 0.95f));
            p.height = Math.round(sh * (landscape ? 0.88f : 0.86f));
            p.gravity = Gravity.CENTER;
            p.dimAmount = 0.72f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(p);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.getDecorView().setBackgroundColor(Color.TRANSPARENT);
            }
        }

        shell.setAlpha(0f);
        shell.setScaleX(0.94f);
        shell.setScaleY(0.94f);
        shell.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start();
    }

    private static Zone classify(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("history") || s.contains("log") || s.contains("سجل")
                || s.contains("release") || s.contains("إفراج")) return Zone.TRACE;
        if (s.contains("robot") || s.contains("روبوت") || s.contains("throttle")
                || s.contains("خنق") || s.contains("white-return") || s.contains("الأبيض")) {
            return Zone.ROBOT;
        }
        if (s.contains("freeze") || s.contains("تجميد") || s.contains("payload")
                || s.contains("فلترة") || s.contains("ramp") || s.contains("تدرّج")) {
            return Zone.FREEZE;
        }
        return Zone.CORE;
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
            if (s.length() <= 76) return s;
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
            return on + "/" + switches.size() + " ON";
        }
        if (!edits.isEmpty()) {
            StringBuilder b = new StringBuilder();
            int count = Math.min(2, edits.size());
            for (int i = 0; i < count; i++) {
                String v = edits.get(i).getText().toString().trim();
                if (v.isEmpty()) v = "—";
                if (i > 0) b.append(" · ");
                b.append(v);
            }
            return b.toString();
        }
        List<TextView> texts = new ArrayList<>();
        collectTexts(card, texts);
        for (int i = 1; i < texts.size(); i++) {
            CharSequence value = texts.get(i).getText();
            if (value == null) continue;
            String s = value.toString().trim();
            if (!s.isEmpty() && s.length() <= 38) return s;
        }
        return "OPEN";
    }

    private static String displayTitle(String raw) {
        String t = raw;
        t = t.replace("ROBOT LIGHT OUTBOUND THROTTLE", "Robot Light");
        t = t.replace("ROBOT WHITE-RETURN OUTBOUND THROTTLE", "Robot Post");
        t = t.replace("FREEZE HISTORY • LAST 5", "Freeze History");
        t = t.replace("DETAILED FREEZE LOG • LAST 3   ▸", "Freeze Trace");
        t = t.replace("DETAILED FREEZE LOG • LAST 3   ▾", "Freeze Trace");
        t = t.replace("تشغيل وإيقاف الـ VPN", "VPN");
        t = t.replace("إعدادات الـ VPN", "VPN Config");
        t = t.replace("التطبيقات المستهدفة", "Targets");
        t = t.replace("فلترة Payload", "Payload");
        t = t.replace("الخنق الخفيف بالتوازي مع التجميد", "Robot Light");
        t = t.replace("الخنق بعد عودة الأبيض", "Robot Post");
        t = t.replace("سجل دورات التجميد • آخر 5", "Freeze History");
        t = t.replace("السجل التفصيلي للتجميد • آخر 3   ▸", "Freeze Trace");
        t = t.replace("السجل التفصيلي للتجميد • آخر 3   ▾", "Freeze Trace");
        t = t.replace("الحد الأقصى لمدة تجميد الروبوت", "Robot Max Freeze");
        t = t.replace("إظهار الدوائر", "Show Overlay");
        t = t.replace("إخفاء الدوائر", "Hide Overlay");
        return t;
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

    private static View findTagged(ViewGroup root, String tag) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof ViewGroup) {
                View nested = findTagged((ViewGroup) child, tag);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static TextView text(Activity activity, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static TextView mono(Activity activity, String value, int sp, int color, boolean bold) {
        TextView view = text(activity, value, sp, color, false);
        view.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLetterSpacing(0.08f);
        return view;
    }

    private static GradientDrawable glassPanel(Activity activity, int accent) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xf0151922, 0xf00d1118});
        d.setCornerRadius(dp(activity, 16));
        d.setStroke(dp(activity, 1), withAlpha(accent, 74));
        return d;
    }

    private static GradientDrawable moduleBackground(Activity activity, int accent) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xaa202735, 0xaa111620});
        d.setCornerRadius(dp(activity, 10));
        d.setStroke(dp(activity, 1), withAlpha(accent, 42));
        return d;
    }

    private static GradientDrawable detailBackground(Activity activity, int accent) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xff171c27, 0xff0b0e14});
        d.setCornerRadius(dp(activity, 24));
        d.setStroke(dp(activity, 1), withAlpha(accent, 135));
        return d;
    }

    private static GradientDrawable round(Activity activity, int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(activity, radiusDp));
        d.setStroke(dp(activity, 1), stroke);
        return d;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((Math.max(0, Math.min(255, alpha))) << 24);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class BlueprintView extends View {
        private final Paint fine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint major = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        BlueprintView(Activity activity) {
            super(activity);
            density = activity.getResources().getDisplayMetrics().density;
            fine.setColor(0x142f3a4d);
            fine.setStrokeWidth(Math.max(1f, density * 0.45f));
            major.setColor(0x223f4d65);
            major.setStrokeWidth(Math.max(1f, density * 0.70f));
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(Math.max(1f, density));
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BG);
            float step = 28f * density;
            for (float x = 0; x < getWidth(); x += step) {
                Paint p = (((int) (x / step)) % 4 == 0) ? major : fine;
                canvas.drawLine(x, 0, x, getHeight(), p);
            }
            for (float y = 0; y < getHeight(); y += step) {
                Paint p = (((int) (y / step)) % 4 == 0) ? major : fine;
                canvas.drawLine(0, y, getWidth(), y, p);
            }

            glow.setColor(0x2867d8ff);
            float r1 = Math.min(getWidth(), getHeight()) * 0.38f;
            canvas.drawCircle(getWidth() * 0.10f, getHeight() * 0.18f, r1, glow);
            glow.setColor(0x24b993ff);
            float r2 = Math.min(getWidth(), getHeight()) * 0.46f;
            canvas.drawCircle(getWidth() * 0.92f, getHeight() * 0.76f, r2, glow);
        }
    }

    private static final class OrbitMark extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        OrbitMark(Activity activity) {
            super(activity);
            density = activity.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1f, density));
            ring.setColor(0x775b6983);
            hot.setStyle(Paint.Style.STROKE);
            hot.setStrokeWidth(Math.max(1.5f, density * 1.5f));
            hot.setColor(VIOLET);
            text.setColor(TEXT);
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.42f;
            canvas.drawCircle(cx, cy, r, ring);
            canvas.drawCircle(cx, cy, r * 0.67f, hot);
            canvas.drawLine(cx, cy - r, cx, cy - r * 0.72f, hot);
            canvas.drawLine(cx + r * 0.72f, cy, cx + r, cy, hot);
            text.setTextSize(Math.min(getWidth(), getHeight()) * 0.30f);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("G", cx, baseline, text);
        }
    }
}
