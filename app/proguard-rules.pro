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
