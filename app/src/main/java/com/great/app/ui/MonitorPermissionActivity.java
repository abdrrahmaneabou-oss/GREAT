package com.great.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/** Each new capture session receives a fresh, user-approved full-display token. */
public final class MonitorPermissionActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView explanation = new TextView(this);
        explanation.setText("Visual Monitor needs the entire screen to align its ring with the game.\n\nApprove screen sharing to start.");
        explanation.setPadding(32, 64, 32, 32);
        setContentView(explanation);
        if (state != null) return;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, 1);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1) return;
        if (result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, FreezeMonitorService.class)
                    .setAction(FreezeMonitorService.ACTION_START)
                    .putExtra("result_code", result).putExtra("result_data", data));
        } else Toast.makeText(this, "Visual Monitor not started", Toast.LENGTH_SHORT).show();
        finish();
    }
}
