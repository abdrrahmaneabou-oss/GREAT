package com.great.app.transport;

import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import com.great.app.config.AwgConfig;
import com.great.app.core.FreezeCore;
import com.great.app.core.PacketDecision;
import com.great.app.core.PacketDirection;
import com.great.app.core.PacketEnvelope;
import com.great.app.core.PacketPipeline;

import org.amnezia.awg.config.Config;
import org.amnezia.awg.util.SharedLibraryLoader;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.amnezia.awg.GoBackend.awgVersion;

/**
 * GREAT transport adapter.
 *
 * Android's real TUN terminates here first. Packets are evaluated by GREAT's
 * PacketPipeline and then forwarded over a packet-preserving AF_UNIX bridge to
 * the official AmneziaWG Go engine. Capability-held packets are released back
 * through this same transport seam, never through a legacy FOX socket stack.
 */
public final class AwgTransport implements TunnelTransport {
    private static final String SESSION = "GREAT";
    private static final String IFACE = "great0";
    private static final int MAX_PACKET = 65535;

    private final PacketPipeline pipeline;
    private final FreezeCore capabilities;
    private final GlobalRobotOutboundThrottle globalRobotThrottle = GlobalRobotOutboundThrottle.instance();
    private final FreezeCore.OutputSink capabilitySink = this::emitCapabilityPacket;
    private final GlobalRobotOutboundThrottle.OutputSink globalThrottleSink = this::emitCapabilityPacket;
    private final Object bridgeWriteLock = new Object();
    private final Object tunWriteLock = new Object();
    private final AtomicLong outboundPackets = new AtomicLong();
    private final AtomicLong inboundPackets = new AtomicLong();
    private final AtomicLong droppedPackets = new AtomicLong();

    private volatile TransportState state = TransportState.STOPPED;
    private volatile boolean running;
    private int handle = -1;
    private ParcelFileDescriptor realTun;
    private FileDescriptor bridgeFd;
    private Thread outboundThread;
    private Thread inboundThread;

    public AwgTransport(PacketPipeline pipeline, FreezeCore capabilities) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public synchronized void start(VpnService vpnService, AwgConfig config, byte[] rawConfig) throws Exception {
        if (running || handle >= 0 || state == TransportState.CONNECTED || state == TransportState.CONNECTING) return;

        state = TransportState.STARTING;
        int startedHandle = -1;
        try {
            Config officialConfig = Config.parse(new ByteArrayInputStream(rawConfig));
            SharedLibraryLoader.loadSharedLibrary(vpnService, "wg-go");
            awgVersion();

            final int mtu = parseMtu(config.interfaceValue("MTU"));
            VpnService.Builder builder = vpnService.new Builder()
                    .setSession(SESSION)
                    .setBlocking(true)
                    .setMtu(mtu);

            addCidrsAsAddresses(builder, required(config.interfaceValue("Address"), "Address"));
            addCidrsAsRoutes(builder, required(config.peerValue("AllowedIPs"), "AllowedIPs"));

            String dns = config.interfaceValue("DNS");
            if (dns != null) addDns(builder, dns);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) vpnService.setUnderlyingNetworks(null);

            if (!hasDefaultRoute(config.peerValue("AllowedIPs"))) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            realTun = builder.establish();
            if (realTun == null) throw new IllegalStateException("Android refused to create the VPN TUN interface");

            FileDescriptor engineSide = new FileDescriptor();
            bridgeFd = new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, bridgeFd, engineSide);

            int engineFd;
            try (ParcelFileDescriptor dup = ParcelFileDescriptor.dup(engineSide)) {
                engineFd = dup.detachFd();
            } finally {
                try { Os.close(engineSide); } catch (Exception ignored) { }
            }

            state = TransportState.CONNECTING;
            startedHandle = GreatAwgBridge.turnOn(IFACE, engineFd, mtu, officialConfig.toAwgUserspaceString());
            if (startedHandle < 0) throw new IllegalStateException("AmneziaWG bridge activation failed: " + startedHandle);

            protectSocket(vpnService, GreatAwgBridge.getSocketV4(startedHandle), "IPv4");
            protectSocket(vpnService, GreatAwgBridge.getSocketV6(startedHandle), "IPv6");

