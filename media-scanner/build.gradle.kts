@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "io.ymusic.mediascanner"
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // The store's sqliter driver is used transitively (via :media-store) rather than declared in this
    // module's nativeMain, so the `-lsqlite3` linker flag its cinterop carries doesn't reach our native
    // binaries automatically. State it explicitly so the test binaries (which build a real store) link.
    withAppleTargets { binaries.all { linkerOpts("-lsqlite3") } }
    withLinuxTargets { binaries.all { linkerOpts("-lsqlite3") } }

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * commonMain  (scan engine + DocumentsProvider/DocumentInfo + okio FileSystem provider +
         *              ffmpeg metadata extractor + no-op stubs — all cross-platform)
         * ├── androidMain  (SafDocumentsProvider — Storage Access Framework)
         * └── nativeMain   (nothing extra; the engine + okio provider are common)
         *
         * The scanner is a no-op on iOS/tvOS/watchOS not by source-set gating but by wiring: those
         * apps construct a NoopDocumentsProvider + NoopMetadataExtractor (kmpFfmpeg can't run there).
         */
        val commonMain by getting {
            dependencies {
                // api: these types surface in the public API (BetterMediaStore/MediaItemDraft in the
                // MediaScanner ctor, Uri in DocumentInfo, FileSystem in FileSystemDocumentsProvider,
                // FfmpegMetadataGetter in FfmpegMetadataExtractor).
                api(project(":media-store"))
                api(project(":kmpFfmpeg"))
                api(project(":kmpCommon"))
                api(Libs.Okio.core)
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
                implementation(Libs.Okio.fakeFileSystem) // in-memory FileSystem for the engine tests
            }
        }
    }
}
