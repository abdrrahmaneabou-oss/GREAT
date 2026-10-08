package com.great.app.core;

/** Protocol-level classification only; it does not assume any application or use-case. */
public final class DefaultPacketClassifier implements PacketClassifier {
    @Override public TrafficClass classify(PacketMetadata metadata) {
        if (!metadata.valid()) return TrafficClass.MALFORMED;
        return switch (metadata.protocol()) {
            case PacketParser.PROTO_TCP -> TrafficClass.TCP;
            case PacketParser.PROTO_UDP -> TrafficClass.UDP;
            case PacketParser.PROTO_ICMP, PacketParser.PROTO_ICMPV6 -> TrafficClass.CONTROL;
            default -> TrafficClass.OTHER;
        };
    }
}
