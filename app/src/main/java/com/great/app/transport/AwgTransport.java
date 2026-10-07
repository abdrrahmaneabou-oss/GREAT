package com.great.app.transport;

import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import com.great.app.config.AwgConfig;

import org.amnezia.awg.config.Config;
import org.amnezia.awg.util.SharedLibraryLoader;

import java.io.ByteArrayInputStream;

import static org.amnezia.awg.GoBackend.awgGetSocketV4;
import static org.amnezia.awg.GoBackend.awgGetSocketV6;
import static org.amnezia.awg.GoBackend.awgTurnOff;
import static org.amnezia.awg.GoBackend.awgTurnOn;
import static org.amnezia.awg.GoBackend.awgVersion;

/**
 * GREAT's thin adapter around the official AmneziaWG userspace engine.
 *
 * This class owns only transport lifecycle. Capability rules stay out of this layer.
 * The official engine is pinned as a git submodule; no FOX binaries or legacy code are used.
 */
public final class AwgTransport implements TunnelTransport {
    private static final String SESSION = "GREAT";
    private static final String IFACE = "great0";

    private volatile TransportState state = TransportState.STOPPED;
    private int handle = -1;

    @Override
    public synchronized void start(VpnService vpnService, AwgConfig config, byte[] rawConfig) throws Exception {
        if (handle >= 0 || state == TransportState.CONNECTED || state == TransportState.CONNECTING) return;

        state = TransportState.STARTING;
        int startedHandle = -1;
        try {
            // The official parser is authoritative for AmneziaWG-specific fields and userspace serialization.
            Config officialConfig = Config.parse(new ByteArrayInputStream(rawConfig));

            // Load the official libwg-go.so produced by the pinned AmneziaWG Android source.
            SharedLibraryLoader.loadSharedLibrary(vpnService, "wg-go");
            awgVersion(); // Fail before TUN creation if the native bridge cannot be resolved.

            VpnService.Builder builder = vpnService.new Builder()
                    .setSession(SESSION)
                    .setBlocking(true);

            addCidrsAsAddresses(builder, required(config.interfaceValue("Address"), "Address"));
            addCidrsAsRoutes(builder, required(config.peerValue("AllowedIPs"), "AllowedIPs"));

            String dns = config.interfaceValue("DNS");
            if (dns != null) addDns(builder, dns);

            String mtu = config.interfaceValue("MTU");
            builder.setMtu(parseMtu(mtu));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) vpnService.setUnderlyingNetworks(null);

            if (!hasDefaultRoute(config.peerValue("AllowedIPs"))) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            state = TransportState.CONNECTING;
            try (ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null) throw new IllegalStateException("Android refused to create the VPN TUN interface");
                int tunFd = tun.detachFd();
                startedHandle = awgTurnOn(IFACE, tunFd, officialConfig.toAwgUserspaceString());
            }

            if (startedHandle < 0) throw new IllegalStateException("AmneziaWG activation failed: " + startedHandle);

            // Keep AmneziaWG's own UDP sockets outside the Android VPN to prevent a routing loop.
            int socket4 = awgGetSocketV4(startedHandle);
            int socket6 = awgGetSocketV6(startedHandle);
            if (socket4 >= 0 && !vpnService.protect(socket4)) throw new IllegalStateException("Failed to protect AWG IPv4 socket");
            if (socket6 >= 0 && !vpnService.protect(socket6)) throw new IllegalStateException("Failed to protect AWG IPv6 socket");

            handle = startedHandle;
            state = TransportState.CONNECTED;
        } catch (Throwable failure) {
            if (startedHandle >= 0) {
                try { awgTurnOff(startedHandle); } catch (Throwable ignored) { }
            }
            handle = -1;
            state = TransportState.FAILED;
            if (failure instanceof Exception) throw (Exception) failure;
            throw new RuntimeException(failure);
        }
    }

    @Override
    public TransportState state() {
        return state;
    }

    @Override
    public synchronized void close() {
        int current = handle;
        handle = -1;
        if (current >= 0) {
            try { awgTurnOff(current); } finally { state = TransportState.STOPPED; }
        } else {
            state = TransportState.STOPPED;
        }
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
        java.util.ArrayList<String> clean = new java.util.ArrayList<>(raw.length);
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
            if (slash <= 0 || slash == value.length() - 1) throw new IllegalArgumentException("Invalid CIDR: " + value);
            String address = value.substring(0, slash).trim();
            int prefix = Integer.parseInt(value.substring(slash + 1).trim());
            boolean ipv6 = address.indexOf(':') >= 0;
            int max = ipv6 ? 128 : 32;
            if (prefix < 0 || prefix > max) throw new IllegalArgumentException("Invalid CIDR prefix: " + value);
            return new Cidr(address, prefix);
        }
    }
}
