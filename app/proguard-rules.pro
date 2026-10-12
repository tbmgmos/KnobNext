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

# ML Kit GenAI (Gemini Nano) talks to the system's AICore service through classes it looks up by
# name; shrinking them broke it (a NullPointerException inside the library).
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai** { *; }
-keep class com.google.android.apps.aicore.** { *; }
-keep interface com.google.android.apps.aicore.** { *; }
-keep class com.google.android.aicore.** { *; }
-keep interface com.google.android.aicore.** { *; }
-dontwarn com.google.mlkit.genai.**
-dontwarn com.google.android.apps.aicore.**
-dontwarn com.google.android.aicore.**
