package com.great.app.core;

/** Minimal IPv4/IPv6 parser for the packet hot path. */
public final class PacketParser {
    public static final int PROTO_ICMP = 1;
    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;
    public static final int PROTO_ICMPV6 = 58;

    public PacketMetadata parse(PacketEnvelope packet) {
        byte[] data = packet.data();
        int length = packet.length();
        if (length < 1) return PacketMetadata.invalid();

        int version = (data[0] >>> 4) & 0x0f;
        if (version == 4) return parseIpv4(data, length);
        if (version == 6) return parseIpv6(data, length);
        return PacketMetadata.invalid();
    }

    private static PacketMetadata parseIpv4(byte[] data, int length) {
        if (length < 20) return PacketMetadata.invalid();
        int ihl = (data[0] & 0x0f) * 4;
        if (ihl < 20 || ihl > length) return PacketMetadata.invalid();

        int totalLength = u16(data, 2);
        if (totalLength < ihl || totalLength > length) return PacketMetadata.invalid();

        int protocol = u8(data[9]);
        int fragment = u16(data, 6);
        boolean fragmented = (fragment & 0x3fff) != 0;
        boolean firstFragment = (fragment & 0x1fff) == 0;
        return metadata(4, protocol, ihl, data, totalLength, fragmented, firstFragment);
    }

    private static PacketMetadata parseIpv6(byte[] data, int length) {
        if (length < 40) return PacketMetadata.invalid();
        int payloadLength = u16(data, 4);
        int packetLength = 40 + payloadLength;
        if (packetLength > length) return PacketMetadata.invalid();

        int nextHeader = u8(data[6]);
        int offset = 40;
        boolean fragmented = false;
        boolean firstFragment = true;

        for (int hops = 0; hops < 8; hops++) {
            if (nextHeader == 0 || nextHeader == 43 || nextHeader == 60) {
                if (offset + 2 > packetLength) return PacketMetadata.invalid();
                int extensionLength = (u8(data[offset + 1]) + 1) * 8;
                if (extensionLength < 8 || offset + extensionLength > packetLength) return PacketMetadata.invalid();
                nextHeader = u8(data[offset]);
                offset += extensionLength;
                continue;
            }
            if (nextHeader == 44) {
                if (offset + 8 > packetLength) return PacketMetadata.invalid();
                int fragmentField = u16(data, offset + 2);
                fragmented = true;
                firstFragment = (fragmentField & 0xfff8) == 0;
                nextHeader = u8(data[offset]);
                offset += 8;
                continue;
            }
            if (nextHeader == 51) {
                if (offset + 2 > packetLength) return PacketMetadata.invalid();
                int extensionLength = (u8(data[offset + 1]) + 2) * 4;
                if (extensionLength < 8 || offset + extensionLength > packetLength) return PacketMetadata.invalid();
                nextHeader = u8(data[offset]);
                offset += extensionLength;
                continue;
            }
            break;
        }

        if (offset > packetLength) return PacketMetadata.invalid();
        return metadata(6, nextHeader, offset, data, packetLength, fragmented, firstFragment);
    }

    private static PacketMetadata metadata(int version, int protocol, int offset, byte[] data,
                                           int length, boolean fragmented, boolean firstFragment) {
        int source = PacketMetadata.UNKNOWN_PORT;
        int destination = PacketMetadata.UNKNOWN_PORT;
        if (firstFragment && (protocol == PROTO_TCP || protocol == PROTO_UDP) && offset + 4 <= length) {
            source = u16(data, offset);
            destination = u16(data, offset + 2);
        }
        return new PacketMetadata(true, version, protocol, source, destination, offset, fragmented);
    }

    private static int u8(byte value) { return value & 0xff; }
    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