            handle = startedHandle;
            running = true;
            capabilities.attach(capabilitySink);
            globalRobotThrottle.attach(globalThrottleSink);
            state = TransportState.CONNECTED;
            startPumps();
        } catch (Throwable failure) {
            running = false;
            capabilities.detach(capabilitySink);
            globalRobotThrottle.detach(globalThrottleSink);
            if (startedHandle >= 0) {
                try { GreatAwgBridge.turnOff(startedHandle); } catch (Throwable ignored) { }
            }
            handle = -1;
            closeBridgeLocked();
            closeTunLocked();
            state = TransportState.FAILED;
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    private void startPumps() {
        outboundThread = new Thread(this::pumpOutbound, "GREAT-TUN-Out");
        inboundThread = new Thread(this::pumpInbound, "GREAT-AWG-In");
        outboundThread.start();
        inboundThread.start();
    }

    private void pumpOutbound() {
        byte[] packet = new byte[MAX_PACKET];
        try {
            while (running) {
                ParcelFileDescriptor tun = realTun;
                FileDescriptor bridge = bridgeFd;
                if (tun == null || bridge == null) return;
                int length = Os.read(tun.getFileDescriptor(), packet, 0, packet.length);
                if (length <= 0) continue;
                outboundPackets.incrementAndGet();
                applyDecision(packet, length, PacketDirection.OUTBOUND, bridge);
            }
        } catch (Throwable failure) {
            if (running) failClosed(failure);
        }
    }

    private void pumpInbound() {
        byte[] packet = new byte[MAX_PACKET];
        try {
            while (running) {
                FileDescriptor bridge = bridgeFd;
                ParcelFileDescriptor tun = realTun;
                if (bridge == null || tun == null) return;
                int length = Os.read(bridge, packet, 0, packet.length);
                if (length <= 0) continue;
                inboundPackets.incrementAndGet();
                applyDecision(packet, length, PacketDirection.INBOUND, tun.getFileDescriptor());
            }
        } catch (Throwable failure) {
            if (running) failClosed(failure);
        }
    }

    private void applyDecision(byte[] packet, int length, PacketDirection direction,
                               FileDescriptor destination) throws Exception {
        PacketEnvelope envelope = new PacketEnvelope(packet, length, direction, System.nanoTime());

        // Robot global throttle runs before target-package ownership and Freeze rules.
        if (direction == PacketDirection.OUTBOUND
                && globalRobotThrottle.decide(envelope) == PacketDecision.HOLD) {
            return;
        }

        PacketDecision decision = pipeline.evaluate(envelope);
        switch (decision) {
            case PASS -> writeDirected(direction, destination, packet, length);
            case DROP -> droppedPackets.incrementAndGet();
            case HOLD -> { /* Core owns an immutable copy until release. */ }
            default -> throw new IllegalStateException("Unsupported live decision: " + decision);
        }
    }

    private void emitCapabilityPacket(PacketEnvelope packet) throws Exception {
        if (!running) throw new IOException("Transport is not running");
        if (packet.direction() == PacketDirection.OUTBOUND) {
            FileDescriptor bridge = bridgeFd;
            if (bridge == null) throw new IOException("AWG bridge is closed");
            writeDirected(PacketDirection.OUTBOUND, bridge, packet.data(), packet.length());
        } else {
            ParcelFileDescriptor tun = realTun;
            if (tun == null) throw new IOException("TUN is closed");
            writeDirected(PacketDirection.INBOUND, tun.getFileDescriptor(), packet.data(), packet.length());
        }
    }

    private void writeDirected(PacketDirection direction, FileDescriptor destination,
                               byte[] packet, int length) throws Exception {
        Object lock = direction == PacketDirection.OUTBOUND ? bridgeWriteLock : tunWriteLock;
        synchronized (lock) {
            int written = Os.write(destination, packet, 0, length);
            if (written != length) throw new IOException("Short packet write: " + written + "/" + length);
        }
    }

    /** Keep Android's TUN alive on an internal failure so traffic fails closed. */
    private synchronized void failClosed(Throwable ignored) {
        if (!running) return;
        running = false;
        capabilities.detach(capabilitySink);
        globalRobotThrottle.detach(globalThrottleSink);
        state = TransportState.FAILED;
        int current = handle;
        handle = -1;
        if (current >= 0) {
            try { GreatAwgBridge.turnOff(current); } catch (Throwable ignoredToo) { }
        }
        closeBridgeLocked();
    }

    private static void protectSocket(VpnService vpnService, int fd, String family) {
        if (fd >= 0 && !vpnService.protect(fd)) {
            throw new IllegalStateException("Failed to protect AWG " + family + " socket");
        }
    }

    @Override public TransportState state() { return state; }

    public long outboundPackets() { return outboundPackets.get(); }
    public long inboundPackets() { return inboundPackets.get(); }
    public long droppedPackets() { return droppedPackets.get(); }

    @Override
    public void close() {
        Thread out;
        Thread in;
        synchronized (this) {
            running = false;
            capabilities.detach(capabilitySink);
            globalRobotThrottle.detach(globalThrottleSink);
            int current = handle;
            handle = -1;
            if (current >= 0) {
                try { GreatAwgBridge.turnOff(current); } catch (Throwable ignored) { }
            }
            closeBridgeLocked();
            closeTunLocked();
            out = outboundThread;
            in = inboundThread;
            outboundThread = null;
            inboundThread = null;
        }

        join(out);
        join(in);
        state = TransportState.STOPPED;
    }

    private void closeBridgeLocked() {
        FileDescriptor bridge = bridgeFd;
        bridgeFd = null;
        if (bridge != null) {
            try { Os.shutdown(bridge, OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
            try { Os.close(bridge); } catch (Exception ignored) { }
        }
    }

    private void closeTunLocked() {
        ParcelFileDescriptor tun = realTun;
        realTun = null;
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) { }
        }
    }

    private static void join(Thread worker) {
        if (worker == null || worker == Thread.currentThread()) return;
        try { worker.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static void addCidrsAsAddresses(VpnService.Builder builder, String list) {
        for (String item : splitCsv(list)) {
            Cidr cidr = Cidr.parse(item);
            builder.addAddress(cidr.address, cidr.prefix);
        }
    }

    private static void addCidrsAsRoutes(VpnService.Builder builder, String list) {
        for (String item : splitCsv(list)) {
            Cidr cidr = Cidr.parse(item);
            builder.addRoute(cidr.address, cidr.prefix);
        }
    }

    private static void addDns(VpnService.Builder builder, String list) {
        for (String item : splitCsv(list)) builder.addDnsServer(item);
    }

    private static boolean hasDefaultRoute(String allowedIps) {
        if (allowedIps == null) return false;
        for (String item : splitCsv(allowedIps)) {
            Cidr cidr = Cidr.parse(item);
            if (cidr.prefix == 0) return true;
        }
        return false;
    }

    private static int parseMtu(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 1280;
        int value = Integer.parseInt(raw.trim());
        if (value < 576 || value > 65535) throw new IllegalArgumentException("Invalid MTU");
        return value;
    }

    private static String[] splitCsv(String value) {
        String[] raw = value.split(",");
        ArrayList<String> clean = new ArrayList<>(raw.length);
        for (String item : raw) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) clean.add(trimmed);
        }
        if (clean.isEmpty()) throw new IllegalArgumentException("Empty network list");
        return clean.toArray(new String[0]);
    }

    private static final class Cidr {
        final String address;
        final int prefix;

        Cidr(String address, int prefix) {
            this.address = address;
            this.prefix = prefix;
        }

        static Cidr parse(String value) {
            int slash = value.lastIndexOf('/');
            if (slash <= 0 || slash == value.length() - 1) {
                throw new IllegalArgumentException("Invalid CIDR: " + value);
            }
            String address = value.substring(0, slash).trim();
            int prefix = Integer.parseInt(value.substring(slash + 1).trim());
            boolean ipv6 = address.indexOf(':') >= 0;
            int max = ipv6 ? 128 : 32;
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + value);
            }
            return new Cidr(address, prefix);
        }
    }
}
