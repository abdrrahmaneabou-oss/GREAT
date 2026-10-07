package com.great.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GhostPolicyTest {
    private static PacketContext context() {
        byte[] data = new byte[20];
        PacketEnvelope packet = new PacketEnvelope(data, data.length, PacketDirection.INBOUND, 1L);
        PacketMetadata metadata = new PacketMetadata(true, 4, PacketParser.PROTO_UDP,
                1000, 2000, 20, false);
        return new PacketContext(packet, metadata, TrafficClass.UDP);
    }

    @Test public void disabledGhostAlwaysPasses() {
        CapabilityController state = new CapabilityController();
        GhostPolicy policy = new GhostPolicy(PacketSelector.all());
        assertEquals(PacketDecision.PASS, policy.decide(context(), state.snapshot()));
    }

    @Test public void enabledGhostDropsOnlyMatchingSelection() {
        CapabilityController state = new CapabilityController();
        state.set(Capability.GHOST, true);

        GhostPolicy matching = new GhostPolicy(PacketSelector.all());
        GhostPolicy notMatching = new GhostPolicy(PacketSelector.none());

        assertEquals(PacketDecision.DROP, matching.decide(context(), state.snapshot()));
        assertEquals(PacketDecision.PASS, notMatching.decide(context(), state.snapshot()));
    }
}
