package com.great.app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Ordered package names entered by the user; no built-in application list. */
public final class TargetPackages {
    public static final int LIMIT = 15;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private final ArrayList<String> names = new ArrayList<>();
    public TargetPackages(List<String> saved) {
        for (String name : saved) {
            if (names.size() == LIMIT) break;
            if (name != null && PACKAGE.matcher(name).matches() && !names.contains(name)) names.add(name);
        }
    }
    public void add(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!PACKAGE.matcher(name).matches()) throw new IllegalArgumentException("Enter a valid package name, e.g. com.android.chrome");
        if (names.contains(name)) throw new IllegalArgumentException("This application is already added");
        if (names.size() == LIMIT) throw new IllegalArgumentException("You can target at most 15 applications");
        names.add(name);
    }
    public void remove(String name) { names.remove(name); }
    public List<String> names() { return Collections.unmodifiableList(new ArrayList<>(names)); }
}
