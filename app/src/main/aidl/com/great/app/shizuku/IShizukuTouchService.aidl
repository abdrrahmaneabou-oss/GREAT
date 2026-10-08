package com.great.app.shizuku;

import com.great.app.shizuku.ITouchTriggerCallback;

interface IShizukuTouchService {
    void destroy() = 16777114;
    int getUid() = 1;
    String getBackend() = 2;
    void setCallback(ITouchTriggerCallback callback) = 3;
    void updateTriggerGeometry(float centerX, float centerY, float radiusPx,
                               int screenWidth, int screenHeight, int rotation,
                               long revision) = 4;
    void setTriggerEnabled(boolean enabled, long revision) = 5;
    void startMonitor() = 6;
    void stopMonitor() = 7;
}
