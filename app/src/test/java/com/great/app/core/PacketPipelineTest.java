package com.great.app.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PacketPipelineTest {
    @Test public void build1AlwaysPassesUnchanged() {
        CapabilityController state = new CapabilityController();
        PacketPipeline pipeline = new PacketPipeline(new PassPolicy(), state);
        byte[] payload = {1,2,3,4};
        PacketEnvelope packet = new PacketEnvelope(payload, payload.length, PacketDirection.OUTBOUND, 10L);
        assertEquals(PacketDecision.PASS, pipeline.evaluate(packet));
        assertSame(payload, packet.data());
    }

    @Test public void controllerIsSingleSourceOfTruth() {
        CapabilityController state = new CapabilityController();
        state.set(Capability.FREEZE, true);
        assertTrue(state.snapshot().enabled(Capability.FREEZE));
        state.reset();
        assertFalse(state.snapshot().enabled(Capability.FREEZE));
    }
}
