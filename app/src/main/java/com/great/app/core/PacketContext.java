package com.great.app.core;

import java.util.Objects;

/** Parsed packet plus classification; immutable and allocation-light. */
public final class PacketContext {
    private final PacketEnvelope packet;
    private final PacketMetadata metadata;
    private final TrafficClass trafficClass;

    public PacketContext(PacketEnvelope packet, PacketMetadata metadata, TrafficClass trafficClass) {
        this.packet = Objects.requireNonNull(packet, "packet");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.trafficClass = Objects.requireNonNull(trafficClass, "trafficClass");
    }

    public PacketEnvelope packet() { return packet; }
    public PacketMetadata metadata() { return metadata; }
    public TrafficClass trafficClass() { return trafficClass; }
}
