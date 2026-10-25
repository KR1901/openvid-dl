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

# ---------------------------------------------------------------------------
# youtubedl-android
#
# CRITICAL: this library ships its rules as `proguard-rules.pro`, NOT
# `consumer-rules.pro`, so they are NOT applied to consumers. Without the
# commons-compress keep below, R8 (especially in full mode) mangles the archiver
# the library uses to unpack the bundled Python at init — init then throws,
# yt-dlp never becomes ready, and extraction silently falls back to the native
# extractor. That is the bug this rule fixes.
#
# It also maps yt-dlp's --dump-json output onto its mapper classes with Jackson
# reflection, so those fields must survive renaming too.
# ---------------------------------------------------------------------------
-keep class com.yausername.** { *; }
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.**

# Jackson needs annotations and generic signatures at runtime.
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes AnnotationDefault
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
