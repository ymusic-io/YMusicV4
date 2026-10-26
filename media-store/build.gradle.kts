@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :kmpFfmpeg) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
    // SQLDelight codegen (typesafe .sq -> Kotlin). Version comes from the buildSrc classpath.
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("MediaStoreDatabase") {
            packageName.set("io.ymusic.mediastore.db")
            // .sq lives in src/commonMain/sqldelight — one shared schema for every platform.
        }
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "io.ymusic.mediastore"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Full native matrix (host-gated), matching :kmpHttp:core / :kmpFfmpeg. SQLDelight's native
    // driver (sqliter) covers every one of these: apple (ios/macos/tvos/watchos) + linux.
    withAppleTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * commonMain  (public API + models + impl + SQLDelight-generated DB)
         * ├── jvmMain      → JdbcSqliteDriver
         * ├── androidMain  → AndroidSqliteDriver
         * └── nativeMain   → NativeSqliteDriver (apple + linux)
         */
        val commonMain by getting {
            dependencies {
                // api: both appear in the public API surface — Uri in BetterMediaStore/model
                // signatures, and SqlDriver in BetterMediaStore.create + the driver builders — so
                // consumers (e.g. :media-scanner) must see them on their compile classpath.
                api(project(":kmpCommon"))
                api(Libs.SqlDelight.runtime)
                implementation(Libs.Kotlinx.coroutinesCore)
                implementation(Libs.SqlDelight.coroutinesExtensions)
            }
        }

        jvmMain.dependencies {
            implementation(Libs.SqlDelight.sqliteDriver)
        }

        androidMain.dependencies {
            implementation(Libs.SqlDelight.androidDriver)
        }

        // Null-safe on hosts with no native target configured (e.g. Windows).
        findByName("nativeMain")?.dependencies {
            // api (not implementation): the sqliter cinterop carries the `-lsqlite3` linker flag,
            // which must reach any native binary that links this module (apps + :media-scanner's
            // test binary) — a transitive `implementation` drops it and the link fails.
            api(Libs.SqlDelight.nativeDriver)
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
            }
        }

        // Host test drivers (in-memory): JVM uses the JDBC driver; native uses sqliter in-memory.
        jvmTest.dependencies {
            implementation(Libs.SqlDelight.sqliteDriver)
        }
        findByName("nativeTest")?.dependencies {
            implementation(Libs.SqlDelight.nativeDriver)
        }
    }
}
