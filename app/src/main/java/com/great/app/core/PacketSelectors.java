package com.great.app.core;

import java.util.Objects;

/** Small composable selector helpers; no application-specific targeting is hard-coded. */
public final class PacketSelectors {
    private PacketSelectors() {}

    public static PacketSelector direction(PacketDirection direction) {
        Objects.requireNonNull(direction, "direction");
        return context -> context.packet().direction() == direction;
    }

    public static PacketSelector trafficClass(TrafficClass trafficClass) {
        Objects.requireNonNull(trafficClass, "trafficClass");
        return context -> context.trafficClass() == trafficClass;
    }

    public static PacketSelector sourcePort(int port) {
        validatePort(port);
        return context -> context.metadata().sourcePort() == port;
    }

    public static PacketSelector destinationPort(int port) {
        validatePort(port);
        return context -> context.metadata().destinationPort() == port;
    }

    public static PacketSelector and(PacketSelector first, PacketSelector second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return context -> first.matches(context) && second.matches(context);
    }

    public static PacketSelector or(PacketSelector first, PacketSelector second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return context -> first.matches(context) || second.matches(context);
    }

    private static void validatePort(int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
    }
}
