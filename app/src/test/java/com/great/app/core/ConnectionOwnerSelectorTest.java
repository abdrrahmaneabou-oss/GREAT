package com.great.app.core;
import org.junit.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public final class ConnectionOwnerSelectorTest {
    @Test public void bothDirectionsHaveSameLocalRemoteTupleAndCache() {
        AtomicInteger calls = new AtomicInteger();
        ConnectionOwnerSelector selector = new ConnectionOwnerSelector(Set.of(42),7,(protocol,local,remote)-> {
            calls.incrementAndGet();
            assertEquals(17,protocol); assertEquals("10.0.0.1",local.getAddress().getHostAddress());
            assertEquals(40000,local.getPort()); assertEquals("10.0.0.2",remote.getAddress().getHostAddress());
            assertEquals(443,remote.getPort()); return 42;
        },()->0);
        assertTrue(selector.matches(TestPackets.context(TestPackets.udp(PacketDirection.OUTBOUND,443,100,0))));
        assertTrue(selector.matches(TestPackets.context(TestPackets.udp(PacketDirection.INBOUND,443,100,0))));
        assertEquals(1,calls.get());
    }
    @Test public void unknownOtherAndSelfOwnersNeverMatch() {
        for(int uid : new int[]{-1,7,99}) {
            ConnectionOwnerSelector selector = new ConnectionOwnerSelector(Set.of(42,7),7,(p,l,r)->uid,()->0);
            assertFalse(selector.matches(TestPackets.context(TestPackets.udp(PacketDirection.INBOUND,443,100,0))));
        }
        ConnectionOwnerSelector denied = new ConnectionOwnerSelector(Set.of(42),7,(p,l,r)->{throw new SecurityException();},()->0);
        assertFalse(denied.matches(TestPackets.context(TestPackets.udp(PacketDirection.INBOUND,443,100,0))));
    }
    @Test public void unknownOwnerIsRetriedAndCacheExpires() {
        AtomicLong time = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        ConnectionOwnerSelector selector = new ConnectionOwnerSelector(Set.of(42),7,(p,l,r)->calls.incrementAndGet()==1?-1:42,time::get);
        PacketContext p = TestPackets.context(TestPackets.udp(PacketDirection.INBOUND,443,100,0));
        assertFalse(selector.matches(p)); assertTrue(selector.matches(p)); assertEquals(2,calls.get());
        time.set(30_000); assertTrue(selector.matches(p)); assertEquals(3,calls.get());
    }
    @Test public void remoteAddressIsPartOfCacheKey() {
        AtomicInteger calls = new AtomicInteger();
        ConnectionOwnerSelector selector = new ConnectionOwnerSelector(Set.of(42),7,(p,l,r)-> {calls.incrementAndGet();return 42;},()->0);
        PacketEnvelope p = TestPackets.udp(PacketDirection.INBOUND,443,100,0);
        assertTrue(selector.matches(TestPackets.context(p)));
        p.data()[15] = 3; assertTrue(selector.matches(TestPackets.context(p)));
        assertEquals(2,calls.get());
    }
}
