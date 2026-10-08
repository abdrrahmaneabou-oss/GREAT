package com.great.app.shizuku;

import com.great.app.shizuku.ITouchTriggerCallback;

interface IShizukuTouchService {
    void destroy() = 16777114;
    int getUid() = 1;
    String getBackend() = 2;

    void configureTrigger(boolean enabled,
                          float centerX, float centerY, float radiusPx,
                          int screenWidth, int screenHeight, int rotation,
                          long revision) = 3;
    void setTriggerCallback(ITouchTriggerCallback callback) = 4;
    long getTriggerRevision() = 5;

    // Kept temporarily for compatibility with older builds; the locked circle no longer uses it.
    boolean injectMotion(int action, long downTime, long eventTime,
                         float x, float y, int displayId) = 6;
}
