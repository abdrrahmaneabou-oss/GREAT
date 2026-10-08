package com.great.app.shizuku;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class TouchTrackerTest {
    private static TouchTracker.Geometry geometry(long revision) {
        return new TouchTracker.Geometry(true, 500f, 500f, 100f, 1000, 1000, 0, revision);
    }

    @Test public void startsInsideAndReleasesOnlyWhenSameContactEnds() {
        List<Boolean> events = new ArrayList<>();
        TouchTracker tracker = new TouchTracker((active, nanos) -> events.add(active));
        tracker.updateGeometry(geometry(1), 1);

        tracker.contactStarted(2, 42, 500f, 500f, 2);
        assertTrue(tracker.active());
        tracker.contactMoved(2, 42, 900f, 900f, 3);
        assertTrue(tracker.active());
        tracker.contactEnded(2, 42, 4);

        assertFalse(tracker.active());
        assertEquals(java.util.Arrays.asList(true, false), events);
    }

    @Test public void contactStartingOutsideNeverArmsAfterMovingInside() {
        List<Boolean> events = new ArrayList<>();
        TouchTracker tracker = new TouchTracker((active, nanos) -> events.add(active));
        tracker.updateGeometry(geometry(1), 1);

        tracker.contactStarted(0, 5, 100f, 100f, 2);
        tracker.contactMoved(0, 5, 500f, 500f, 3);
        tracker.contactEnded(0, 5, 4);

        assertFalse(tracker.active());
        assertTrue(events.isEmpty());
    }

    @Test public void multipleTriggerContactsKeepHoldUntilLastOneEnds() {
        List<Boolean> events = new ArrayList<>();
        TouchTracker tracker = new TouchTracker((active, nanos) -> events.add(active));
        tracker.updateGeometry(geometry(1), 1);

        tracker.contactStarted(0, 10, 500f, 500f, 2);
        tracker.contactStarted(1, 11, 520f, 500f, 3);
        assertEquals(2, tracker.activeTriggerContacts());
        tracker.contactEnded(0, 10, 4);
        assertTrue(tracker.active());
        tracker.contactEnded(1, 11, 5);

        assertEquals(java.util.Arrays.asList(true, false), events);
    }

    @Test public void geometryChangeDuringHoldDoesNotReleaseExistingContact() {
        List<Boolean> events = new ArrayList<>();
        TouchTracker tracker = new TouchTracker((active, nanos) -> events.add(active));
        tracker.updateGeometry(geometry(1), 1);
        tracker.contactStarted(0, 12, 500f, 500f, 2);

        tracker.updateGeometry(new TouchTracker.Geometry(true, 900f, 900f, 20f,
                1000, 1000, 0, 2), 3);
        assertTrue(tracker.active());
        tracker.contactEnded(0, 12, 4);

        assertEquals(java.util.Arrays.asList(true, false), events);
    }

    @Test public void disablingCircleCancelsActiveHoldImmediately() {
        List<Boolean> events = new ArrayList<>();
        TouchTracker tracker = new TouchTracker((active, nanos) -> events.add(active));
        tracker.updateGeometry(geometry(1), 1);
        tracker.contactStarted(0, 3, 500f, 500f, 2);

        tracker.updateGeometry(new TouchTracker.Geometry(false, 500f, 500f, 100f,
                1000, 1000, 0, 2), 3);

        assertFalse(tracker.active());
        assertEquals(java.util.Arrays.asList(true, false), events);
    }

    @Test public void staleGeometryRevisionIsIgnored() {
        TouchTracker tracker = new TouchTracker((active, nanos) -> { });
        tracker.updateGeometry(new TouchTracker.Geometry(true, 700f, 700f, 50f,
                1000, 1000, 0, 7), 1);
        tracker.updateGeometry(new TouchTracker.Geometry(true, 100f, 100f, 400f,
                1000, 1000, 0, 6), 2);

        assertEquals(7L, tracker.geometry().revision());
        assertEquals(700f, tracker.geometry().centerX(), 0f);
    }
}
