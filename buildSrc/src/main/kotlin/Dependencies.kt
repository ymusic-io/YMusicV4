object Versions {
    const val kotlin = "2.4.0"
    const val agp = "9.2.1"
    // 36: okhttp-android 5.4 (selected for Android consumers of :kmpHttp via Gradle metadata)
    // requires it of every module that compiles against it, not just the app.
    const val compileSdk = 36
    const val minSdk = 24

    /** The oldest macOS the desktop app runs on: Compose Desktop's floor and the first Apple-silicon release.
     *  Every macOS native passes it as its deployment target — without one, a dylib records the build
     *  host's OS as its minimum and will not load on anything older. */
    const val macosMin = "11.0"

    /** The scrape bundle's (engine.js) self-declared revision — BUMP MANUALLY on every remote push of
     *  it. Feeds its inlined BACKEND_VERSION, its embedded-const version and each shell's
     *  ENGINE_JS_VERSION. A remote engine.js applies only above the embedded revision. */
    const val engineJsVersion = 85L

    /** The MSE player bundle's (player.js) revision — independent of [engineJsVersion]; BUMP
     *  MANUALLY on every remote push of it. Feeds its inlined BACKEND_VERSION and PLAYER_JS_VERSION. */
    const val playerJsVersion = 97L
    const val targetSdk = 35

    // KMP
    const val coroutines = "1.11.0"
    const val atomicfu = "0.23.1" // aligned with coroutines 1.11.0
    const val kotlinxSerialization = "1.9.0"
    const val kotlinxDateTime = "0.8.0"
    const val composeMultiplatform = "1.11.1"
    const val navigationEvent = "1.0.1" // the androidx.navigationevent composeMultiplatform ships with
    const val okhttp = "5.4.0"
    const val okio = "3.17.0"
    const val sqldelight = "2.3.2"

    // :player local-file backends (per platform; Apple uses AVFoundation cinterop, no dep)
    const val media3 = "1.11.0"   // Android ExoPlayer
    const val jna = "5.17.0"     // JVM desktop native interop (macOS Now Playing bridge)

    // AndroidX
    const val annotation = "1.9.1"
    const val coreKtx = "1.15.0"
    const val lifecycleRuntimeKtx = "2.8.7"
    const val activityCompose = "1.9.3"
    const val cameraX = "1.4.2"
    const val composeBom = "2024.12.01"

    // JS toolchain (:engine-js, run via npx — Node required at build)
    const val esbuild = "0.25.12"
    const val typescript = "5.9.3"

    // Crash reporting (wraps sentry-java on Android/JVM, sentry-cocoa on Apple)
    const val sentry = "0.27.0"

    // Benchmark
    const val kotlinxBenchmark = "0.4.17"

    // Testing
    const val junit = "4.13.2"
    const val junitExt = "1.3.0"
    const val testRunner = "1.7.0"
    const val espresso = "3.6.1"
}

