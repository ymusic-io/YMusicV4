@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // The download queue is persisted so tasks survive a restart (SqlDelight, same as :yt-media).
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("DownloadDatabase") {
            packageName.set("io.ymusic.download.db")
        }
    }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.download"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    withIosTargets()
    withMacosTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(Libs.Okio.core)
            // api: SabrSession/SabrStreamSpec/AdaptiveFormat/DownloadFile/MediaCache are the download inputs.
            api(project(":yt-media"))
            api(project(":kmpHttp:core"))
            api(project(":kmpCommon"))
            // ffmpeg (non-apple): convert best-audio to mp3, mux audio+video into one container.
            implementation(project(":kmpFfmpeg"))
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(project(":projectCommon"))
            api(Libs.SqlDelight.runtime)
        }

        // Null-safe on hosts with no native target configured (e.g. Windows).
        findByName("nativeMain")?.dependencies { implementation(Libs.SqlDelight.nativeDriver) }
        jvmMain.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
        androidMain.dependencies { implementation(Libs.SqlDelight.androidDriver) }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }

        jvmTest.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
        findByName("nativeTest")?.dependencies { implementation(Libs.SqlDelight.nativeDriver) }
    }
}
