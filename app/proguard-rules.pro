# GREAT deliberately keeps release rules minimal. Add rules only for a proven runtime need.
# JNI resolves these exact symbols from libwg-go.so.
-keep class com.great.app.transport.GreatAwgBridge {
    native <methods>;
}

# Shizuku creates this class reflectively in a separate shell process.
-keep class com.great.app.shizuku.GreatTouchUserService {
    public <init>();
    *;
}
