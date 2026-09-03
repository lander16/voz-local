# JNI bridge
-keep class com.whispercpp.whisper.** { *; }

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
