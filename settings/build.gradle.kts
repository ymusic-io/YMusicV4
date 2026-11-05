@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :media-store / :player) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.settings"
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    withIosTargets()
    withMacosTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(Libs.Kotlinx.coroutinesCore)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
