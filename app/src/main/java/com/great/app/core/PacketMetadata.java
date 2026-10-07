package com.great.app.core;

/** Allocation-light metadata extracted from an IP packet. */
public final class PacketMetadata {
    public static final int UNKNOWN_PORT = -1;

    private final boolean valid;
    private final int ipVersion;
    private final int protocol;
    private final int sourcePort;
    private final int destinationPort;
    private final int transportOffset;
    private final boolean fragmented;

    PacketMetadata(boolean valid, int ipVersion, int protocol, int sourcePort,
                   int destinationPort, int transportOffset, boolean fragmented) {
        this.valid = valid;
        this.ipVersion = ipVersion;
        this.protocol = protocol;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.transportOffset = transportOffset;
        this.fragmented = fragmented;
    }

    public static PacketMetadata invalid() {
        return new PacketMetadata(false, 0, 0, UNKNOWN_PORT, UNKNOWN_PORT, -1, false);
    }

    public boolean valid() { return valid; }
    public int ipVersion() { return ipVersion; }
    public int protocol() { return protocol; }
    public int sourcePort() { return sourcePort; }
    public int destinationPort() { return destinationPort; }
    public int transportOffset() { return transportOffset; }
    public boolean fragmented() { return fragmented; }
}
