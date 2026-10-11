# Native libraries call back into these classes by name.
-keep class com.google.mediapipe.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }

# Optional parts of these libraries that the app never uses.
-dontwarn com.google.mediapipe.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
