package com.great.app.core;

/** Process-wide owner of GREAT's packet decision state and capability infrastructure. */
public final class GreatEngine {
    private static final GreatEngine INSTANCE = new GreatEngine();
    private static final int SCHEDULER_CAPACITY = 512;

    private final CapabilityController capabilities = new CapabilityController();
    private final EngineDiagnostics diagnostics = new EngineDiagnostics();
    private final PacketScheduler scheduler = new PacketScheduler(SCHEDULER_CAPACITY);
    private final ConfigurablePacketSelector targetSelector = new ConfigurablePacketSelector();
    private final FoxCapabilityCore foxCapabilities = new FoxCapabilityCore(capabilities, diagnostics);
    private final PacketPolicy policy = new CapabilityPolicyEngine(
            new FoxCapabilityPolicy(targetSelector, foxCapabilities),
            new PassPolicy());
    private final PacketPipeline pipeline = new PacketPipeline(
            policy,
            capabilities,
            new PacketParser(),
            new DefaultPacketClassifier(),
            diagnostics);

    private GreatEngine() {
        capabilities.setListener(foxCapabilities::onCapabilityChanged);
    }

    public static GreatEngine instance() { return INSTANCE; }
    public CapabilityController capabilities() { return capabilities; }
    public PacketPipeline pipeline() { return pipeline; }
    public PacketScheduler scheduler() { return scheduler; }
    public EngineDiagnostics diagnostics() { return diagnostics; }
    public FoxCapabilityCore foxCapabilities() { return foxCapabilities; }

    /** Shared target gate for all three capabilities. It intentionally defaults to match-nothing. */
    public ConfigurablePacketSelector targetSelector() { return targetSelector; }

    /** Compatibility accessor retained for Build 2A callers; now aliases the shared target gate. */
    public ConfigurablePacketSelector ghostSelector() { return targetSelector; }

    public void reset() {
        foxCapabilities.reset();
        capabilities.reset();
        scheduler.clear();
        diagnostics.reset();
        targetSelector.clear();
    }
}
