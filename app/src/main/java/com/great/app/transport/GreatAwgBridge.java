package com.great.app.transport;

/** JNI surface for GREAT's packet bridge into the official AmneziaWG Go engine. */
final class GreatAwgBridge {
    private GreatAwgBridge() {}

    static native int turnOn(String ifName, int bridgeFd, int mtu, String settings);
    static native void turnOff(int handle);
    static native int getSocketV4(int handle);
    static native int getSocketV6(int handle);
}
