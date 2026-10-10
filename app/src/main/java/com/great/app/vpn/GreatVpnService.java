package com.great.app.vpn;

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Process;
import android.os.SystemClock;
import com.great.app.config.TargetAppsStore;
import com.great.app.core.ConnectionOwnerSelector;
import com.great.app.core.Capability;
import java.util.Set;
import android.net.VpnService;
import android.os.IBinder;

import com.great.app.config.AwgConfig;
import com.great.app.config.AwgConfigParser;
import com.great.app.config.CapabilitySettingsStore;
import com.great.app.config.FreezeHistoryStore;
import com.great.app.config.SecureConfigStore;
import com.great.app.core.GreatEngine;
import com.great.app.transport.AwgTransport;
import com.great.app.transport.GlobalRobotOutboundThrottle;
import com.great.app.transport.TunnelTransport;

import java.util.Arrays;

/** Owns VPN lifecycle. It never contains capability rules or UI logic. */
public final class GreatVpnService extends VpnService {
    public static final String ACTION_START = "com.great.app.action.START";
    public static final String ACTION_STOP = "com.great.app.action.STOP";
    private TunnelTransport transport;
    private TargetAppsStore targets;
    private FreezeHistoryStore freezeHistory;
    private final SharedPreferences.OnSharedPreferenceChangeListener targetChanges = (prefs, key) -> {
        if (TargetAppsStore.KEY.equals(key) && transport != null) refreshTargets();
    };
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (transport != null) refreshTargets();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        targets = new TargetAppsStore(this);
        freezeHistory = new FreezeHistoryStore(this);
        targets.register(targetChanges);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageChanges, filter);
    }

    private void refreshTargets() {
        GreatEngine engine = GreatEngine.instance();
        // Finish the previous Freeze before changing which applications it affects.
        engine.capabilities().set(Capability.FREEZE, false);
        Set<Integer> uids = targets.resolveUids();
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        engine.targetSelector().set(new ConnectionOwnerSelector(uids, Process.myUid(),
                (protocol, local, remote) -> connectivity.getConnectionOwnerUid(protocol, local, remote),
                SystemClock::elapsedRealtime));
        engine.setTargetCount(uids.size());
    }

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
            GreatEngine engine = GreatEngine.instance();
            engine.reset();
            engine.freezeCore().setCycleListener(freezeHistory::append);

            CapabilitySettingsStore tuning = new CapabilitySettingsStore(this);
            engine.freezeCore().setFreezeDurationSeconds(tuning.freezeSeconds());
            engine.freezeCore().setPayloadRange(tuning.freezePayloadMin(), tuning.freezePayloadMax());
            engine.freezeCore().setRandomPayloadRange(
                    tuning.freezePayloadMinFrom(), tuning.freezePayloadMinTo(),
                    tuning.freezePayloadMaxFrom(), tuning.freezePayloadMaxTo());
            engine.freezeCore().setRandomPayloadRangeEnabled(tuning.freezePayloadRandomEnabled());
            engine.freezeCore().setOutboundThrottleEnabled(tuning.outboundReleaseThrottleEnabled());
            GlobalRobotOutboundThrottle.instance().setFeatureEnabled(
                    tuning.outboundReleaseThrottleEnabled());

            refreshTargets();
            raw = new SecureConfigStore(this).load();
            AwgConfig config = new AwgConfigParser().parse(raw);
            TunnelTransport next = new AwgTransport(engine.pipeline(), engine.freezeCore());
            next.start(this, config, raw);
            transport = next;
        } catch (Exception e) {
            if (transport != null) transport.close();
            transport = null;
            GlobalRobotOutboundThrottle.instance().setFeatureEnabled(false);
            GreatEngine.instance().reset();
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
        GlobalRobotOutboundThrottle.instance().setFeatureEnabled(false);
        GreatEngine.instance().reset();
    }

    @Override public void onDestroy() {
        targets.unregister(targetChanges);
        unregisterReceiver(packageChanges);
        stopEngine();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
