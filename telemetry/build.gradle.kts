@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.telemetry"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // No Apple targets yet: sentry-kotlin-multiplatform needs the Sentry cocoa framework linked
    // into the Xcode shell — the iOS shell keeps its plain print logger until that lands.

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmpCommon"))
            implementation(project(":projectCommon"))
            implementation(project(":log-store")) // installErrorLogs reads the one app log
            implementation(project(":remote-config")) // errorLogLines, once the config has loaded
            api(Libs.Sentry.kmp)
            implementation(Libs.Kotlinx.coroutinesCore) // bindCrashReporting collects the consent flow
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Both targets run sentry-java underneath: the event hooks and timed breadcrumbs go through
        // it directly, since the KMP wrapper's beforeSend round-trip re-stamps every breadcrumb.
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)
    }
}
