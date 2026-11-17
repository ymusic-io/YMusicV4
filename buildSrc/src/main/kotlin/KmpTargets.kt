import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

private val KotlinProjectExtension.isHostCompatibleMode
    get() = project.findProperty("kmpTargets") == "host"

val hostOs: org.gradle.internal.os.OperatingSystem =
    org.gradle.internal.os.OperatingSystem.current()
val hostIsArm64 = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }

/** The desktop Kotlin/Native target the build host itself runs; null off macOS and Linux. */
val hostDesktopTargetName: String? = when {
    hostOs.isMacOsX -> if (hostIsArm64) "macosArm64" else "macosX64"
    hostOs.isLinux -> if (hostIsArm64) "linuxArm64" else "linuxX64"
    else -> null
}

fun KotlinMultiplatformExtension.withAppleTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
            macosArm64(),

            tvosArm64(),
            tvosSimulatorArm64(),

            watchosArm64(),
//            watchosArm32(),
            watchosDeviceArm64(),
            watchosSimulatorArm64(),
        ).forEach(configure)
    }

    if (hostOs.isMacOsX && !hostIsArm64 || !isHostCompatibleMode) {
        listOf(
            macosX64(),
            tvosX64(),
            watchosX64(),
        ).forEach(configure)
    }
}

fun KotlinMultiplatformExtension.withLinuxTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isLinux && hostIsArm64 || !isHostCompatibleMode) {
        linuxArm64().configure()
    }

    if (hostOs.isLinux && !hostIsArm64 || !isHostCompatibleMode) {
        linuxX64().configure()
    }
}

/**
 * iOS-only native targets: device + arm64 simulator, no tvOS/watchOS. There is no `iosX64`: the app's
 * iOS build links static ffmpeg, which exists only for arm64. An Intel host configures them only in
 * all-targets mode (`-PkmpTargets=all`, cross-compiling).
 */
fun KotlinMultiplatformExtension.withIosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach(configure)
    }
}

/** iOS targets for a Compose Multiplatform module; the same set as [withIosTargets]. */
fun KotlinMultiplatformExtension.withComposeIosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) = withIosTargets(configure)

/**
 * macOS-only native targets (host-gated). For modules that only need to run/build on macOS, e.g.
 * the benchmark module. Keeps the configured targets in sync with the host-gated library modules
 * they depend on.
 */
fun KotlinMultiplatformExtension.withMacosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        macosArm64().configure()
    }
    if (hostOs.isMacOsX && !hostIsArm64 || !isHostCompatibleMode) {
        macosX64().configure()
    }
}

/**
 * Desktop-native targets only: macOS + Linux, no iOS/tvOS/watchOS. Used by modules that wrap a
 * desktop-only native library (e.g. :kmpHttp:curl over libcurl). Host-gated the same way as the
 * other helpers so a given host only configures the native targets it can actually build — this is
 * what keeps macOS from trying to generate the Linux cinterop (and vice-versa).
 */
fun KotlinMultiplatformExtension.withDesktopTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    withMacosTargets(configure)
    withLinuxTargets(configure)
}

fun KotlinMultiplatformExtension.kmpHttpCommonConfig() {
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
}
