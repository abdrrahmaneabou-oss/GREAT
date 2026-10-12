package com.great.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * A calm, direct settings surface. It reuses the real existing controls/listeners but removes the
 * old giant vertical stack and the previous dashboard overlay. Settings are grouped into sections,
 * summarized when collapsed, and expanded inline only when the user needs them.
 */
final class ComfortSettingsUi {
    private static final String ROOT_TAG = "great_comfort_settings_root";
    private static final String PREFS = "great_comfort_ui";
    private static final String KEY_TAB = "selected_tab";

    private static final int BG = 0xff0a0c11;
    private static final int SURFACE = 0xff12161f;
    private static final int SURFACE_2 = 0xff191e29;
    private static final int FIELD = 0xff0f131b;
    private static final int STROKE = 0xff2a3140;
    private static final int TEXT = 0xfff5f7fb;
    private static final int MUTED = 0xff929bad;
    private static final int GREEN = 0xff6cdda6;
    private static final int RED = 0xffff7d8f;
    private static final int AMBER = 0xffffc56f;

    private enum Section {
        SYSTEM("النظام", "VPN • التطبيقات • الدوائر", "◈", 0xff8bb8ff),
        FREEZE("Freeze", "التجميد ووقت الدخول", "✣", 0xffb69cff),
        ROBOT("Robot", "الأتمتة والخنق", "◎", 0xff65d5c2),
        PAYLOAD("Payload", "فلترة أحجام الحزم", "⌁", 0xffffbd72),
        RELEASE("Release", "طريقة الإفراج", "⇢", 0xffff91b5),
        LOGS("السجل", "الدورات والتشخيص", "▤", 0xff8da1ff);

        final String title;
        final String subtitle;
        final String icon;
        final int accent;

        Section(String title, String subtitle, String icon, int accent) {
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.accent = accent;
        }
    }

    private static final class CardModel {
        final View card;
        final String title;
        final String searchable;
        final Section section;

        CardModel(View card, String title, String searchable, Section section) {
            this.card = card;
            this.title = title;
            this.searchable = searchable;
            this.section = section;
        }
    }

    private static final class CardShell {
        final CardModel model;
        final LinearLayout root;
        final LinearLayout body;
        final TextView summary;
        final TextView badge;
        final TextView chevron;
        boolean expanded;

        CardShell(CardModel model, LinearLayout root, LinearLayout body,
                  TextView summary, TextView badge, TextView chevron) {
            this.model = model;
            this.root = root;
            this.body = body;
            this.summary = summary;
            this.badge = badge;
            this.chevron = chevron;
        }
    }

    private static final class ScreenState {
        final Activity activity;
        final SharedPreferences prefs;
        final EnumMap<Section, List<CardShell>> shells = new EnumMap<>(Section.class);
        final EnumMap<Section, View> pages = new EnumMap<>(Section.class);
        final EnumMap<Section, TextView> tabs = new EnumMap<>(Section.class);
        Section selected;
        EditText search;

        ScreenState(Activity activity) {
            this.activity = activity;
            this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (Section section : Section.values()) shells.put(section, new ArrayList<>());
        }
    }

    private ComfortSettingsUi() { }

    static boolean isInstalled(View root) {
        return findTagged(root, ROOT_TAG) != null;
    }

    static void ensure(Activity activity, View decorRoot) {
        if (isInstalled(decorRoot)) return;
        ScrollView legacyScroll = findLegacyScroll(decorRoot);
        if (legacyScroll == null || legacyScroll.getChildCount() == 0) return;
        View child = legacyScroll.getChildAt(0);
        if (!(child instanceof LinearLayout)) return;
        LinearLayout legacyColumn = (LinearLayout) child;

        List<CardModel> models = collectCards(legacyColumn);
        if (models.size() < 6) return; // supplemental cards are not ready yet

        ViewGroup host = (ViewGroup) legacyScroll.getParent();
        if (host == null) return;
        int index = host.indexOfChild(legacyScroll);
        host.removeView(legacyScroll);

        ScreenState state = new ScreenState(activity);
        View replacement = buildScreen(state, models);
        replacement.setTag(ROOT_TAG);
        ViewGroup.LayoutParams lp = legacyScroll.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(-1, -1);
        host.addView(replacement, Math.max(0, Math.min(index, host.getChildCount())), lp);
    }

