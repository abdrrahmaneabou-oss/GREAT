package com.great.app.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

/** Keeps the target-app editor reachable after the legacy card is wrapped by ComfortSettingsUi. */
final class TargetAppsEditorUi {
    private static final String TARGET_HINT = "com.dts.freefireth";

    private TargetAppsEditorUi() { }

    static void ensure(View root) {
        EditText input = findTargetInput(root);
        if (input == null) return;
        View parent = (View) input.getParent();
        if (parent != null) parent.setVisibility(View.VISIBLE);
    }

    private static EditText findTargetInput(View view) {
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            if (hint != null && TARGET_HINT.contentEquals(hint)) return (EditText) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = findTargetInput(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
