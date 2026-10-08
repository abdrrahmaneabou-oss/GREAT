package com.great.app.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Resolves each IPv4 UDP flow to its Android owner; both directions use the same local/remote key. */
public final class ConnectionOwnerSelector implements PacketSelector {
    public interface Resolver { int owner(int protocol, InetSocketAddress local, InetSocketAddress remote) throws Exception; }
    private static final long TTL_MILLIS = 30_000;
    private static final int CACHE_CAPACITY = 256;
    private final Resolver resolver;
    private final LongSupplier clock;
    private final Set<Integer> targets;
    private final int ownUid;
    private final Map<Flow, Owner> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Flow, Owner> eldest) { return size() > CACHE_CAPACITY; }
    };

    public ConnectionOwnerSelector(Set<Integer> targets, int ownUid, Resolver resolver, LongSupplier clock) {
        this.targets = Collections.unmodifiableSet(new HashSet<>(targets));
        this.ownUid = ownUid; this.resolver = resolver; this.clock = clock;
    }
    @Override public boolean matches(PacketContext context) {
        PacketMetadata m = context.metadata();
        if (targets.isEmpty() || !m.valid() || m.ipVersion() != 4 || m.fragmented()
                || m.protocol() != PacketParser.PROTO_UDP || m.sourcePort() < 0 || m.destinationPort() < 0) return false;
        try {
            byte[] bytes = context.packet().data();
            InetSocketAddress source = new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)), m.sourcePort());
            InetSocketAddress destination = new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 16, 20)), m.destinationPort());
            boolean outbound = context.packet().direction() == PacketDirection.OUTBOUND;
            Flow flow = new Flow(m.protocol(), outbound ? source : destination, outbound ? destination : source);
            long now = clock.getAsLong();
            synchronized (cache) {
                Owner cached = cache.get(flow);
                if (cached != null && now - cached.time >= 0 && now - cached.time < TTL_MILLIS) return accepted(cached.uid);
                cache.remove(flow);
            }
            int uid = resolver.owner(flow.protocol, flow.local, flow.remote);
            if (uid < 0) return false; // Unknown ownership never freezes unrelated traffic.
            synchronized (cache) { cache.put(flow, new Owner(uid, now)); }
            return accepted(uid);
        } catch (Exception ignored) { return false; }
    }
    private boolean accepted(int uid) { return uid != ownUid && targets.contains(uid); }
    private record Flow(int protocol, InetSocketAddress local, InetSocketAddress remote) { }
    private record Owner(int uid, long time) { }
}
