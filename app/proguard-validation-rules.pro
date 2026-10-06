# AndroidX Test's TestDirCalculator calls Kotlin's lazy facade from the
# instrumentation runner. Keep it in this validation-only APK; production R8
# rules remain unchanged.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }

# The instrumentation smoke explicitly drains native engines before finishing.
-keepclassmembers class dev.sebastian.vozlocal.data.repository.DictationRepository {
    public java.lang.Object shutdown(...);
}
