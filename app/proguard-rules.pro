# Keep the JNI surface intact (names must match the Rust exports exactly).
-keepclasseswithmembernames class dev.kr1901.openvid.engine.NativeEngine {
    native <methods>;
}
-keep class dev.kr1901.openvid.engine.** { *; }
