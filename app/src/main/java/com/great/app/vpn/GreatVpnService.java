package com.great.app.vpn;

import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import com.great.app.ui.GreatMainActivity;
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
import com.great.app.config.FreezeEntrySettingsStore;
import com.great.app.config.ReleaseSettingsStore;
import com.great.app.config.SecureConfigStore;
import com.great.app.core.GreatEngine;
import com.great.app.monitor.RobotFreezeOrchestrator;
import com.great.app.transport.AwgTransport;
import com.great.app.transport.GlobalRobotOutboundThrottle;
import com.great.app.transport.TunnelTransport;
import com.great.app.transport.TransportState;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns VPN lifecycle. It never contains capability rules or UI logic. */
public final class GreatVpnService extends VpnService {
    public static final String ACTION_START = "com.great.app.action.START";
    public static final String ACTION_STOP = "com.great.app.action.STOP";
    private static final String CHANNEL_ID = "great_vpn_service";
    private static final int NOTIFICATION_ID = 4108;
    private static final String PREF_RUNTIME = "great_vpn_service_runtime";
    private static final String KEY_DESIRED = "vpn_start_requested";
    private static volatile GreatVpnService activeService;
    private static volatile String lastStartFailure;
    private final ScheduledExecutorService healthChecks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GREAT-VPN-Health");
        t.setDaemon(true);
        return t;
    });
    private volatile TunnelTransport transport;

    public static boolean isTransportConnected() {
        GreatVpnService service = activeService;
        TunnelTransport current = service == null ? null : service.transport;
        return current != null && current.state() == TransportState.CONNECTED;
    }

    public static String publicStatus() {
        GreatVpnService service = activeService;
        if (service == null) return lastStartFailure == null ? "STOPPED" : lastStartFailure;
        TunnelTransport current = service.transport;
        if (current == null) return lastStartFailure == null ? "STOPPED" : lastStartFailure;
        TransportState state = current.state();
        if (state == TransportState.FAILED) {
            String type = current instanceof AwgTransport ? ((AwgTransport) current).failureType() : null;
            return "FAILED" + (type == null || type.isEmpty() ? "" : " (" + type + ")")
                    + " • tap START VPN to retry";
        }
        return state.name();
    }
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
        activeService = this;
        createNotificationChannel();
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
        healthChecks.scheduleWithFixedDelay(this::checkTransportHealth,
                6L, 6L, TimeUnit.SECONDS);
    }

    private void checkTransportHealth() {
        try {
            if (!getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE)
                    .getBoolean(KEY_DESIRED, false)) return;
            TunnelTransport observed = transport;
            if (observed == null) return; // startup is already underway
            TransportState state = observed.state();
            if (state != TransportState.FAILED && state != TransportState.STOPPED) return;
            synchronized (this) {
                // A STOP may have arrived while the watchdog was waiting.
                if (!getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE)
                        .getBoolean(KEY_DESIRED, false) || transport != observed) return;
                updateVpnNotification("Tunnel interrupted • recovering automatically");
                startEngine();
            }
        } catch (RuntimeException error) {
            lastStartFailure = "VPN recovery failed (" + error.getClass().getSimpleName() + ")";
        }
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
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DESIRED, false).apply();
            lastStartFailure = null;
            stopEngine();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean explicitStart = ACTION_START.equals(action);
        boolean resumeRequested = intent == null
                && getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).getBoolean(KEY_DESIRED, false);
        if (!explicitStart && !resumeRequested) return START_NOT_STICKY;
        // A sticky restart may only reuse the VPN grant if Android still authorizes us.
        if (VpnService.prepare(this) != null) {
            lastStartFailure = "VPN permission required • tap START VPN";
            getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DESIRED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startVpnForeground("Connecting • VPN active in background");
        } catch (RuntimeException error) {
            lastStartFailure = "Foreground VPN rejected (" + error.getClass().getSimpleName() + ")";
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).edit()
                .putBoolean(KEY_DESIRED, true).apply();
        startEngine();
        return transport != null && transport.state() == TransportState.CONNECTED
                ? START_STICKY : START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GREAT VPN protection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible while the GREAT VPN tunnel is running");
        manager.createNotificationChannel(channel);
    }

    private Notification vpnNotification(String details) {
        Intent open = new Intent(this, GreatMainActivity.class);
        PendingIntent launch = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("GREAT • VPN running")
                .setContentText(details)
                .setContentIntent(launch)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void startVpnForeground(String details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, vpnNotification(details),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, vpnNotification(details));
        }
    }

    private void updateVpnNotification(String details) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, vpnNotification(details));
    }

    private synchronized void startEngine() {
        if (transport != null) {
            if (transport.state() != TransportState.FAILED
                    && transport.state() != TransportState.STOPPED) {
                updateVpnNotification("Connected • background VPN service active");
                return;
            }
            transport.close();
            transport = null;
        }
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

            // Load persisted settings before the engine can receive any packet; no Activity required.
            FreezeEntrySettingsStore entry = new FreezeEntrySettingsStore(this);
            if (entry.rampEnabled()) {
                engine.freezeCore().setRampProfile(entry.rampDurationMinMs(),
                        entry.rampDurationMaxMs(), entry.rampStage1MinPercent(),
                        entry.rampStage1MaxPercent(), entry.rampStage2MinPercent(),
                        entry.rampStage2MaxPercent());
            } else {
                engine.freezeCore().setRampProfile(0, 0, 100, 100, 100, 100);
            }
            RobotFreezeOrchestrator.instance().setSkipPercent(entry.robotSkipPercent());
            ReleaseSettingsStore release = new ReleaseSettingsStore(this);
            engine.freezeCore().setReleaseDropRange(release.dropMinPercent(), release.dropMaxPercent());
            engine.freezeCore().setReleaseBurstRange(release.burstMin(), release.burstMax());
            engine.freezeCore().setReleaseDelayRangeMs(release.delayMinMs(), release.delayMaxMs());
            engine.freezeCore().setReleaseDropEnabled(release.dropEnabled());
            engine.freezeCore().setReleaseBurstEnabled(release.burstEnabled());
            engine.freezeCore().setReleaseDelayEnabled(release.delayEnabled());

            // Legacy FreezeCore outbound throttle is not controlled by these Robot cards.
            engine.freezeCore().setOutboundThrottleEnabled(false);

            GlobalRobotOutboundThrottle robotThrottle = GlobalRobotOutboundThrottle.instance();
            robotThrottle.setFeatureEnabled(true);

            robotThrottle.setLightEnabled(tuning.robotLightThrottleEnabled());
            robotThrottle.setLightStopOnWhiteReturn(tuning.robotLightStopOnWhiteReturn());
            robotThrottle.setLightRange(
                    tuning.robotLightStartMinMs(), tuning.robotLightStartMaxMs(),
                    tuning.robotLightDurationMinMs(), tuning.robotLightDurationMaxMs());

            robotThrottle.setVisualPostEnabled(tuning.outboundReleaseThrottleEnabled());
            robotThrottle.setVisualPostRange(
                    tuning.robotWhiteReturnStartMinMs(), tuning.robotWhiteReturnStartMaxMs(),
                    tuning.robotWhiteReturnDurationMinMs(), tuning.robotWhiteReturnDurationMaxMs());

            refreshTargets();
            raw = new SecureConfigStore(this).load();
            AwgConfig config = new AwgConfigParser().parse(raw);
            TunnelTransport next = new AwgTransport(engine.pipeline(), engine.freezeCore());
            next.start(this, config, raw);
            transport = next;
            lastStartFailure = null;
            updateVpnNotification("Connected • background VPN service active");
        } catch (Exception e) {
            lastStartFailure = "START FAILED (" + e.getClass().getSimpleName() + ")";
            getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DESIRED, false).apply();
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

    @Override public void onRevoke() {
        getSharedPreferences(PREF_RUNTIME, MODE_PRIVATE).edit()
                .putBoolean(KEY_DESIRED, false).apply();
        lastStartFailure = "VPN authorization revoked • tap START VPN";
        stopEngine();
        stopSelf();
    }

    @Override public void onDestroy() {
        healthChecks.shutdownNow();
        targets.unregister(targetChanges);
        unregisterReceiver(packageChanges);
        stopEngine();
        if (activeService == this) activeService = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
