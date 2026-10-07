package com.great.app.transport;

import android.net.VpnService;
import com.great.app.config.AwgConfig;

/**
 * Build 1 boundary for the official AmneziaWG backend.
 * No FOX native binaries or legacy code are reused. The official backend will be wired here only.
 */
public final class AwgTransport implements TunnelTransport {
    private volatile TransportState state = TransportState.STOPPED;

    @Override public void start(VpnService vpnService, AwgConfig config, byte[] rawConfig) {
        state = TransportState.FAILED;
        throw new UnsupportedOperationException("Official AmneziaWG backend not wired yet");
    }

    @Override public TransportState state() { return state; }
    @Override public void close() { state = TransportState.STOPPED; }
}
