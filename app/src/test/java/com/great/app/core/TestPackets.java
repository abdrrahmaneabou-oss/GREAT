package com.great.app.core;

final class TestPackets {
    static PacketEnvelope udp(PacketDirection direction, int remotePort, int payload, int marker) {
        byte[] p = new byte[28 + payload];
        p[0] = 0x45; put(p, 2, p.length); p[8] = 64; p[9] = 17;
        boolean out = direction == PacketDirection.OUTBOUND;
        p[12] = 10; p[15] = (byte) (out ? 1 : 2);
        p[16] = 10; p[19] = (byte) (out ? 2 : 1);
        put(p, 20, out ? 40000 : remotePort); put(p, 22, out ? remotePort : 40000);
        put(p, 24, payload + 8);
        if (payload > 0) p[28] = (byte) marker;
        return new PacketEnvelope(p, p.length, direction, System.nanoTime());
    }
    static PacketContext context(PacketEnvelope packet) {
        return new PacketContext(packet, new PacketParser().parse(packet), TrafficClass.UDP);
    }
    static void put(byte[] p, int offset, int value) { p[offset] = (byte)(value >>> 8); p[offset + 1] = (byte)value; }
}
