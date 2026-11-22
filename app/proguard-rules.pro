# Proguard rules for Lia AI

# Keep org.json
-keep class org.json.** { *; }

# V14: Vosk wake word (org.vosk) — native JNI bridge, keep everything.
-keep class org.vosk.** { *; }
-keep class com.alphacephei.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# V18 diet fix: R8 on directDebug failed with "Missing class
# com.google.errorprone.annotations.*" (CanIgnoreReturnValue,
# CheckReturnValue, Immutable, RestrictedApi), referenced from
# com.google.crypto.tink — Tink comes in via
# androidx.security:security-crypto (V15 encrypted credential storage).
# Error Prone annotations are compile-time only, so silencing the
# warning is safe; the annotations are never needed at runtime.
-dontwarn com.google.errorprone.annotations.**

# Keep DataStore Preferences
-keep class androidx.datastore.preferences.** { *; }

# Keep Android WebKit and AssetLoader
-keep class androidx.webkit.** { *; }

# Keep Compose runtime and model classes
-keep class com.Lia.assistant.data.** { *; }
-keep class com.Lia.assistant.voice.** { *; }
-keep class com.Lia.assistant.forge.** { *; }
-keep class com.Lia.assistant.agent.** { *; }
-keep class com.Lia.assistant.license.** { *; }

# Three.js / JS Interface bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
