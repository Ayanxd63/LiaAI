package com.goldai.assistant.voice

import android.os.Build
import android.util.Log

/**
 * V19 (restored from V17) — the on-device voice runtime gate
 * (sherpa-onnx, Apache-2.0).
 *
 * sherpa-onnx ships native libraries for arm64-v8a and x86_64 only
 * (the CI step extracts exactly those two ABIs into
 * app/src/main/jniLibs). On any other ABI — 32-bit ARM phones, for
 * example — the engine simply does not exist, and every V17 feature
 * degrades gracefully to what the phone already had (system TTS,
 * Android SpeechRecognizer, Vosk wake word). Nothing in V17 ever
 * assumes the native library is present: callers check
 * [isAvailable] first.
 *
 * The native library is loaded with [System.loadLibrary] directly.
 * sherpa's own `LibraryLoader.maybeLoad()` is package-private, so it
 * cannot be called from here — but every sherpa constructor calls it
 * internally, so a successful probe here means the constructors will
 * load too.
 *
 * Size budget (measured from the v2.0.3 release tarball): the two
 * ABIs in the tarball are arm64 ~31.5 MB + x86_64 ~37.4 MB of .so
 * files (libonnxruntime + libsherpa-onnx-jni + libsherpa-onnx-c-api
 * + libsherpa-onnx-cxx-api). V19 keeps the V18 arm64-only filter, so
 * ONLY arm64 ships — about +31.5 MB on the ~19 MB V18 APK (~51 MB
 * total). The ~191 MB Pocket TTS model itself is NEVER in the APK;
 * it downloads on-demand on Wi-Fi.
 *
 * V19: only Pocket TTS uses this runtime now (the other V17 engines
 * were removed in the V18 diet and stay out).
 */
object SherpaRuntime {

    private const val TAG = "SherpaRuntime"

    /** ABIs the sherpa-onnx release ships native libraries for. */
    val SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    @Volatile
    private var nativeLoadAttempted = false

    @Volatile
    private var nativeLoaded = false

    /** True when this phone's CPU can run the sherpa native libs. */
    fun isSupportedDevice(): Boolean =
        Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    /**
     * True when the native library loaded. The probe runs once and is
     * cached; a failure (missing .so, wrong ABI) is a normal "not
     * available", never a crash.
     */
    fun isAvailable(): Boolean {
        if (!isSupportedDevice()) return false
        if (nativeLoadAttempted) return nativeLoaded
        nativeLoadAttempted = true
        nativeLoaded = runCatching {
            System.loadLibrary("sherpa-onnx-jni")
            true
        }.onFailure { e ->
            Log.w(TAG, "sherpa native lib not loadable on this phone", e)
        }.getOrDefault(false)
        return nativeLoaded
    }

    /** Plain-language reason when [isAvailable] is false, else null. */
    fun unavailableReason(): String? {
        if (!isSupportedDevice()) {
            return "Ye phone ka processor on-device voice support nahi karta " +
                "(sirf arm64 / x86_64). System voice use hogi."
        }
        if (!isAvailable()) {
            return "On-device voice engine load nahi ho saka — app dobara " +
                "install kar ke try karo."
        }
        return null
    }
}
