package com.great.app.ui;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;

import com.great.app.config.MonitorSettingsStore;
import com.great.app.core.GreatEngine;
import com.great.app.monitor.RobotFreezeOrchestrator;
import com.great.app.transport.GlobalRobotOutboundThrottle;
import com.great.app.vpn.GreatVpnService;

/**
 * User-initiated total stop. Do not delete stored configurations or history.
 * Stop services rather than killing the process: Android owns process lifetime.
 */
public final class AllOffController {
    private AllOffController() { }

    public static void stopEverything(Activity activity) {
        // Disarm persistent restart intent BEFORE service teardown: a health
        // watchdog must never restore VPN or monitor after the explicit OFF.
        GreatVpnService.disableAutomaticRestart(activity);
        MonitorSettingsStore monitor = new MonitorSettingsStore(activity);
        monitor.setMonitoringEnabled(false);
        monitor.setEnabled(false);

        // Explicit services stop. stopService does not create new services if
        // they are already absent, unlike dispatching ACTION_STOP to startService.
        activity.stopService(new Intent(activity, FreezeMonitorService.class));
        activity.stopService(new Intent(activity, CapabilityOverlayService.class));
        activity.stopService(new Intent(activity, GreatVpnService.class));

        // Clear all in-process runtime gates and pending robot timer owners.
        RobotFreezeOrchestrator.instance().resetVisualCycle();
        GlobalRobotOutboundThrottle.instance().setFeatureEnabled(false);
        GreatEngine.instance().reset();

        // Remove any leftover foreground/recovery notifications. Only GREAT's
        // own stable IDs are cancelled; never clear notifications of other apps.
        NotificationManager notifications = activity.getSystemService(NotificationManager.class);
        if (notifications != null) {
            notifications.cancel(4107); // capture foreground (legacy)
            notifications.cancel(4108); // VPN foreground
            notifications.cancel(4109); // capture permission/recovery
        }

        // Close the GREAT screen and remove its task. There are no running
        // foreground services or periodic UI callbacks after this operation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.finishAndRemoveTask();
        } else {
            activity.finish();
        }
    }
}
