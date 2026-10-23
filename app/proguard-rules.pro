# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Api21Support is loaded by class name only on Android 5.0 and newer.
-keep class com.gathin.tvhome.Api21Support { *; }

# QR generation for the upload-by-QR wallpaper screen (bundled zxing core).
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# AirPlay native bridge: keep JNI-called class + native methods.
-keep class com.gathin.tvhome.AirPlayReceiver { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.gathin.tvhome.DlnaPlaybackActivity { *; }

# libVLC: keep all classes/interfaces (R8 was stripping inner interfaces -> ClassNotFound on 4.4)
-keep class com.gathin.tvhome.DlnaPlayer { *; }
-keep class com.gathin.tvhome.DlnaPlaybackActivity { *; }

# HomeKit (hap-java) + BouncyCastle + Netty: keep everything (reflection, JNI-free but uses provider lookups)
-keep class org.bouncycastle.** { *; }
-keep class io.netty.** { *; }
-keep class javax.jmdns.** { *; }
-keep class com.beowulfe.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn io.netty.**
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**
-keep class com.gathin.tvhome.HomeKit** { *; }
-keep class com.gathin.tvhome.RemoteKeys { *; }

# dadb (loopback ADB client) + okio + kotlin
-keep class dadb.** { *; }
-keep class okio.** { *; }
-keep class kotlin.** { *; }
-dontwarn dadb.**
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.**
-keep class com.gathin.tvhome.AdbRemote { *; }

-keep class com.gathin.tvhome.hk.** { *; }
