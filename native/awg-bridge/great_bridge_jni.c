#include <jni.h>
#include <stddef.h>

struct go_string { const char *str; long n; };

extern int greatBridgeTurnOn(struct go_string ifname, int bridge_fd, int mtu, struct go_string settings);
extern void greatBridgeTurnOff(int handle);
extern int greatBridgeGetSocketV4(int handle);
extern int greatBridgeGetSocketV6(int handle);

JNIEXPORT jint JNICALL Java_com_great_app_transport_GreatAwgBridge_turnOn(
        JNIEnv *env, jclass clazz, jstring ifname, jint bridge_fd, jint mtu, jstring settings) {
    (void) clazz;
    const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
    const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
    if (ifname_str == NULL || settings_str == NULL) {
        if (ifname_str != NULL) (*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
        if (settings_str != NULL) (*env)->ReleaseStringUTFChars(env, settings, settings_str);
        return -1;
    }

    const size_t ifname_len = (size_t) (*env)->GetStringUTFLength(env, ifname);
    const size_t settings_len = (size_t) (*env)->GetStringUTFLength(env, settings);
    const int result = greatBridgeTurnOn(
            (struct go_string){ .str = ifname_str, .n = (long) ifname_len },
            bridge_fd,
            mtu,
            (struct go_string){ .str = settings_str, .n = (long) settings_len });

    (*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
    (*env)->ReleaseStringUTFChars(env, settings, settings_str);
    return result;
}

JNIEXPORT void JNICALL Java_com_great_app_transport_GreatAwgBridge_turnOff(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    greatBridgeTurnOff(handle);
}

JNIEXPORT jint JNICALL Java_com_great_app_transport_GreatAwgBridge_getSocketV4(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    return greatBridgeGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_com_great_app_transport_GreatAwgBridge_getSocketV6(
        JNIEnv *env, jclass clazz, jint handle) {
    (void) env;
    (void) clazz;
    return greatBridgeGetSocketV6(handle);
}
