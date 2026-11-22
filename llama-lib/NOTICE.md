# Vendored llama.cpp Android binding — NOTICE

The `llama-lib` module is vendored from the llama.cpp project:

- Source: `examples/llama.android/lib` in https://github.com/ggerganov/llama.cpp
- Pinned commit: `e300919cc704ddde3a8a8346a8714f57eeeee71b` (master, 2026-11-22)
- License: MIT (llama.cpp). The binding code is Arm's contribution to
  llama.cpp (the `com.arm.aichat` package); it is MIT-licensed like the
  rest of llama.cpp. The full llama.cpp LICENSE applies to the C++
  sources, which are NOT vendored here — they are cloned by CI at the
  same pinned commit (see `.github/workflows/android.yml`).

Files vendored (verbatim, except the two build files noted below):
- `src/main/cpp/ai_chat.cpp`, `src/main/cpp/logging.h`
- `src/main/java/com/arm/aichat/AiChat.kt`
- `src/main/java/com/arm/aichat/InferenceEngine.kt`
- `src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt`
- `src/main/AndroidManifest.xml`, `consumer-rules.pro`, `proguard-rules.pro`

Adapted (see the header comment in each file):
- `build.gradle.kts` — no version catalog in this repo (literal versions);
  minSdk lowered 33 -> 26 to match the app.
- `src/main/cpp/CMakeLists.txt` — `LLAMA_SRC` points at the CI-cloned
  `llama.cpp/` directory at the repo root instead of a llama.cpp checkout.

NOT vendored on purpose: the `gguf` metadata-reader package (used by
llama.cpp's example app, not by the engine). Model validation in Lia is
SHA-256 in `OfflineModelManager`, not GGUF parsing.

Why vendored instead of a Gradle dependency: there is no published AAR
for the llama.cpp Android binding — JitPack builds of llama.cpp all fail,
nothing exists on Maven Central or Google Maven, and llama.cpp releases
ship no Android binaries. Vendoring the small binding module + cloning
the C++ sources in CI is the only way to build it.
