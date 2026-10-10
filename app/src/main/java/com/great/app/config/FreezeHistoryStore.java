package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.great.app.core.FreezeCore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists the most recent completed Freeze cycles exactly as reported by FreezeCore. */
public final class FreezeHistoryStore {
    private static final String PREFS = "great_freeze_history";
    private static final String KEY_HISTORY = "history";
    public static final int LIMIT = 5;

    private final SharedPreferences prefs;

    public FreezeHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void append(FreezeCore.CycleStats stats) {
        List<Record> current = new ArrayList<>(history());
        current.add(0, new Record(
                stats.startedAtMillis(), stats.source(), stats.arrived(), stats.frozen(),
                stats.dropped(), stats.released(), stats.evicted()));
        if (current.size() > LIMIT) current = new ArrayList<>(current.subList(0, LIMIT));

        JSONArray array = new JSONArray();
        for (Record record : current) {
            JSONObject object = new JSONObject();
            try {
                object.put("startedAtMillis", record.startedAtMillis);
                object.put("source", record.source);
                object.put("arrived", record.arrived);
                object.put("frozen", record.frozen);
                object.put("dropped", record.dropped);
                object.put("released", record.released);
                object.put("evicted", record.evicted);
                array.put(object);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    public synchronized List<Record> history() {
        String raw = prefs.getString(KEY_HISTORY, "[]");
        ArrayList<Record> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            int count = Math.min(array.length(), LIMIT);
            for (int i = 0; i < count; i++) {
                JSONObject object = array.getJSONObject(i);
                result.add(new Record(
                        object.optLong("startedAtMillis", 0L),
                        object.optString("source", "UNKNOWN"),
                        object.optInt("arrived", 0),
                        object.optInt("frozen", 0),
                        object.optInt("dropped", 0),
                        object.optInt("released", 0),
                        object.optInt("evicted", 0)));
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(result);
    }

    public static final class Record {
        public final long startedAtMillis;
        public final String source;
        public final int arrived;
        public final int frozen;
        public final int dropped;
        public final int released;
        public final int evicted;

        Record(long startedAtMillis, String source, int arrived, int frozen,
               int dropped, int released, int evicted) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.arrived = arrived;
            this.frozen = frozen;
            this.dropped = dropped;
            this.released = released;
            this.evicted = evicted;
        }
    }
}
