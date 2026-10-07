package com.great.app.transport;

import android.net.VpnService;
import com.great.app.config.AwgConfig;

/** Transport boundary. Capability code must never depend on AmneziaWG details. */
public interface TunnelTransport extends AutoCloseable {
    void start(VpnService vpnService, AwgConfig config, byte[] rawConfig) throws Exception;
    TransportState state();
    @Override void close();
}
