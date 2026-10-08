package com.great.app.shizuku;

import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passive Shizuku UserService. It never injects or consumes touch events. It opens the physical
 * touchscreen evdev node as shell, watches multi-touch slot/tracking-id events, and reports only
 * whether a contact that STARTED inside GREAT's current trigger circle is still held.
 */
public final class GreatTouchUserService extends IShizukuTouchService.Stub {
    private static final int EV_SYN = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int MAX_SLOTS = 32;

    private static final Pattern DEVICE = Pattern.compile("add device\\s+\\d+:\\s+(/dev/input/event\\d+)");
    private static final Pattern NAME = Pattern.compile("name:\\s+\"([^\"]+)\"");
    private static final Pattern MAX_X = Pattern.compile("ABS_MT_POSITION_X.*?max\\s+(-?\\d+)");
    private static final Pattern MAX_Y = Pattern.compile("ABS_MT_POSITION_Y.*?max\\s+(-?\\d+)");

    private final Object stateLock = new Object();
    private final SlotState[] slots = new SlotState[MAX_SLOTS];

    private volatile ITouchTriggerCallback callback;
    private volatile TriggerGeometry geometry = TriggerGeometry.disabled();
    private volatile String backend = "Passive monitor idle";
    private volatile boolean monitorRequested;
    private volatile Thread monitorThread;
    private volatile FileInputStream eventStream;
    private volatile int triggerContacts;

    public GreatTouchUserService() {
        for (int i = 0; i < slots.length; i++) slots[i] = new SlotState();
        backend = "Passive evdev monitor • uid=" + Process.myUid();
    }

    @Override public void destroy() {
        stopMonitor();
        System.exit(0);
    }

    @Override public int getUid() {
        return Process.myUid();
    }

    @Override public String getBackend() {
        return backend;
    }

    @Override public void setCallback(ITouchTriggerCallback callback) {
        this.callback = callback;
        notifyStatus(backend);
    }

    @Override public void updateTriggerGeometry(float centerX, float centerY, float radiusPx,
                                                int screenWidth, int screenHeight, int rotation,
                                                long revision) {
        TriggerGeometry previous = geometry;
        if (revision < previous.revision) return;
        geometry = new TriggerGeometry(
                previous.enabled,
                centerX,
                centerY,
                Math.max(0f, radiusPx),
                Math.max(1, screenWidth),
                Math.max(1, screenHeight),
                rotation & 3,
                revision
        );
    }

    @Override public void setTriggerEnabled(boolean enabled, long revision) {
        TriggerGeometry previous = geometry;
        if (revision < previous.revision) return;
        geometry = new TriggerGeometry(
                enabled,
                previous.centerX,
                previous.centerY,
                previous.radiusPx,
                previous.screenWidth,
                previous.screenHeight,
                previous.rotation,
                revision
        );
        if (!enabled) clearTriggerContacts(SystemClock.elapsedRealtimeNanos());
    }

    @Override public synchronized void startMonitor() {
        monitorRequested = true;
        Thread existing = monitorThread;
        if (existing != null && existing.isAlive()) return;
        Thread next = new Thread(this::monitorLoop, "GREAT-Passive-Touch");
        next.setDaemon(true);
        monitorThread = next;
        next.start();
    }

