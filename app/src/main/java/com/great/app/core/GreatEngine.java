package com.great.app.core;

/** Process-wide owner of GREAT's packet decision state and capability infrastructure. */
public final class GreatEngine {
    private static final GreatEngine INSTANCE = new GreatEngine();

    private volatile int targetCount;

    private final CapabilityController capabilities = new CapabilityController();
    private final EngineDiagnostics diagnostics = new EngineDiagnostics();
    private final ConfigurablePacketSelector targetSelector = new ConfigurablePacketSelector();
    private final FreezeCore freezeCore = new FreezeCore(capabilities, diagnostics);
    private final PacketPolicy policy = new FreezePolicy(targetSelector, freezeCore);
    private final PacketPipeline pipeline = new PacketPipeline(
            policy,
            capabilities,
            new PacketParser(),
            new DefaultPacketClassifier(),
            diagnostics);

    private GreatEngine() {
        capabilities.setListener(freezeCore::onCapabilityChanged);
    }

    public int targetCount() { return targetCount; }
    public void setTargetCount(int count) { targetCount = count; }

    public static GreatEngine instance() { return INSTANCE; }
    public CapabilityController capabilities() { return capabilities; }
    public PacketPipeline pipeline() { return pipeline; }
    public EngineDiagnostics diagnostics() { return diagnostics; }
    public FreezeCore freezeCore() { return freezeCore; }

    /** Target gate defaults to match-nothing until the active VPN resolves saved packages. */
    public ConfigurablePacketSelector targetSelector() { return targetSelector; }

    public void reset() {
        freezeCore.reset();
        capabilities.reset();
        diagnostics.reset();
        targetSelector.clear();
        targetCount = 0;
    }
}
