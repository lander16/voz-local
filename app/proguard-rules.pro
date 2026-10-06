# JNI bridge
-keep class com.whispercpp.whisper.** { *; }
# Moonshine JNI accesses transcript fields and bridge classes by their Java names.
-keep class ai.moonshine.voice.** { *; }
# Sherpa JNI binds native methods by class/method name; its pinned AAR has no
# consumer rules, so preserve the bridge in minified release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Room
-keep class dev.sebastian.vozlocal.data.model.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# Moshi (still here for future use)
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep class kotlin.Metadata { *; }

# Compose
-keep class androidx.compose.runtime.** { *; }


# AccessibilityService
-keep class dev.sebastian.vozlocal.service.DictationAccessibilityService { *; }

# OkHttp optional TLS platform providers
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Crash report line numbers
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
