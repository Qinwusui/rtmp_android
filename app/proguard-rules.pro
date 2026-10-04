# Room and kotlinx.serialization ship their own precise consumer rules.
# Reflection constructs only the generated Room implementation.
-keep class com.wusui.rtmpcapture.data.CaptureDatabase_Impl { public <init>(); }
# Suppress third-party Android Log calls in release, including RTMP command diagnostics.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