object Libs {
    object Kotlinx {
        const val coroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}"
        const val atomicfu = "org.jetbrains.kotlinx:atomicfu:${Versions.atomicfu}"
        const val coroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}"
        const val coroutinesSwing = "org.jetbrains.kotlinx:kotlinx-coroutines-swing:${Versions.coroutines}"
        const val coroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}"
        const val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinxSerialization}"
        const val serializationJsonOkio = "org.jetbrains.kotlinx:kotlinx-serialization-json-okio:${Versions.kotlinxSerialization}"
        const val dateTime = "org.jetbrains.kotlinx:kotlinx-datetime:${Versions.kotlinxDateTime}"
    }

    object AndroidX {
        const val annotation = "androidx.annotation:annotation:${Versions.annotation}"
        const val coreKtx = "androidx.core:core-ktx:${Versions.coreKtx}"
        const val lifecycleRuntimeKtx = "androidx.lifecycle:lifecycle-runtime-ktx:${Versions.lifecycleRuntimeKtx}"
        const val activityCompose = "androidx.activity:activity-compose:${Versions.activityCompose}"
        const val biometric = "androidx.biometric:biometric:1.1.0"
        const val palette = "androidx.palette:palette:1.0.0"

        // biometric 1.1.0 drags in fragment 1.2.5, whose FragmentActivity rejects the request codes
        // registerForActivityResult issues ("Can only use lower 16 bits for requestCode").
        const val fragment = "androidx.fragment:fragment:1.8.5"
        const val cameraCore = "androidx.camera:camera-core:${Versions.cameraX}"
        const val cameraCamera2 = "androidx.camera:camera-camera2:${Versions.cameraX}"
        const val cameraLifecycle = "androidx.camera:camera-lifecycle:${Versions.cameraX}"
        const val cameraView = "androidx.camera:camera-view:${Versions.cameraX}"

        object Compose {
            const val bom = "androidx.compose:compose-bom:${Versions.composeBom}"
            const val ui = "androidx.compose.ui:ui"
            const val uiGraphics = "androidx.compose.ui:ui-graphics"
            const val uiToolingPreview = "androidx.compose.ui:ui-tooling-preview"
            const val material3 = "androidx.compose.material3:material3"
            const val uiTooling = "androidx.compose.ui:ui-tooling"
            const val uiTestManifest = "androidx.compose.ui:ui-test-manifest"
            const val uiTestJunit4 = "androidx.compose.ui:ui-test-junit4"
        }
    }

    object Testing {
        const val junit = "junit:junit:${Versions.junit}"
        const val junitExt = "androidx.test.ext:junit:${Versions.junitExt}"
        const val runner = "androidx.test:runner:${Versions.testRunner}"
        const val espresso = "androidx.test.espresso:espresso-core:${Versions.espresso}"
    }

    object OkHttp {
        const val core = "com.squareup.okhttp3:okhttp:${Versions.okhttp}"
    }

    object Okio {
        const val core = "com.squareup.okio:okio:${Versions.okio}"
        const val fakeFileSystem = "com.squareup.okio:okio-fakefilesystem:${Versions.okio}"
    }

    object SqlDelight {
        const val runtime = "app.cash.sqldelight:runtime:${Versions.sqldelight}"
        const val coroutinesExtensions = "app.cash.sqldelight:coroutines-extensions:${Versions.sqldelight}"
        const val sqliteDriver = "app.cash.sqldelight:sqlite-driver:${Versions.sqldelight}"     // JVM (JDBC)
        const val androidDriver = "app.cash.sqldelight:android-driver:${Versions.sqldelight}"
        const val nativeDriver = "app.cash.sqldelight:native-driver:${Versions.sqldelight}"     // sqliter
    }

    object Doist {
        const val normalizer = "com.doist.x:normalize:1.3.3"
    }

    object Benchmark {
        const val runtime = "org.jetbrains.kotlinx:kotlinx-benchmark-runtime:${Versions.kotlinxBenchmark}"
    }

    object Moshi {
        const val core = "com.squareup.moshi:moshi:1.15.2"
        const val kotlin = "com.squareup.moshi:moshi-kotlin:1.15.2"
        const val okio = "com.squareup.moshi:moshi-adapters:1.15.2"
    }

    // :player local-file backend deps. Apple (iOS/macOS) needs none — AVFoundation is a cinterop
    // platform lib.
    object Media3 {
        const val exoplayer = "androidx.media3:media3-exoplayer:${Versions.media3}"
        const val extractor = "androidx.media3:media3-extractor:${Versions.media3}"
        const val datasource = "androidx.media3:media3-datasource:${Versions.media3}"
        const val common = "androidx.media3:media3-common:${Versions.media3}"
        const val ui = "androidx.media3:media3-ui:${Versions.media3}"
        const val session = "androidx.media3:media3-session:${Versions.media3}"
    }

    object Jna {
        const val core = "net.java.dev.jna:jna:${Versions.jna}"
    }

    object NavigationEvent {
        const val compose = "org.jetbrains.androidx.navigationevent:navigationevent-compose:${Versions.navigationEvent}"
    }

    object Sentry {
        const val kmp = "io.sentry:sentry-kotlin-multiplatform:${Versions.sentry}"
    }

    object Zxing {
        const val core = "com.google.zxing:core:3.5.3"
    }

    // java.time on API < 26, which kotlinx-datetime's TimeZone needs.
    const val desugarJdkLibs = "com.android.tools:desugar_jdk_libs:2.1.5"
}
