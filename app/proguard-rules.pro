# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Error Prone / Tink Annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Room
-keep class * extends androidx.room.RoomDatabase

# Moshi (KSP Codegen & Runtime)
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keep class * implements com.squareup.moshi.JsonAdapter { *; }
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-keep class *JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    public <init>(com.squareup.moshi.Moshi, java.lang.reflect.Type[]);
}

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Strip Logging (Log.v, Log.d, Log.i, Log.w, Log.e)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# OkHttp Optional Security Platform Companion Libraries (Bouncy Castle, Conscrypt, OpenJSSE)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
