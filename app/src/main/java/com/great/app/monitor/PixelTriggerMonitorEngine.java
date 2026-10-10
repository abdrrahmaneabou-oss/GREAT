package com.great.app.monitor;

import java.util.ArrayDeque;

/**
 * Pure visual state machine matching PixelTrigger's right-side sensor rules.
 * It consumes five tiny RGB probe samples and emits only armed/fired state changes.
 */
public final class PixelTriggerMonitorEngine {
    public static final float SENSOR_DIAMETER_MM = 0.30f;
    public static final int MAX_PROBE_POINTS = 5;

    public static final int WHITE_PIXEL_LUMINANCE = 190;
    public static final int WHITE_PIXEL_MIN_CHANNEL = 170;
    public static final int WHITE_PIXEL_MAX_CHROMA = 60;

    public static final float ARM_WHITE_COVERAGE = 0.50f;
    public static final int REQUIRED_ARM_FRAMES = 3;
    public static final int REQUIRED_REARM_FRAMES = 3;

    public static final int FIRE_MAX_LUMINANCE = 90;
    public static final int PROBE_CHANNEL_DELTA = 18;
    public static final int PROBE_LUMINANCE_DROP = 12;

    public enum State { WAITING_FOR_WHITE, ARMED, FIRED }

    public interface Listener { void onStateChanged(State state); }

    public static final class Sample {
        private final int[] probes;
        private final int count;
        private final float whiteRatio;
        private final int averageLuminance;

        public Sample(int[] packedRgb, int count) {
            if (packedRgb == null) throw new IllegalArgumentException("probes == null");
            this.count = Math.max(0, Math.min(Math.min(count, packedRgb.length), MAX_PROBE_POINTS));
            this.probes = new int[this.count];
            int whites = 0;
            int luminance = 0;
            for (int i = 0; i < this.count; i++) {
                int rgb = packedRgb[i] & 0x00ffffff;
                probes[i] = rgb;
                if (isPackedWhite(rgb)) whites++;
                luminance += luminance(rgb);
            }
            whiteRatio = this.count == 0 ? 0f : (float) whites / this.count;
            averageLuminance = this.count == 0 ? 0 : Math.round((float) luminance / this.count);
        }

        public int count() { return count; }
        public int probe(int index) { return probes[index]; }
        public float whiteRatio() { return whiteRatio; }
        public int averageLuminance() { return averageLuminance; }

        public boolean isArmingWhite() {
            return count > 0 && (isPackedWhite(probes[0]) || whiteRatio >= ARM_WHITE_COVERAGE);
        }

        public boolean isFireLuminance() {
            return count > 0 && (luminance(probes[0]) <= FIRE_MAX_LUMINANCE
                    || averageLuminance <= FIRE_MAX_LUMINANCE);
        }
    }

    private final Listener listener;
    private final ArrayDeque<Sample> whiteFrames = new ArrayDeque<>();
    private final RobotFreezeOrchestrator robotFreeze = RobotFreezeOrchestrator.instance();
    private State state = State.WAITING_FOR_WHITE;
    private Sample baseline;
    private boolean skippedVisualCycle;
    private boolean visualFreezeRan;

    public PixelTriggerMonitorEngine(Listener listener) {
        this.listener = listener;
        robotFreeze.ensureStarted();
    }

    public State state() { return state; }

    public void reset() {
        whiteFrames.clear();
        baseline = null;
        skippedVisualCycle = false;
        visualFreezeRan = false;
        robotFreeze.resetVisualCycle();
        setState(State.WAITING_FOR_WHITE);
    }

    public void process(Sample sample) {
        if (sample == null || sample.count() == 0) return;
        switch (state) {
            case WAITING_FOR_WHITE -> waitForWhite(sample, REQUIRED_ARM_FRAMES);
            case ARMED -> processArmed(sample);
            case FIRED -> waitForWhite(sample, REQUIRED_REARM_FRAMES);
        }
    }

    private void processArmed(Sample sample) {
        whiteFrames.clear();

        if (skippedVisualCycle) {
            if (sample.isArmingWhite()) {
                skippedVisualCycle = false;
                robotFreeze.finishVisualCycle(false);
            }
            return;
        }

        if (baseline == null || !isProbeDepartureFrom(sample, baseline) || !sample.isFireLuminance()) return;

        // A short periodic Robot Freeze owns this moment; if the pixel is still dark after it ends,
        // the next frame may begin a normal visual cycle.
        if (robotFreeze.periodicFreezeActive()) return;

        if (!robotFreeze.allowVisualFreeze()) {
            skippedVisualCycle = true;
            return;
        }

        visualFreezeRan = true;
        setState(State.FIRED);
    }

