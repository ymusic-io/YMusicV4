import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.imageloader"
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Same iOS gating as :commonUi (Compose MP has no x86_64-Apple targets).
    withComposeIosTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * commonMain  (loader + LIFO queue + caches + AsyncImage composable, expect codec)
         * ├── skikoMain    → Skia decode/encode (desktop + iOS share Compose's Skia backend)
         * │   ├── jvmMain
         * │   └── iosMain
         * └── androidMain  → BitmapFactory subsampled decode
         */
        val commonMain by getting {
            dependencies {
                // api: KmpHttpClient appears in the ImageLoader constructor.
                api(project(":kmpHttp:core"))
                api(project(":kmpCommon"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(Libs.Kotlinx.coroutinesCore)
                implementation(Libs.Kotlinx.atomicfu)
                implementation(Libs.Okio.core)
            }
        }

        val skikoMain by creating {
            dependsOn(commonMain)
        }
        jvmMain.get().dependsOn(skikoMain)
        findByName("iosMain")?.dependsOn(skikoMain)

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
                implementation(Libs.Okio.fakeFileSystem)
            }
        }

        // Skia's native runtime for the host, so the decode actuals run in a bare test JVM
        // (an app gets it from its own compose desktop dependency).
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
