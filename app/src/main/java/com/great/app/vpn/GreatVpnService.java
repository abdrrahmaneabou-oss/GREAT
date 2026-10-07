package com.great.app.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;

import com.great.app.config.AwgConfig;
import com.great.app.config.AwgConfigParser;
import com.great.app.config.SecureConfigStore;
import com.great.app.transport.AwgTransport;
import com.great.app.transport.TunnelTransport;

import java.util.Arrays;

/** Owns VPN lifecycle. It never contains capability rules or UI logic. */
public final class GreatVpnService extends VpnService {
    public static final String ACTION_START = "com.great.app.action.START";
    public static final String ACTION_STOP = "com.great.app.action.STOP";
    private TunnelTransport transport;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopEngine();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) startEngine();
        return START_NOT_STICKY;
    }

    private synchronized void startEngine() {
        if (transport != null) return;
        byte[] raw = null;
        try {
            raw = new SecureConfigStore(this).load();
            AwgConfig config = new AwgConfigParser().parse(raw);
            TunnelTransport next = new AwgTransport();
            next.start(this, config, raw);
            transport = next;
        } catch (Exception e) {
            if (transport != null) transport.close();
            transport = null;
            stopSelf();
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    private synchronized void stopEngine() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    @Override public void onDestroy() { stopEngine(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
