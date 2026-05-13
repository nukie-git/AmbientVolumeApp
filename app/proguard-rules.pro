# 1. Protect the Volume Engine & Foreground Service
# This prevents R8 from renaming or removing your background logic
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keepclassmembers class * extends android.app.Service {
    public <init>(...);
}
-keep class com.nukie.ambientvolume.service.** { *; }

# 2. Protect Bluetooth & Audio Management
# Critical for your Bluetooth volume adjustment logic
-keep class android.media.AudioManager { *; }
-keep class android.bluetooth.** { *; }

# 3. Protect Jetpack Compose & Material You
# Essential for your dashboard and theme toggles
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.ui.platform.** { *; }
-keep interface androidx.compose.runtime.** { *; }
-keepattributes Signature, AnnotationDefault, EnclosingMethod, InnerClasses

# 4. Protect DataStore (Persistence)
# Ensures your 'Profiles' and 'Theme Settings' survive minification
-keep class androidx.datastore.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn androidx.datastore.**

# 5. Protect OEM Intents & Build Config
# Critical for the Persistence Assistant buttons (Xiaomi, Doogee, etc.)
-keep class **.BuildConfig { *; }
-dontwarn com.mediatek.duraspeed.**

# 6. Optimization Settings
# Allow the compiler to be aggressive with everything else to keep APK small
-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively

# General optimization rules
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
