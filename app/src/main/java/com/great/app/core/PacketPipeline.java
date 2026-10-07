package com.great.app.core;

import java.util.Objects;

/** Hot-path coordinator: parse -> classify -> policy. No UI, sockets or lifecycle work lives here. */
public final class PacketPipeline {
    private final PacketPolicy policy;
    private final CapabilityController capabilities;
    private final PacketParser parser;
    private final PacketClassifier classifier;
    private final EngineDiagnostics diagnostics;

    public PacketPipeline(PacketPolicy policy, CapabilityController capabilities) {
        this(policy, capabilities, new PacketParser(), new DefaultPacketClassifier(), new EngineDiagnostics());
    }

    public PacketPipeline(PacketPolicy policy, CapabilityController capabilities,
                          PacketParser parser, PacketClassifier classifier,
                          EngineDiagnostics diagnostics) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public PacketDecision evaluate(PacketEnvelope packet) {
        diagnostics.packetSeen(packet.direction());
        PacketMetadata metadata = parser.parse(packet);
        diagnostics.parsed(metadata.valid());
        TrafficClass trafficClass = classifier.classify(metadata);
        PacketContext context = new PacketContext(packet, metadata, trafficClass);
        PacketDecision decision = policy.decide(context, capabilities.snapshot());
        diagnostics.decided(decision);
        return decision;
    }

    public EngineDiagnostics diagnostics() { return diagnostics; }
}
