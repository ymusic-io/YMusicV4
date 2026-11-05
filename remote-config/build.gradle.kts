@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :settings / :media-store) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.remoteconfig"
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    withIosTargets()
    withMacosTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    // okio-fakefilesystem is built against kotlinx-datetime 0.7.1-0.6.x-compat while :kmpHttp:core
    // pulls 0.8.0; mixing them in one native test link kills the K/N linker ("IrTypeAliasSymbolImpl
    // is already bound: kotlinx.datetime/Clock"). Pin the TEST graph to the version fakefilesystem
    // was built against — main configurations are untouched.
    configurations.matching { it.name.contains("Test") }.configureEach {
        resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
    }

    sourceSets {
        commonMain.dependencies {
            api(Libs.Kotlinx.coroutinesCore)
            // api: the caller supplies the KmpHttpClient the config is fetched with, and the okio
            // FileSystem/Path the cache is streamed to.
            api(project(":kmpHttp:core"))
            api(Libs.Okio.core)
            implementation(project(":kmpCommon")) // Logger, BufferedAsyncSource
            implementation(project(":projectCommon"))
            implementation(project(":kmpHttp:json")) // JsonReader — no @Serializable anywhere
            implementation(Libs.Kotlinx.atomicfu) // SynchronizedObject for the cookie store
        }

        // RemoteConfig's policy tests fake ApiService and run everywhere.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
            implementation(Libs.Okio.fakeFileSystem)
        }

        // HttpApiService's tests stay here: faking the http client needs a Response with a body,
        // and ResponseBody is an expect class whose only common factory is EMPTY — one can be
        // built on the JVM (okhttp) and nowhere else.
        jvmTest.dependencies {
            implementation(Libs.OkHttp.core)
            implementation(project(":kmpHttp:testing")) // InMemoryCookieJar for the faked client
        }
    }
}
