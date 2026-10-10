package com.great.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.great.app.core.FreezeCore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists summary history plus a compact per-packet trace for the three latest Freeze cycles. */
public final class FreezeHistoryStore {
    private static final String PREFS = "great_freeze_history";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_DETAILS = "detail_history";
    public static final int LIMIT = 5;
    public static final int DETAIL_LIMIT = 3;

    private final SharedPreferences prefs;

    public FreezeHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void append(FreezeCore.CycleStats stats) {
        persistSummary(stats);
        persistDetail(stats);
    }

    private void persistSummary(FreezeCore.CycleStats stats) {
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

    private void persistDetail(FreezeCore.CycleStats stats) {
        List<DetailRecord> current = new ArrayList<>(detailHistory());
        int count = stats.traceCount();
        int[] trace = new int[count];
        for (int i = 0; i < count; i++) trace[i] = stats.traceValue(i);
        current.add(0, new DetailRecord(stats.startedAtMillis(), stats.source(),
                stats.payloadMin(), stats.payloadMax(), trace));
        if (current.size() > DETAIL_LIMIT) current = new ArrayList<>(current.subList(0, DETAIL_LIMIT));

        JSONArray array = new JSONArray();
        for (DetailRecord record : current) {
            JSONObject object = new JSONObject();
            try {
                object.put("startedAtMillis", record.startedAtMillis);
                object.put("source", record.source);
                object.put("payloadMin", record.payloadMin);
                object.put("payloadMax", record.payloadMax);
                object.put("trace", encodeTrace(record.trace));
                array.put(object);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_DETAILS, array.toString()).apply();
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

    public synchronized List<DetailRecord> detailHistory() {
        String raw = prefs.getString(KEY_DETAILS, "[]");
        ArrayList<DetailRecord> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            int count = Math.min(array.length(), DETAIL_LIMIT);
            for (int i = 0; i < count; i++) {
                JSONObject object = array.getJSONObject(i);
                result.add(new DetailRecord(
                        object.optLong("startedAtMillis", 0L),
                        object.optString("source", "UNKNOWN"),
                        object.optInt("payloadMin", FreezeCore.DEFAULT_PAYLOAD_MIN),
                        object.optInt("payloadMax", FreezeCore.DEFAULT_PAYLOAD_MAX),
                        decodeTrace(object.optString("trace", ""))));
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(result);
    }

    private static String encodeTrace(int[] trace) {
        ByteBuffer buffer = ByteBuffer.allocate(trace.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : trace) buffer.putInt(value);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private static int[] decodeTrace(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new int[0];
        try {
            byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
            int[] result = new int[raw.length / 4];
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < result.length; i++) result[i] = buffer.getInt();
            return result;
        } catch (Exception ignored) {
            return new int[0];
        }
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

    public static final class DetailRecord {
        public final long startedAtMillis;
        public final String source;
        public final int payloadMin;
        public final int payloadMax;
        public final int[] trace;

        DetailRecord(long startedAtMillis, String source, int payloadMin, int payloadMax, int[] trace) {
            this.startedAtMillis = startedAtMillis;
            this.source = source;
            this.payloadMin = payloadMin;
            this.payloadMax = payloadMax;
            this.trace = trace;
        }
    }
}