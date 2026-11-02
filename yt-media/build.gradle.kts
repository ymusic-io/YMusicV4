@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // SegmentIndex indexes segment metadata in SQLite (the byte spans stay in MediaBlobCache on the FS).
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("SegmentDatabase") {
            packageName.set("io.ymusic.ytmedia.db")
        }
    }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.ytmedia"
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
            // api: okio Source/Sink/Buffer are the module's byte API (UMP streams, proto payloads).
            api(Libs.Okio.core)
            // api: SabrSession's public constructor takes a KmpHttpClient (the app's standard client).
            api(project(":kmpHttp:core"))
            implementation(Libs.Kotlinx.coroutinesCore)
            // MediaBlobCache guards its in-memory span index with a SynchronizedObject so the (non-suspend)
            // PinnedSource.close() can unpin without runBlocking.
            implementation(Libs.Kotlinx.atomicfu)
            implementation(project(":projectCommon"))
            // api: SqlDelightSegmentIndex's constructor takes a SqlDriver (the app builds the platform one).
            api(Libs.SqlDelight.runtime)
        }

        // Platform SqlDelight drivers (jvm = JDBC, android = AndroidSqliteDriver, apple/linux = sqliter),
        // same split as :dynamic-engine's DiskCacheStore. Null-safe on hosts with no native target.
        findByName("nativeMain")?.dependencies { implementation(Libs.SqlDelight.nativeDriver) }
        jvmMain.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
        androidMain.dependencies { implementation(Libs.SqlDelight.androidDriver) }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }

        // In-memory SQLite drivers for the SegmentIndex tests (JVM = JDBC, native = sqliter).
        jvmTest.dependencies { implementation(Libs.SqlDelight.sqliteDriver) }
        findByName("nativeTest")?.dependencies { implementation(Libs.SqlDelight.nativeDriver) }
    }
}
