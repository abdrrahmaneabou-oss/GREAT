package com.great.app.core;

/** Process-wide owner of GREAT's pure packet decision state. */
public final class GreatEngine {
    private static final GreatEngine INSTANCE = new GreatEngine();

    private final CapabilityController capabilities = new CapabilityController();
    private final PacketPipeline pipeline = new PacketPipeline(new PassPolicy(), capabilities);

    private GreatEngine() {}

    public static GreatEngine instance() { return INSTANCE; }
    public CapabilityController capabilities() { return capabilities; }
    public PacketPipeline pipeline() { return pipeline; }

    public void reset() { capabilities.reset(); }
}
