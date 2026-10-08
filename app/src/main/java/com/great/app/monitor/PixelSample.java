package com.great.app.monitor;

/** The RGB probes used by PixelTrigger's right-hand detector. No HSV conversion. */
public final class PixelSample {
    private final int[] probes;
    public final int count;
    public final float whiteRatio;
    public final int luminance;

    public PixelSample(int... rgb) {
        if (rgb.length < 1 || rgb.length > 5) throw new IllegalArgumentException("1..5 probes required");
        probes = rgb.clone();
        count = probes.length;
        int white = 0, luma = 0;
        for (int p : probes) {
            if (isWhite(p)) white++;
            luma += luminance(p);
        }
        whiteRatio = (float) white / count;
        luminance = luma / count;
    }

    public int probe(int index) { return probes[index]; }
    public boolean armingWhite() { return whiteRatio >= .5f; }

    public static int luminance(int rgb) {
        return (red(rgb) * 54 + green(rgb) * 183 + blue(rgb) * 19) >> 8;
    }

    public static boolean isWhite(int rgb) {
        int min = Math.min(red(rgb), Math.min(green(rgb), blue(rgb)));
        int max = Math.max(red(rgb), Math.max(green(rgb), blue(rgb)));
        return luminance(rgb) >= 190 && min >= 170 && max - min <= 60;
    }

    public boolean departedFrom(int[] baseline) {
        int n = Math.min(count, baseline.length);
        int quorum = n >= 5 ? 3 : n >= 3 ? 2 : 1;
        int changed = 0;
        for (int i = 0; i < n; i++) {
            if (probeChanged(baseline[i], probes[i]) && ++changed >= quorum) return true;
        }
        return false;
    }

    static boolean probeChanged(int reference, int current) {
        int delta = Math.max(Math.abs(red(reference) - red(current)),
                Math.max(Math.abs(green(reference) - green(current)), Math.abs(blue(reference) - blue(current))));
        return delta >= 18 || luminance(reference) - luminance(current) >= 12
                || (isWhite(reference) && !isWhite(current));
    }

    static int red(int rgb) { return (rgb >>> 16) & 255; }
    static int green(int rgb) { return (rgb >>> 8) & 255; }
    static int blue(int rgb) { return rgb & 255; }
}
