import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.goldai.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.goldai.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // V18 diet: arm64-v8a only — the user's phone (and nearly every
        // modern Android phone) is arm64. Shipping x86_64/x86/armeabi-v7a
        // tripled the native-library size for phones nobody uses here.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val props = Properties()
                keystoreFile.inputStream().use { props.load(it) }
                val releaseSigning = signingConfigs.create("release") {
                    storeFile = rootProject.file(props.getProperty("storeFile", "release.keystore"))
                    storePassword = props.getProperty("storePassword", "")
                    keyAlias = props.getProperty("keyAlias", "")
                    keyPassword = props.getProperty("keyPassword", "")
                    // Gold AI release keystore is PKCS#12 (generated with
                    // openssl, see play-store/SIGNING.md). If a JKS keystore
                    // is ever used instead, set storeType=JKS in
                    // keystore.properties.
                    storeType = props.getProperty("storeType", "PKCS12")
                }
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            // V18 diet: the workflow builds directDebug, so the diet
            // applies here — R8 + resource shrinking (same rules file
            // as release; proguard-rules.pro already keeps the JNI
            // bridges, Compose/data classes and reflection entry
            // points). If R8 ever breaks the debug build, turn these
            // two flags off and say so in the build report.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Explicit: AdManager checks BuildConfig.DEBUG to keep ads
        // off in debug builds.
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // V14: background watchdog (WorkManager) + on-device wake word (Vosk).
    // Vosk is Apache-2.0, offline, free, no key. The voice model (~40 MB)
    // is NOT bundled — it downloads on first enable (WakeWordModelManager).
    // NOTE: the vosk-android version pin is verified by the first GitHub
    // Actions build on this branch; if it fails to resolve, bump it to
    // the current release on Maven Central.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.alphacephei:vosk-android:0.3.45") {
        // V21 fix, round 2: vosk-android pulls in the plain JNA jar
        // transitively, which duplicates every class in the JNA AAR
        // below (126 duplicate-class errors). Exclude it here so JNA
        // comes ONLY from the AAR.
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // V21 fix: Vosk's Model() needs JNA's android-aarch64
    // libjnidispatch.so; the plain JNA jar vosk-android pulls in does
    // not put it in the minified APK, so the wake-word spotter crashed
    // with UnsatisfiedLinkError. The JNA AAR ships the native lib
    // (5.14.0, close to vosk's transitive 5.13.0).
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // V15: Keystore-backed encrypted credential storage (security-crypto
    // 1.1.0, stable July 2025 — verified on Google Maven 2026-11-22).
    implementation("androidx.security:security-crypto:1.1.0")
    // V19: on-device voice engine — sherpa-onnx 2.0.3 (Apache-2.0),
    // restored from V17 for Pocket TTS only. It is NOT on Maven
    // Central, so there is no Maven coordinate to pin: CI downloads
    // the release tarball + JVM jar (SHA-256 checked, see
    // .github/workflows/android.yml) into app/libs + jniLibs. A local
    // build without that step fails on the missing jar file — build on
    // GitHub Actions, like V15's first builds. (No TensorFlow Lite:
    // memory embeddings stay out in V19.)
    implementation(files("libs/sherpa-onnx-jvm-2.0.3.jar"))
    // V18 diet: the offline LLM (llama-lib) and the on-device voice
    // engine (sherpa-onnx jar + TensorFlow Lite) were removed. The
    // voice stack is now Vosk wake word + system SpeechRecognizer /
    // system TTS + the online Kokoro voice (OkHttp, already here) +
    // (V19) Pocket TTS via sherpa-onnx above.

    // V22 monetization: Google Mobile Ads (AdMob) — banner +
    // interstitial only. Test ad unit IDs only, see
    // monetization/AdConfig.kt. Ads are OFF in debug builds
    // (AdManager checks BuildConfig.DEBUG). No other ad/tracking
    // SDK is added — F-Droid forbids proprietary ad libraries, so
    // that door stays open. (The Gold Premium subscription and the
    // rewarded ad were removed on the user's order 2026-11-24 —
    // ads-only monetization; the in-app-purchase dependency went
    // with them, so there is no subscription code anywhere now.)
    implementation("com.google.android.gms:play-services-ads:23.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
