package com.great.app.core;

import java.util.Objects;

/** Hot path coordinator: pure decision logic only, no UI, no sockets, no Android lifecycle. */
public final class PacketPipeline {
    private final PacketPolicy policy;
    private final CapabilityController capabilities;

    public PacketPipeline(PacketPolicy policy, CapabilityController capabilities) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public PacketDecision evaluate(PacketEnvelope packet) {
        return policy.decide(packet, capabilities.snapshot());
    }
}
