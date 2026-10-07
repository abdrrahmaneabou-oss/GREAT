package com.great.app.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Shared bounded scheduler. It owns payload copies only for packets that must outlive the hot-path buffer. */
public final class PacketScheduler {
    private final int maxPackets;
    private final PriorityQueue<ScheduledPacket> queue = new PriorityQueue<>(Comparator.comparingLong(ScheduledPacket::dueNanos));

    public PacketScheduler(int maxPackets) {
        if (maxPackets < 1) throw new IllegalArgumentException("maxPackets must be positive");
        this.maxPackets = maxPackets;
    }

    public synchronized boolean schedule(PacketEnvelope packet, long dueNanos) {
        if (queue.size() >= maxPackets) return false;
        byte[] owned = Arrays.copyOf(packet.data(), packet.length());
        queue.add(new ScheduledPacket(owned, packet.direction(), packet.monotonicNanos(), dueNanos));
        return true;
    }

    public synchronized ScheduledPacket pollDue(long nowNanos) {
        ScheduledPacket next = queue.peek();
        if (next == null || next.dueNanos() > nowNanos) return null;
        return queue.poll();
    }

    public synchronized int size() { return queue.size(); }
    public synchronized int capacity() { return maxPackets; }
    public synchronized void clear() { queue.clear(); }

    public record ScheduledPacket(byte[] data, PacketDirection direction, long capturedNanos, long dueNanos) { }
}
