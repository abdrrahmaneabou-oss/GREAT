package com.great.app.core;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PacketSchedulerTest {
    @Test public void schedulerIsBoundedAndOrdersByDueTime() {
        PacketScheduler scheduler = new PacketScheduler(2);
        PacketEnvelope first = new PacketEnvelope(new byte[]{1}, 1, PacketDirection.OUTBOUND, 10L);
        PacketEnvelope second = new PacketEnvelope(new byte[]{2}, 1, PacketDirection.INBOUND, 20L);
        PacketEnvelope third = new PacketEnvelope(new byte[]{3}, 1, PacketDirection.OUTBOUND, 30L);

        assertTrue(scheduler.schedule(first, 200L));
        assertTrue(scheduler.schedule(second, 100L));
        assertFalse(scheduler.schedule(third, 50L));
        assertEquals(2, scheduler.size());

        assertNull(scheduler.pollDue(99L));
        assertEquals(2, scheduler.pollDue(100L).data()[0]);
        assertEquals(1, scheduler.pollDue(200L).data()[0]);
        assertEquals(0, scheduler.size());
    }

    @Test public void queuedPacketOwnsItsPayloadCopy() {
        PacketScheduler scheduler = new PacketScheduler(1);
        byte[] data = {7, 8};
        PacketEnvelope packet = new PacketEnvelope(data, data.length, PacketDirection.OUTBOUND, 1L);
        assertTrue(scheduler.schedule(packet, 5L));
        data[0] = 99;
        assertEquals(7, scheduler.pollDue(5L).data()[0]);
    }
}
