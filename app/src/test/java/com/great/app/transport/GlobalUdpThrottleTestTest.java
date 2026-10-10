package com.great.app.transport;

import com.great.app.core.PacketDecision;
import com.great.app.core.PacketDirection;
import com.great.app.core.PacketEnvelope;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class GlobalUdpThrottleTestTest {
    @Test public void delaysGlobalOutboundIpv4UdpIncludingFragmentsByFixed100ms() throws Exception {
        GlobalUdpThrottleTest throttle = GlobalUdpThrottleTest.instance();
        throttle.setEnabled(false);
        CountDownLatch emitted = new CountDownLatch(2);
        GlobalUdpThrottleTest.OutputSink sink = packet -> emitted.countDown();
        throttle.attach(sink);
        try {
            PacketEnvelope normal = ipv4(PacketDirection.OUTBOUND, 17, false);
            PacketEnvelope fragmented = ipv4(PacketDirection.OUTBOUND, 17, true);

            assertEquals(PacketDecision.PASS, throttle.decide(normal));
            throttle.setEnabled(true);
            long started = System.nanoTime();
            assertEquals(PacketDecision.HOLD, throttle.decide(normal));
            assertEquals(PacketDecision.HOLD, throttle.decide(fragmented));
            assertFalse(emitted.await(70, TimeUnit.MILLISECONDS));
            assertTrue(emitted.await(120, TimeUnit.MILLISECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 90);
        } finally {
            throttle.setEnabled(false);
            throttle.detach(sink);
        }
    }

    @Test public void leavesInboundTcpAndIpv6OutsideDiagnosticThrottle() {
        GlobalUdpThrottleTest throttle = GlobalUdpThrottleTest.instance();
        throttle.setEnabled(true);
        try {
            assertEquals(PacketDecision.PASS,
                    throttle.decide(ipv4(PacketDirection.INBOUND, 17, false)));
            assertEquals(PacketDecision.PASS,
                    throttle.decide(ipv4(PacketDirection.OUTBOUND, 6, false)));
            byte[] ipv6 = new byte[40];
            ipv6[0] = 0x60;
            ipv6[6] = 17;
            assertEquals(PacketDecision.PASS,
                    throttle.decide(new PacketEnvelope(ipv6, ipv6.length,
                            PacketDirection.OUTBOUND, System.nanoTime())));
        } finally {
            throttle.setEnabled(false);
        }
    }

    private static PacketEnvelope ipv4(PacketDirection direction, int protocol, boolean fragmented) {
        byte[] data = new byte[32];
        data[0] = 0x45;
        data[2] = 0;
        data[3] = 32;
        data[8] = 64;
        data[9] = (byte) protocol;
        if (fragmented) data[6] = 0x20; // More Fragments flag; throttle must still match it.
        return new PacketEnvelope(data, data.length, direction, System.nanoTime());
    }
}
