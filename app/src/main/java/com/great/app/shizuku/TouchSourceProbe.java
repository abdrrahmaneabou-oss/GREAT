package com.great.app.shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/** One-shot diagnostic only: identifies a likely physical touchscreen from getevent metadata. */
public final class TouchSourceProbe {
    public record Result(boolean found, String devicePath, String name, String detail) { }

    private TouchSourceProbe() { }

    public static Result run() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getevent", "-pl")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return parse(reader);
            } finally {
                try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } catch (Throwable e) {
            String message = e.getMessage();
            return new Result(false, null, null,
                    "touch probe failed: " + (message == null ? e.getClass().getSimpleName() : message));
        } finally {
            if (process != null) process.destroy();
        }
    }

    static Result parse(BufferedReader reader) throws Exception {
        Candidate current = null;
        Candidate best = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.startsWith("add device")) {
                if (current != null && current.score() > 0 && (best == null || current.score() > best.score())) {
                    best = current;
                }
                current = new Candidate(extractPath(trimmed));
                continue;
            }
            if (current == null) continue;

            String lower = trimmed.toLowerCase(Locale.US);
            if (lower.startsWith("name:")) current.name = quoted(trimmed);
            if (trimmed.contains("ABS_MT_POSITION_X")) current.mtX = true;
            if (trimmed.contains("ABS_MT_POSITION_Y")) current.mtY = true;
            if (trimmed.contains("ABS_MT_TRACKING_ID")) current.trackingId = true;
            if (trimmed.contains("BTN_TOUCH")) current.btnTouch = true;
            if (trimmed.contains("INPUT_PROP_DIRECT")) current.direct = true;
        }
        if (current != null && current.score() > 0 && (best == null || current.score() > best.score())) {
            best = current;
        }

        if (best == null || !best.mtX || !best.mtY || !best.trackingId) {
            return new Result(false, null, null, "no physical multi-touch touchscreen detected");
        }
        String name = best.name == null || best.name.isBlank() ? "unnamed touchscreen" : best.name;
        String detail = "touchscreen visible • " + name
                + (best.path == null ? "" : " • " + best.path)
                + " • score=" + best.score();
        return new Result(true, best.path, name, detail);
    }

    private static String extractPath(String line) {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 >= line.length()) return null;
        String value = line.substring(colon + 1).trim();
        return value.isBlank() ? null : value;
    }

    private static String quoted(String line) {
        int first = line.indexOf('"');
        int last = line.lastIndexOf('"');
        if (first >= 0 && last > first) return line.substring(first + 1, last);
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
    }

    private static final class Candidate {
        final String path;
        String name;
        boolean mtX;
        boolean mtY;
        boolean trackingId;
        boolean btnTouch;
        boolean direct;

        Candidate(String path) { this.path = path; }

        int score() {
            if (!mtX || !mtY) return 0;
            int score = 10;
            if (trackingId) score += 8;
            if (btnTouch) score += 3;
            if (direct) score += 6;
            String lower = name == null ? "" : name.toLowerCase(Locale.US);
            if (lower.contains("touchscreen") || lower.contains("touch panel") || lower.contains("tp")) score += 4;
            if (lower.contains("fingerprint") || lower.contains("goodix_fp") || lower.contains("udfps")) score -= 20;
            return score;
        }
    }
}
