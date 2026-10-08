package com.great.app.shizuku;

import com.great.app.shizuku.ITouchTriggerCallback;

interface IShizukuTouchService {
    void destroy() = 16777114;
    int getUid() = 1;
    String getStatus() = 2;
    void setCallback(ITouchTriggerCallback callback) = 3;
    void configureTrigger(float centerX, float centerY, float radiusPx,
                          int screenWidth, int screenHeight, int rotation) = 4;
    void startMonitor() = 5;
    void stopMonitor() = 6;
}
