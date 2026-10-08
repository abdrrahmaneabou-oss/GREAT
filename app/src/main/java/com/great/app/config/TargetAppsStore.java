package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import com.great.app.core.TargetPackages;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Saved editable package list. UID values are resolved afresh, never persisted. */
public final class TargetAppsStore {
    public static final String KEY = "packages";
    private final SharedPreferences prefs;
    private final PackageManager packages;
    public TargetAppsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("great_target_apps", Context.MODE_PRIVATE);
        packages = context.getPackageManager();
    }
    private TargetPackages load() { return new TargetPackages(Arrays.asList(prefs.getString(KEY, "").split("\n"))); }
    public List<String> names() { return load().names(); }
    public synchronized void add(String name) {
        TargetPackages list = load();
        list.add(name);
        String normalized = name.trim();
        try {
            ApplicationInfo app = packages.getApplicationInfo(normalized, 0);
            if (!app.enabled) throw new IllegalArgumentException("This application is disabled");
            if (app.uid == Process.myUid()) throw new IllegalArgumentException("GREAT cannot target itself");
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("This package is not installed in this Android profile");
        }
        save(list);
    }
    public synchronized void remove(String name) { TargetPackages list = load(); list.remove(name); save(list); }
    private void save(TargetPackages list) { prefs.edit().putString(KEY, String.join("\n", list.names())).apply(); }
    public Set<Integer> resolveUids() {
        Set<Integer> result = new HashSet<>();
        for (String name : names()) {
            try {
                ApplicationInfo app = packages.getApplicationInfo(name, 0);
                if (app.enabled && app.uid != Process.myUid()) result.add(app.uid);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return result;
    }
    public void register(SharedPreferences.OnSharedPreferenceChangeListener listener) { prefs.registerOnSharedPreferenceChangeListener(listener); }
    public void unregister(SharedPreferences.OnSharedPreferenceChangeListener listener) { prefs.unregisterOnSharedPreferenceChangeListener(listener); }
}