    private void waitForWhite(Sample sample, int requiredFrames) {
        if (!sample.isArmingWhite()) {
            whiteFrames.clear();
            return;
        }
        whiteFrames.addLast(sample);
        while (whiteFrames.size() > requiredFrames) whiteFrames.removeFirst();
        if (whiteFrames.size() < requiredFrames) return;
        baseline = average(whiteFrames);
        whiteFrames.clear();
        setState(State.ARMED);
    }

    private void setState(State next) {
        if (state == next) return;
        State previous = state;
        state = next;

        // Let the service change FreezeCore's real hold state first. The throttle is then coupled
        // to the actual Robot Freeze, not merely to a detector state transition.
        if (listener != null) listener.onStateChanged(next);

        if (previous == State.ARMED && next == State.FIRED && visualFreezeRan) {
            robotFreeze.visualFreezeStarted();
        } else if (previous == State.FIRED && next == State.ARMED) {
            boolean ran = visualFreezeRan;
            visualFreezeRan = false;
            robotFreeze.finishVisualCycle(ran);
        }
    }

    static boolean isProbeDepartureFrom(Sample current, Sample reference) {
        int count = Math.min(Math.min(current.count(), reference.count()), MAX_PROBE_POINTS);
        if (count <= 0) return false;
        if (probePointChanged(reference.probe(0), current.probe(0))) return true;

        int quorum = count >= 5 ? 3 : (count >= 3 ? 2 : 1);
        int changed = 0;
        for (int i = 1; i < count; i++) {
            if (probePointChanged(reference.probe(i), current.probe(i))) {
                changed++;
                if (changed >= quorum) return true;
            }
        }
        return false;
    }

    static boolean probePointChanged(int reference, int current) {
        int rr = red(reference), rg = green(reference), rb = blue(reference);
        int cr = red(current), cg = green(current), cb = blue(current);
        int channelDelta = Math.max(Math.abs(rr - cr), Math.max(Math.abs(rg - cg), Math.abs(rb - cb)));
        if (channelDelta >= PROBE_CHANNEL_DELTA) return true;
        if (luminance(reference) - luminance(current) >= PROBE_LUMINANCE_DROP) return true;
        return isPackedWhite(reference) && !isPackedWhite(current);
    }

    public static boolean isPackedWhite(int packedRgb) {
        int r = red(packedRgb), g = green(packedRgb), b = blue(packedRgb);
        int min = Math.min(r, Math.min(g, b));
        int max = Math.max(r, Math.max(g, b));
        int chroma = max - min;
        return luminance(packedRgb) >= WHITE_PIXEL_LUMINANCE
                && min >= WHITE_PIXEL_MIN_CHANNEL
                && chroma <= WHITE_PIXEL_MAX_CHROMA;
    }

    public static int luminance(int packedRgb) {
        return (54 * red(packedRgb) + 183 * green(packedRgb) + 19 * blue(packedRgb)) >> 8;
    }

    private static int red(int rgb) { return (rgb >>> 16) & 0xff; }
    private static int green(int rgb) { return (rgb >>> 8) & 0xff; }
    private static int blue(int rgb) { return rgb & 0xff; }

    private static Sample average(Iterable<Sample> samples) {
        int[] sumR = new int[MAX_PROBE_POINTS];
        int[] sumG = new int[MAX_PROBE_POINTS];
        int[] sumB = new int[MAX_PROBE_POINTS];
        int[] counts = new int[MAX_PROBE_POINTS];
        for (Sample sample : samples) {
            for (int i = 0; i < sample.count(); i++) {
                int rgb = sample.probe(i);
                sumR[i] += red(rgb);
                sumG[i] += green(rgb);
                sumB[i] += blue(rgb);
                counts[i]++;
            }
        }
        int valid = 0;
        int[] averaged = new int[MAX_PROBE_POINTS];
        for (int i = 0; i < MAX_PROBE_POINTS; i++) {
            if (counts[i] == 0) break;
            int r = Math.round((float) sumR[i] / counts[i]);
            int g = Math.round((float) sumG[i] / counts[i]);
            int b = Math.round((float) sumB[i] / counts[i]);
            averaged[i] = (r << 16) | (g << 8) | b;
            valid++;
        }
        return new Sample(averaged, valid);
    }
}
