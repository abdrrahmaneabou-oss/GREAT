package com.great.app.shizuku;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure state machine for the Freeze trigger circle. It does not read Android input itself.
 * A backend feeds contact start/end events and current screen-space coordinates.
 */
public final class TouchTracker {
    public interface Listener {
        void onTriggerChanged(boolean active, long eventNanos);
    }

    public record Geometry(boolean enabled, float centerX, float centerY, float radiusPx,
                           int screenWidth, int screenHeight, int rotation, long revision) {
        public Geometry {
            radiusPx = Math.max(0f, radiusPx);
            screenWidth = Math.max(1, screenWidth);
            screenHeight = Math.max(1, screenHeight);
            rotation &= 3;
        }

        public static Geometry disabled() {
            return new Geometry(false, 0f, 0f, 0f, 1, 1, 0, -1L);
        }
    }

    private static final class Contact {
        final int trackingId;
        final boolean trigger;

        Contact(int trackingId, boolean trigger) {
            this.trackingId = trackingId;
            this.trigger = trigger;
        }
    }

    private final Listener listener;
    private final Map<Integer, Contact> contacts = new HashMap<>();
    private Geometry geometry = Geometry.disabled();
    private int triggerContacts;

    public TouchTracker(Listener listener) {
        this.listener = listener;
    }

    public synchronized Geometry geometry() {
        return geometry;
    }

    public synchronized void updateGeometry(Geometry next, long eventNanos) {
        if (next == null || next.revision() < geometry.revision()) return;
        geometry = next;
        if (!next.enabled()) clear(eventNanos);
    }

    /**
     * Registers a fresh physical contact. Circle membership is decided only once, at contact
     * start. Moving outside afterwards never releases the trigger; the same contact must end.
     */
    public synchronized void contactStarted(int slot, int trackingId, float x, float y, long eventNanos) {
        Contact old = contacts.remove(slot);
        if (old != null && old.trigger) decrementTrigger(eventNanos);

        Geometry g = geometry;
        boolean inside = g.enabled() && g.radiusPx() > 0f && insideCircle(x, y, g);
        contacts.put(slot, new Contact(trackingId, inside));
        if (inside) {
            int before = triggerContacts;
            triggerContacts++;
            if (before == 0) notifyChanged(true, eventNanos);
        }
    }

    /** Movement does not change membership by design. */
    public synchronized void contactMoved(int slot, int trackingId, float x, float y, long eventNanos) {
        Contact current = contacts.get(slot);
        if (current == null || current.trackingId != trackingId) return;
    }

    public synchronized void contactEnded(int slot, int trackingId, long eventNanos) {
        Contact current = contacts.get(slot);
        if (current == null || current.trackingId != trackingId) return;
        contacts.remove(slot);
        if (current.trigger) decrementTrigger(eventNanos);
    }

    public synchronized void cancelAll(long eventNanos) {
        clear(eventNanos);
    }

    public synchronized boolean active() {
        return triggerContacts > 0;
    }

    public synchronized int activeTriggerContacts() {
        return triggerContacts;
    }

    private void decrementTrigger(long eventNanos) {
        if (triggerContacts <= 0) return;
        triggerContacts--;
        if (triggerContacts == 0) notifyChanged(false, eventNanos);
    }

    private void clear(long eventNanos) {
        boolean wasActive = triggerContacts > 0;
        contacts.clear();
        triggerContacts = 0;
        if (wasActive) notifyChanged(false, eventNanos);
    }

    private void notifyChanged(boolean active, long eventNanos) {
        if (listener != null) listener.onTriggerChanged(active, eventNanos);
    }

    private static boolean insideCircle(float x, float y, Geometry g) {
        float dx = x - g.centerX();
        float dy = y - g.centerY();
        return dx * dx + dy * dy <= g.radiusPx() * g.radiusPx();
    }
}