    private static View buildScreen(ScreenState state, List<CardModel> models) {
        Activity a = state.activity;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(a, 16), dp(a, 18), dp(a, 16), dp(a, 12));

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(a);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(a, "GREAT", 23, TEXT, true);
        TextView subtitle = text(a, "الإعدادات", 12, MUTED, false);
        brand.addView(title);
        brand.addView(subtitle);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView ready = text(a, "●  جاهز", 10, GREEN, true);
        ready.setGravity(Gravity.CENTER);
        ready.setPadding(dp(a, 11), dp(a, 7), dp(a, 11), dp(a, 7));
        ready.setBackground(round(a, 0xff102119, 999, 0xff254c3a));
        top.addView(ready);
        root.addView(top);

        state.search = new EditText(a);
        state.search.setSingleLine(true);
        state.search.setHint("ابحث داخل القسم الحالي");
        state.search.setTextColor(TEXT);
        state.search.setHintTextColor(0xff687284);
        state.search.setTextSize(13);
        state.search.setPadding(dp(a, 13), 0, dp(a, 13), 0);
        state.search.setBackground(round(a, FIELD, 14, STROKE));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(a, 44));
        searchParams.topMargin = dp(a, 14);
        root.addView(state.search, searchParams);

        HorizontalScrollView tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        LinearLayout tabRow = new LinearLayout(a);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tabScrollParams = new LinearLayout.LayoutParams(-1, -2);
        tabScrollParams.topMargin = dp(a, 12);
        root.addView(tabScroll, tabScrollParams);

        FrameLayout content = new FrameLayout(a);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = dp(a, 12);
        root.addView(content, contentParams);

        for (Section section : Section.values()) {
            List<CardModel> sectionModels = new ArrayList<>();
            for (CardModel model : models) if (model.section == section) sectionModels.add(model);
            if (sectionModels.isEmpty()) continue;

            TextView tab = buildTab(a, section, sectionModels.size());
            state.tabs.put(section, tab);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-2, dp(a, 40));
            if (tabRow.getChildCount() > 0) tabParams.leftMargin = dp(a, 8);
            tabRow.addView(tab, tabParams);

            View page = buildSectionPage(state, section, sectionModels);
            page.setVisibility(View.GONE);
            state.pages.put(section, page);
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));

            tab.setOnClickListener(v -> selectSection(state, section));
        }

        int saved = state.prefs.getInt(KEY_TAB, Section.FREEZE.ordinal());
        Section initial = Section.FREEZE;
        if (saved >= 0 && saved < Section.values().length
                && state.pages.containsKey(Section.values()[saved])) {
            initial = Section.values()[saved];
        } else if (!state.pages.containsKey(initial)) {
            initial = state.pages.keySet().iterator().next();
        }
        selectSection(state, initial);

        state.search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(state, s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return root;
    }

    private static TextView buildTab(Activity a, Section section, int count) {
        TextView tab = text(a, section.icon + "  " + section.title + "  " + count, 12, MUTED, true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(a, 13), 0, dp(a, 13), 0);
        tab.setBackground(round(a, SURFACE, 12, STROKE));
        tab.setClickable(true);
        tab.setFocusable(true);
        return tab;
    }

    private static View buildSectionPage(ScreenState state, Section section, List<CardModel> models) {
        Activity a = state.activity;
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(a, 24));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        LinearLayout intro = new LinearLayout(a);
        intro.setOrientation(LinearLayout.HORIZONTAL);
        intro.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(a, section.icon, 20, section.accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(a, withAlpha(section.accent, 0x18), 13, withAlpha(section.accent, 0x45)));
        intro.addView(icon, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 42)));

        LinearLayout copy = new LinearLayout(a);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(a, section.title, 17, TEXT, true);
        TextView sub = text(a, section.subtitle, 11, MUTED, false);
        copy.addView(heading);
        copy.addView(sub);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(a, 10);
        intro.addView(copy, copyParams);

        TextView count = text(a, models.size() + " إعدادات", 10, section.accent, true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(a, 9), dp(a, 6), dp(a, 9), dp(a, 6));
        count.setBackground(round(a, withAlpha(section.accent, 0x13), 999, withAlpha(section.accent, 0x35)));
        intro.addView(count);

        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.bottomMargin = dp(a, 12);
        column.addView(intro, introParams);

        for (CardModel model : models) {
            CardShell shell = buildCardShell(state, section, model);
            state.shells.get(section).add(shell);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(a, 10);
            column.addView(shell.root, p);
        }
        return scroll;
    }

    private static CardShell buildCardShell(ScreenState state, Section section, CardModel model) {
        Activity a = state.activity;
        ViewGroup oldParent = (ViewGroup) model.card.getParent();
        if (oldParent != null) oldParent.removeView(model.card);

        LinearLayout shell = new LinearLayout(a);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(round(a, SURFACE, 18, STROKE));
        shell.setClipToPadding(false);

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a, 13), dp(a, 11), dp(a, 11), dp(a, 11));
        header.setClickable(true);
        header.setFocusable(true);

        TextView rail = new TextView(a);
        rail.setBackground(round(a, section.accent, 999));
        header.addView(rail, new LinearLayout.LayoutParams(dp(a, 4), dp(a, 34)));

        LinearLayout labels = new LinearLayout(a);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(a, cleanTitle(model.title), 14, TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView summary = text(a, summarize(model.card), 11, MUTED, false);
        summary.setMaxLines(1);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(a, 2);
        labels.addView(summary, sp);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsParams.leftMargin = dp(a, 10);
        header.addView(labels, labelsParams);

        TextView badge = text(a, "", 9, MUTED, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(a, 8), dp(a, 5), dp(a, 8), dp(a, 5));
        header.addView(badge);

        TextView chevron = text(a, "⌄", 17, MUTED, true);
        chevron.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(a, 28), dp(a, 34));
        chevronParams.leftMargin = dp(a, 4);
        header.addView(chevron, chevronParams);
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 13), 0, dp(a, 13), dp(a, 13));
        body.setVisibility(View.GONE);
        styleLegacyCard(a, model.card, model.title, section.accent);
        body.addView(model.card, new LinearLayout.LayoutParams(-1, -2));
        shell.addView(body, new LinearLayout.LayoutParams(-1, -2));

        CardShell result = new CardShell(model, shell, body, summary, badge, chevron);
        updateHeaderState(a, result, section);

        header.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (result.expanded) {
                collapse(result);
            } else {
                for (CardShell other : state.shells.get(section)) if (other != result) collapse(other);
                expand(result);
            }
            result.summary.setText(summarize(result.model.card));
            updateHeaderState(a, result, section);
        });
        return result;
    }

    private static void expand(CardShell shell) {
        if (shell.expanded) return;
        shell.expanded = true;
        shell.body.setAlpha(0f);
        shell.body.setTranslationY(-dp((Activity) shell.body.getContext(), 6));
        shell.body.setVisibility(View.VISIBLE);
        shell.body.animate().alpha(1f).translationY(0f).setDuration(150).start();
        shell.chevron.animate().rotation(180f).setDuration(150).start();
    }

    private static void collapse(CardShell shell) {
        if (!shell.expanded) return;
        shell.expanded = false;
        shell.chevron.animate().rotation(0f).setDuration(120).start();
        shell.body.animate().alpha(0f).translationY(-dp((Activity) shell.body.getContext(), 4))
                .setDuration(100).withEndAction(() -> {
                    shell.body.setVisibility(View.GONE);
                    shell.body.setAlpha(1f);
                    shell.body.setTranslationY(0f);
                }).start();
        shell.summary.setText(summarize(shell.model.card));
    }

    private static void selectSection(ScreenState state, Section selected) {
        state.selected = selected;
        state.prefs.edit().putInt(KEY_TAB, selected.ordinal()).apply();
        for (Section section : Section.values()) {
            View page = state.pages.get(section);
            if (page != null) page.setVisibility(section == selected ? View.VISIBLE : View.GONE);
            TextView tab = state.tabs.get(section);
            if (tab != null) {
                boolean active = section == selected;
                tab.setTextColor(active ? TEXT : MUTED);
                tab.setBackground(round(state.activity,
                        active ? withAlpha(section.accent, 0x20) : SURFACE,
                        12,
                        active ? withAlpha(section.accent, 0x70) : STROKE));
            }
        }
        state.search.setText("");
    }

    private static void applyFilter(ScreenState state, String raw) {
        if (state.selected == null) return;
        String q = raw.trim().toLowerCase(Locale.ROOT);
        for (CardShell shell : state.shells.get(state.selected)) {
            boolean show = q.isEmpty() || shell.model.searchable.contains(q);
            shell.root.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) collapse(shell);
        }
    }

    private static void updateHeaderState(Activity a, CardShell shell, Section section) {
        List<Switch> switches = new ArrayList<>();
        collectSwitches(shell.model.card, switches);
        if (!switches.isEmpty()) {
            int on = 0;
            for (Switch s : switches) if (s.isChecked()) on++;
            boolean enabled = on > 0;
            shell.badge.setText(enabled ? "ON" : "OFF");
            shell.badge.setTextColor(enabled ? GREEN : MUTED);
            shell.badge.setBackground(round(a,
                    enabled ? 0xff10251c : 0xff1b202a,
                    999,
                    enabled ? 0xff295c43 : STROKE));
            return;
        }
        List<EditText> edits = new ArrayList<>();
        collectEdits(shell.model.card, edits);
        if (!edits.isEmpty()) {
            shell.badge.setText("CUSTOM");
            shell.badge.setTextColor(section.accent);
            shell.badge.setBackground(round(a, withAlpha(section.accent, 0x16), 999, withAlpha(section.accent, 0x4f)));
        } else {
            shell.badge.setText("OPEN");
            shell.badge.setTextColor(MUTED);
            shell.badge.setBackground(round(a, 0xff1b202a, 999, STROKE));
        }
    }

    private static void styleLegacyCard(Activity a, View card, String title, int accent) {
        if (card instanceof LinearLayout) {
            ((LinearLayout) card).setPadding(0, dp(a, 4), 0, 0);
            card.setBackgroundColor(Color.TRANSPARENT);
        }
        TextView originalTitle = findFirstText(card, title);
        if (originalTitle != null) originalTitle.setVisibility(View.GONE);
        styleTree(a, card, originalTitle, accent);
    }

    private static void styleTree(Activity a, View view, TextView hiddenTitle, int accent) {
        if (view == hiddenTitle) return;
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTextColor(TEXT);
            input.setHintTextColor(0xff707a8c);
            input.setTextSize(14);
            input.setMinHeight(dp(a, 46));
            input.setPadding(dp(a, 12), 0, dp(a, 12), 0);
            input.setBackground(round(a, FIELD, 12, STROKE));
        } else if (view instanceof Switch) {
            Switch sw = (Switch) view;
            sw.setTextColor(TEXT);
            sw.setMinHeight(dp(a, 44));
        } else if (view instanceof SeekBar) {
            view.setMinimumHeight(dp(a, 42));
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (tv.isClickable()) {
                String label = tv.getText() == null ? "" : tv.getText().toString().toLowerCase(Locale.ROOT);
                boolean destructive = label.contains("stop") || label.contains("delete") || label.contains("remove")
                        || label.contains("hide") || label.contains("إيقاف") || label.contains("حذف") || label.contains("إخفاء");
                int color = destructive ? RED : TEXT;
                tv.setTextColor(color);
                tv.setGravity(Gravity.CENTER);
                tv.setMinHeight(dp(a, 44));
                tv.setPadding(dp(a, 12), dp(a, 9), dp(a, 12), dp(a, 9));
                tv.setBackground(round(a,
                        destructive ? 0xff24151a : withAlpha(accent, 0x18),
                        12,
                        destructive ? 0xff63313a : withAlpha(accent, 0x58)));
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) styleTree(a, group.getChildAt(i), hiddenTitle, accent);
    }

    private static String summarize(View card) {
        List<Switch> switches = new ArrayList<>();
        collectSwitches(card, switches);
        if (!switches.isEmpty()) {
            int on = 0;
            for (Switch s : switches) if (s.isChecked()) on++;
            return on + " من " + switches.size() + " مفعّل";
        }

        List<SeekBar> seeks = new ArrayList<>();
        collectSeekBars(card, seeks);
        if (!seeks.isEmpty()) return seeks.get(0).getProgress() + "%";

        List<EditText> edits = new ArrayList<>();
        collectEdits(card, edits);
        if (!edits.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(3, edits.size()); i++) {
                if (i > 0) out.append("  •  ");
                String value = edits.get(i).getText().toString().trim();
                if (value.isEmpty()) value = "—";
                out.append(value);
            }
            return out.toString();
        }

        List<TextView> texts = new ArrayList<>();
        collectTexts(card, texts);
        for (int i = 1; i < texts.size(); i++) {
            String s = texts.get(i).getText() == null ? "" : texts.get(i).getText().toString().trim();
            if (!s.isEmpty() && s.length() <= 55) return s;
        }
        return "اضغط للتحكم";
    }

    private static List<CardModel> collectCards(LinearLayout legacyColumn) {
        List<CardModel> models = new ArrayList<>();
        for (int i = 0; i < legacyColumn.getChildCount(); i++) {
            View child = legacyColumn.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            String title = bestTitle(child);
            if (title.isEmpty()) continue;
            String all = allText(child).toLowerCase(Locale.ROOT);
            Section section = classify(title, all);
            models.add(new CardModel(child, title, all, section));
        }
        return models;
    }

    private static Section classify(String title, String all) {
        String s = (title + " " + all).toLowerCase(Locale.ROOT);
        if (s.contains("history") || s.contains("log") || s.contains("سجل")) return Section.LOGS;
        if (s.contains("release") || s.contains("إفراج")) return Section.RELEASE;
        if (s.contains("payload") || s.contains("packet range") || s.contains("فلترة")) return Section.PAYLOAD;
        if (s.contains("robot") || s.contains("روبوت") || s.contains("throttle") || s.contains("الخنق")
                || s.contains("white-return") || s.contains("white return")) return Section.ROBOT;
        if (s.contains("freeze") || s.contains("تجميد") || s.contains("ramp") || s.contains("تدرّج")) return Section.FREEZE;
        return Section.SYSTEM;
    }

    private static String bestTitle(View card) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(card, texts);
        for (TextView text : texts) {
            CharSequence value = text.getText();
            if (value == null) continue;
            String s = value.toString().trim();
            if (!s.isEmpty() && s.length() <= 90) return s;
        }
        return "";
    }

    private static String cleanTitle(String title) {
        String t = title.replace("   ▸", "").replace("   ▾", "").trim();
        if (t.length() > 56) t = t.substring(0, 53) + "…";
        return t;
    }

    private static String allText(View view) {
        StringBuilder out = new StringBuilder();
        appendText(view, out);
        return out.toString();
    }

    private static void appendText(View view, StringBuilder out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            CharSequence hint = view instanceof EditText ? ((EditText) view).getHint() : null;
            if (text != null) out.append(' ').append(text);
            if (hint != null) out.append(' ').append(hint);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), out);
    }

    private static TextView findFirstText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && expected.equals(text.toString().trim())) return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findFirstText(group.getChildAt(i), expected);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectTexts(View view, List<TextView> out) {
        if (view instanceof TextView && !(view instanceof EditText)) out.add((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out);
    }

    private static void collectEdits(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectEdits(group.getChildAt(i), out);
    }

    private static void collectSwitches(View view, List<Switch> out) {
        if (view instanceof Switch) out.add((Switch) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectSwitches(group.getChildAt(i), out);
    }

    private static void collectSeekBars(View view, List<SeekBar> out) {
        if (view instanceof SeekBar) out.add((SeekBar) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectSeekBars(group.getChildAt(i), out);
    }

    private static ScrollView findLegacyScroll(View view) {
        if (view instanceof ScrollView && view.getVisibility() == View.VISIBLE) {
            ScrollView scroll = (ScrollView) view;
            if (scroll.getChildCount() > 0 && scroll.getChildAt(0) instanceof LinearLayout) return scroll;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findLegacyScroll(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static View findTagged(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTagged(group.getChildAt(i), tag);
            if (found != null) return found;
        }
        return null;
    }

    private static TextView text(Activity a, String value, float sp, int color, boolean medium) {
        TextView view = new TextView(a);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static GradientDrawable round(Activity a, int color, int radiusDp) {
        return round(a, color, radiusDp, Color.TRANSPARENT);
    }

    private static GradientDrawable round(Activity a, int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a, radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(Math.max(1, dp(a, 1)), stroke);
        return d;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 0xff) << 24);
    }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
