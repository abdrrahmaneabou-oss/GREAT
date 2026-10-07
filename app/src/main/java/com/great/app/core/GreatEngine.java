package com.great.app.core;

/** Process-wide owner of GREAT's packet decision state and shared capability infrastructure. */
public final class GreatEngine {
    private static final GreatEngine INSTANCE = new GreatEngine();
    private static final int SCHEDULER_CAPACITY = 512;

    private final CapabilityController capabilities = new CapabilityController();
    private final EngineDiagnostics diagnostics = new EngineDiagnostics();
    private final PacketScheduler scheduler = new PacketScheduler(SCHEDULER_CAPACITY);
    private final ConfigurablePacketSelector ghostSelector = new ConfigurablePacketSelector();
    private final PacketPolicy policy = new CapabilityPolicyEngine(
            new GhostPolicy(ghostSelector),
            new PassPolicy());
    private final PacketPipeline pipeline = new PacketPipeline(
            policy,
            capabilities,
            new PacketParser(),
            new DefaultPacketClassifier(),
            diagnostics);

    private GreatEngine() {}

    public static GreatEngine instance() { return INSTANCE; }
    public CapabilityController capabilities() { return capabilities; }
    public PacketPipeline pipeline() { return pipeline; }
    public PacketScheduler scheduler() { return scheduler; }
    public EngineDiagnostics diagnostics() { return diagnostics; }
    public ConfigurablePacketSelector ghostSelector() { return ghostSelector; }

    public void reset() {
        capabilities.reset();
        scheduler.clear();
        diagnostics.reset();
        ghostSelector.clear();
    }
}
