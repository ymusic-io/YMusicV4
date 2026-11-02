import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "io.ymusic.ui.resources"
    // The shells reuse the brand assets (desktop window icon).
    publicResClass = true
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.ui"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Shared Compose UI for iOS — arm64 device + arm64 simulator only (Compose MP has no iosX64).
    // On this Intel host this compiles only under -PkmpTargets=all (cross-compiled for a device).
    // :iosAppKotlin (the iOS shell / composition root) produces the framework the Xcode app embeds.
    withComposeIosTargets()

    sourceSets {
        /**
         * The shared Compose Multiplatform app UI (design/: drawer + Discover tabs + search +
         * now-playing + persistent mini-player). Pure common — the platform shells (:desktopApp,
         * :androidApp) build the AppGraph and inject the platform PlayerSurface composable; nothing
         * platform-specific lives here. iOS reuses this verbatim when its shell lands.
         */
        val commonMain by getting {
            dependencies {
                api(project(":player"))
                api(project(":dynamic-engine"))
                api(project(":media-store"))
                api(project(":media-scanner"))
                api(project(":image-loader"))
                api(project(":settings"))
                implementation(project(":projectCommon"))
                api(project(":remote-config")) // StatUsage rides AppGraph

                api(project(":kmpFfmpeg"))
                api(project(":download"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(Libs.Kotlinx.coroutinesCore)
                implementation(Libs.Kotlinx.atomicfu) // SynchronizedObject for GlobalShortcutActions
                implementation(Libs.Kotlinx.dateTime)
                implementation(project(":kmpHttp:core"))
                implementation(project(":kmpHttp:json"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(Libs.Zxing.core)
                implementation(Libs.SqlDelight.sqliteDriver)
            }
        }

        // The system back bridge (PlatformBackHandler) — the only Android-specific dependency.
        val androidMain by getting {
            dependencies {
                implementation(Libs.AndroidX.activityCompose)
                implementation(Libs.SqlDelight.androidDriver)
            }
        }

        // Lazy: iosMain only exists once the (host-gated) ios targets are configured.
        sourceSets.matching { it.name == "iosMain" }.configureEach {
            dependencies {
                api(Libs.SqlDelight.nativeDriver)
            }
        }

        /**
         * Headless Compose UI tests (no window, no input automation): screens mount over a real
         * DynamicEngineManager driven by a canned HTTP client, so a test exercises Kotlin UI →
         * engine JS → parser → UI end to end on this desktop JVM.
         */
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
                implementation(project(":kmpHttp:testing")) // InMemoryCookieJar for the faked client
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
