package com.great.app.shizuku;

oneway interface ITouchTriggerCallback {
    void onTriggerChanged(boolean active);
    void onStatus(String status);
}
