package com.great.app.shizuku;

oneway interface ITouchTriggerCallback {
    void onTriggerChanged(boolean active, long eventNanos);
    void onMonitorStatus(String status);
}
