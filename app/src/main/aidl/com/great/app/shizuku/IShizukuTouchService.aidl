package com.great.app.shizuku;

interface IShizukuTouchService {
    void destroy() = 16777114;
    int getUid() = 1;
    String getBackend() = 2;
    oneway void injectMotion(int action, long downTime, long eventTime,
                             float x, float y, int displayId) = 3;
}
