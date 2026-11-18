import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
}

val libcurl = HostNativeLibrary(
    displayName = "libcurl",
    pkgConfigModule = "libcurl",
    // Keg-only, so pkg-config misses it unless PKG_CONFIG_PATH names it.
    brewFormula = "curl",
    probeHeader = "curl/curl.h",
    libs = listOf("curl"),
    overrideProperty = "curlPrefix",
    overrideEnv = "CURL_PREFIX",
    installHint = "macOS: brew install curl; Debian/Ubuntu: apt install pkg-config libcurl4-openssl-dev; " +
        "Fedora: dnf install pkgconf libcurl-devel",
)

val openssl = HostNativeLibrary(
    displayName = "OpenSSL",
    pkgConfigModule = "openssl",
    brewFormula = "openssl",
    probeHeader = "openssl/ssl.h",
    libs = listOf("ssl", "crypto"),
    overrideProperty = "opensslPrefix",
    overrideEnv = "OPENSSL_PREFIX",
    installHint = "macOS: brew install curl, which brings the OpenSSL it links; " +
        "Debian/Ubuntu: apt install pkg-config libssl-dev; Fedora: dnf install pkgconf openssl-devel",
)

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    val interopDir = project.file("src/nativeInterop/cinterop")

    // Desktop-native targets (macOS + Linux), host-gated so each host only configures what it can
    // build. Matches :kmpHttp:core's desktop targets, which depend on this module.
    withDesktopTargets()

    // Headers and libs come from the build host's libcurl + OpenSSL, located per target when the def
    // is generated (no vendored files). The openssl cinterop binds a tiny slice of curl's TLS backend,
    // used to merge custom CAs into the default trust store.
    targets.withType<KotlinNativeTarget>().configureEach {
        val curlDef = hostLibraryDef(name, "libcurl", interopDir.resolve("libcurl.def"), libcurl)
        val opensslDef = hostLibraryDef(name, "openssl", interopDir.resolve("openssl.def"), openssl, linkingDef = curlDef)
        compilations.getByName("main").cinterops {
            create("libcurl") {
                definitionFile.set(curlDef)
                packageName("libcurl")
            }
            create("openssl") {
                definitionFile.set(opensslDef)
                packageName("openssl")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting

        // Desktop source set shared by whichever desktop targets exist on this host.
        val desktopMain by creating {
            dependsOn(commonMain)
        }
        listOf("linuxX64Main", "linuxArm64Main", "macosX64Main", "macosArm64Main").forEach { name ->
            findByName(name)?.dependsOn(desktopMain)
        }

        commonMain.dependencies {
            // :curl is a standalone Kotlin wrapper over libcurl (CurlMultiApi/EasyHandle).
            // It does NOT depend on :kmpHttp:core — the HTTP engine lives in core's desktopMain.
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(Libs.Okio.core)
            implementation(project(":kmpCommon"))
        }
    }
}
