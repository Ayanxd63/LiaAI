// V15 — vendored from llama.cpp examples/llama.android/lib at pinned
// commit e300919cc704ddde3a8a8346a8714f57eeeee71b (MIT, see NOTICE.md).
// The example ships as a source module inside the llama.cpp repo — there
// is no published AAR (JitPack builds all fail, nothing on Maven
// Central), so the module lives here and the llama.cpp C++ sources are
// cloned by CI (see .github/workflows/android.yml) before the build.
//
// Adaptations vs the upstream module:
// - No version catalog in this repo: literal plugin ids + versions.
// - minSdk lowered 33 -> 26 to match the app (the code uses only
//   datastore + coroutines + JNI; the first GitHub Actions build
//   verifies this).
// - The gguf metadata-reader package is NOT vendored: the engine does
//   not use it (the example app does); model validation is SHA-256 in
//   OfflineModelManager instead.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36

    // The NDK is downloaded automatically by AGP when it is missing on
    // the builder (GitHub Actions runner).
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
