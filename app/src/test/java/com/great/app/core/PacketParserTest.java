package com.great.app.core;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PacketParserTest {
    @Test public void parsesIpv4UdpPortsWithoutCopyingPayload() {
        byte[] packet = new byte[28];
        packet[0] = 0x45;
        packet[2] = 0;
        packet[3] = 28;
        packet[9] = 17;
        packet[20] = 0x04;
        packet[21] = (byte) 0xD2;
        packet[22] = 0x01;
        packet[23] = (byte) 0xBB;

        PacketMetadata metadata = new PacketParser().parse(
                new PacketEnvelope(packet, packet.length, PacketDirection.OUTBOUND, 1L));

        assertTrue(metadata.valid());
        assertEquals(4, metadata.ipVersion());
        assertEquals(PacketParser.PROTO_UDP, metadata.protocol());
        assertEquals(1234, metadata.sourcePort());
        assertEquals(443, metadata.destinationPort());
        assertEquals(20, metadata.transportOffset());
    }

    @Test public void parsesIpv6TcpPorts() {
        byte[] packet = new byte[60];
        packet[0] = 0x60;
        packet[4] = 0;
        packet[5] = 20;
        packet[6] = 6;
        packet[40] = 0x1F;
        packet[41] = (byte) 0x90;
        packet[42] = 0x00;
        packet[43] = 0x50;

        PacketMetadata metadata = new PacketParser().parse(
                new PacketEnvelope(packet, packet.length, PacketDirection.INBOUND, 2L));

        assertTrue(metadata.valid());
        assertEquals(6, metadata.ipVersion());
        assertEquals(PacketParser.PROTO_TCP, metadata.protocol());
        assertEquals(8080, metadata.sourcePort());
        assertEquals(80, metadata.destinationPort());
        assertEquals(40, metadata.transportOffset());
    }

    @Test public void rejectsTruncatedPacket() {
        byte[] packet = {(byte) 0x45, 0, 0, 20};
        PacketMetadata metadata = new PacketParser().parse(
                new PacketEnvelope(packet, packet.length, PacketDirection.OUTBOUND, 3L));
        assertFalse(metadata.valid());
    }
}
