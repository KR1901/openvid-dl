# ---------------------------------------------------------------------------
# JNI surface — these names are looked up from native code, so they must
# survive R8 renaming/removal in the release build.
# ---------------------------------------------------------------------------

# NativeEngine's methods are declared `external`; keep the class and the
# native method names intact.
-keepclasseswithmembernames class dev.kr1901.openvid.engine.NativeEngine {
    native <methods>;
}
-keep class dev.kr1901.openvid.engine.NativeEngine { *; }

# The Rust download loop calls `onProgress(long, long)` on the callback object
# by name. Keep the interface and every implementation of it.
-keep interface dev.kr1901.openvid.engine.ProgressCallback { *; }
-keep class * implements dev.kr1901.openvid.engine.ProgressCallback { *; }
-keepclassmembers class * implements dev.kr1901.openvid.engine.ProgressCallback {
    public void onProgress(long, long);
}

# Format models are parsed by field access from Kotlin; keep them whole.
-keep class dev.kr1901.openvid.engine.** { *; }