    @Override public synchronized void stopMonitor() {
        monitorRequested = false;
        FileInputStream stream = eventStream;
        eventStream = null;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) { }
        }
        Thread thread = monitorThread;
        monitorThread = null;
        if (thread != null) thread.interrupt();
        clearTriggerContacts(SystemClock.elapsedRealtimeNanos());
        backend = "Passive monitor stopped • uid=" + Process.myUid();
        notifyStatus(backend);
    }

    private void monitorLoop() {
        try {
            TouchDevice device = discoverTouchscreen();
            if (device == null) {
                backend = "No readable multi-touch evdev device • uid=" + Process.myUid();
                notifyStatus(backend);
                return;
            }

            backend = "Passive evdev • " + device.path + " • " + device.name
                    + " • " + device.maxX + "x" + device.maxY + " • uid=" + Process.myUid();
            notifyStatus(backend);

            try (FileInputStream input = new FileInputStream(device.path)) {
                eventStream = input;
                readEvents(input, device);
            } finally {
                eventStream = null;
            }
        } catch (Throwable e) {
            if (monitorRequested) {
                String message = e.getMessage();
                backend = "Passive monitor error: "
                        + (message == null ? e.getClass().getSimpleName() : message);
                notifyStatus(backend);
            }
        } finally {
            clearTriggerContacts(SystemClock.elapsedRealtimeNanos());
            synchronized (this) {
                if (Thread.currentThread() == monitorThread) monitorThread = null;
            }
        }
    }

    private void readEvents(FileInputStream input, TouchDevice device) throws Exception {
        final boolean is64 = Process.is64Bit();
        final int eventSize = is64 ? 24 : 16;
        final int typeOffset = is64 ? 16 : 8;
        byte[] raw = new byte[eventSize];
        int currentSlot = 0;

        while (monitorRequested && !Thread.currentThread().isInterrupted()) {
            if (!readFully(input, raw)) return;
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
            long eventNanos;
            if (is64) {
                long sec = b.getLong(0);
                long usec = b.getLong(8);
                eventNanos = sec * 1_000_000_000L + usec * 1_000L;
            } else {
                long sec = Integer.toUnsignedLong(b.getInt(0));
                long usec = Integer.toUnsignedLong(b.getInt(4));
                eventNanos = sec * 1_000_000_000L + usec * 1_000L;
            }
            if (eventNanos <= 0) eventNanos = SystemClock.elapsedRealtimeNanos();

            int type = Short.toUnsignedInt(b.getShort(typeOffset));
            int code = Short.toUnsignedInt(b.getShort(typeOffset + 2));
            int value = b.getInt(typeOffset + 4);

            if (type == EV_ABS) {
                if (code == ABS_MT_SLOT) {
                    currentSlot = Math.max(0, Math.min(MAX_SLOTS - 1, value));
                } else {
                    SlotState slot = slots[currentSlot];
                    if (code == ABS_MT_TRACKING_ID) {
                        if (value < 0) {
                            releaseSlot(slot, eventNanos);
                            slot.trackingId = -1;
                            slot.pendingStart = false;
                            slot.rawX = -1;
                            slot.rawY = -1;
                        } else {
                            // A fresh physical contact. Membership is decided after this report's
                            // coordinates arrive, then remains pinned until this tracking id ends.
                            releaseSlot(slot, eventNanos);
                            slot.trackingId = value;
                            slot.pendingStart = true;
                            slot.rawX = -1;
                            slot.rawY = -1;
                        }
                    } else if (code == ABS_MT_POSITION_X) {
                        slot.rawX = value;
                    } else if (code == ABS_MT_POSITION_Y) {
                        slot.rawY = value;
                    }
                }
            } else if (type == EV_SYN && code == SYN_REPORT) {
                resolvePendingStarts(device, eventNanos);
            }
        }
    }

    private void resolvePendingStarts(TouchDevice device, long eventNanos) {
        TriggerGeometry g = geometry;
        if (!g.enabled || g.radiusPx <= 0f) {
            for (SlotState slot : slots) slot.pendingStart = false;
            return;
        }

        for (SlotState slot : slots) {
            if (!slot.pendingStart || slot.trackingId < 0 || slot.rawX < 0 || slot.rawY < 0) continue;
            slot.pendingStart = false;
            float[] xy = mapToScreen(slot.rawX, slot.rawY, device.maxX, device.maxY, g);
            float dx = xy[0] - g.centerX;
            float dy = xy[1] - g.centerY;
            if (dx * dx + dy * dy <= g.radiusPx * g.radiusPx) {
                slot.triggerContact = true;
                int count;
                synchronized (stateLock) {
                    count = ++triggerContacts;
                }
                if (count == 1) notifyTrigger(true, eventNanos);
            }
        }
    }

    private void releaseSlot(SlotState slot, long eventNanos) {
        if (!slot.triggerContact) return;
        slot.triggerContact = false;
        int count;
        synchronized (stateLock) {
            triggerContacts = Math.max(0, triggerContacts - 1);
            count = triggerContacts;
        }
        if (count == 0) notifyTrigger(false, eventNanos);
    }

    private void clearTriggerContacts(long eventNanos) {
        boolean hadActive;
        synchronized (stateLock) {
            hadActive = triggerContacts > 0;
            triggerContacts = 0;
            for (SlotState slot : slots) {
                slot.triggerContact = false;
                slot.pendingStart = false;
            }
        }
        if (hadActive) notifyTrigger(false, eventNanos);
    }

    private void notifyTrigger(boolean active, long eventNanos) {
        ITouchTriggerCallback cb = callback;
        if (cb == null) return;
        try { cb.onTriggerChanged(active, eventNanos); }
        catch (Throwable ignored) { }
    }

    private void notifyStatus(String message) {
        ITouchTriggerCallback cb = callback;
        if (cb == null) return;
        try { cb.onMonitorStatus(message); }
        catch (Throwable ignored) { }
    }

    private static float[] mapToScreen(int rawX, int rawY, int maxX, int maxY, TriggerGeometry g) {
        float nx = clamp01(rawX / (float) Math.max(1, maxX));
        float ny = clamp01(rawY / (float) Math.max(1, maxY));
        float x;
        float y;
        switch (g.rotation & 3) {
            case 1 -> {
                x = ny * g.screenWidth;
                y = (1f - nx) * g.screenHeight;
            }
            case 2 -> {
                x = (1f - nx) * g.screenWidth;
                y = (1f - ny) * g.screenHeight;
            }
            case 3 -> {
                x = (1f - ny) * g.screenWidth;
                y = nx * g.screenHeight;
            }
            default -> {
                x = nx * g.screenWidth;
                y = ny * g.screenHeight;
            }
        }
        return new float[]{x, y};
    }

    private TouchDevice discoverTouchscreen() throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/getevent", "-pl")
                .redirectErrorStream(true)
                .start();
        List<TouchDevice> devices = new ArrayList<>();
        TouchDevice current = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher d = DEVICE.matcher(line);
                if (d.find()) {
                    if (current != null) devices.add(current);
                    current = new TouchDevice(d.group(1));
                    continue;
                }
                if (current == null) continue;
                Matcher n = NAME.matcher(line);
                if (n.find()) current.name = n.group(1);
                Matcher x = MAX_X.matcher(line);
                if (x.find()) current.maxX = parsePositive(x.group(1));
                Matcher y = MAX_Y.matcher(line);
                if (y.find()) current.maxY = parsePositive(y.group(1));
            }
            if (current != null) devices.add(current);
        } finally {
            try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            process.destroy();
        }

        TouchDevice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (TouchDevice candidate : devices) {
            if (candidate.maxX <= 0 || candidate.maxY <= 0) continue;
            int score = 0;
            String lower = candidate.name.toLowerCase(Locale.US);
            if (lower.contains("touchscreen")) score += 100;
            if (lower.contains("touch")) score += 50;
            if (lower.contains("goodix") || lower.contains("synaptics") || lower.contains("fts")) score += 20;
            long area = (long) candidate.maxX * candidate.maxY;
            score += (int) Math.min(30, area / 1_000_000L);
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static int parsePositive(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static boolean readFully(FileInputStream input, byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int n = input.read(buffer, offset, buffer.length - offset);
            if (n < 0) return false;
            offset += n;
        }
        return true;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class SlotState {
        int trackingId = -1;
        int rawX = -1;
        int rawY = -1;
        boolean pendingStart;
        boolean triggerContact;
    }

    private static final class TouchDevice {
        final String path;
        String name = "touchscreen";
        int maxX;
        int maxY;

        TouchDevice(String path) { this.path = path; }
    }

    private static final class TriggerGeometry {
        final boolean enabled;
        final float centerX;
        final float centerY;
        final float radiusPx;
        final int screenWidth;
        final int screenHeight;
        final int rotation;
        final long revision;

        TriggerGeometry(boolean enabled, float centerX, float centerY, float radiusPx,
                        int screenWidth, int screenHeight, int rotation, long revision) {
            this.enabled = enabled;
            this.centerX = centerX;
            this.centerY = centerY;
            this.radiusPx = radiusPx;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.rotation = rotation;
            this.revision = revision;
        }

        static TriggerGeometry disabled() {
            return new TriggerGeometry(false, 0f, 0f, 0f, 1, 1, 0, -1L);
        }
    }
}
