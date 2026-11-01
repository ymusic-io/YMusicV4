@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("LogDatabase") {
            packageName.set("io.ymusic.logstore.db")
        }
    }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.logstore"
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
            api(project(":kmpCommon"))
            // api: LogStore's constructor takes a SqlDriver (the app builds the platform one).
            api(Libs.SqlDelight.runtime)
            // api: exportGzip writes to an okio FileSystem/Path.
            api(Libs.Okio.core)
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(Libs.Kotlinx.dateTime) // export: local wall time per line
        }

        findByName("nativeMain")?.dependencies { implementation(Libs.SqlDelight.nativeDriver) }
        jvmMain.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
        androidMain.dependencies { implementation(Libs.SqlDelight.androidDriver) }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
        jvmTest.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
    }
}
