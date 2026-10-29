@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val mseNativeSrc = projectDir.resolve("native")
val production = isProductionBuild()
val productionC = if (production) "1" else "0"

fun mseSdkPath(sdk: String): String =
    ProcessBuilder("xcrun", "--sdk", sdk, "--show-sdk-path").start()
        .inputStream.bufferedReader().readText().trim()

fun mseClangArch(targetName: String): List<String> = when (targetName) {
    "macosX64" -> listOf("-arch", "x86_64")
    "macosArm64" -> listOf("-arch", "arm64")
    "iosX64" -> listOf(
        "-arch", "x86_64", "-isysroot", mseSdkPath("iphonesimulator"),
        "-mios-simulator-version-min=12.0",
    )
    "iosSimulatorArm64" -> listOf(
        "-arch", "arm64", "-isysroot", mseSdkPath("iphonesimulator"),
        "-mios-simulator-version-min=12.0",
    )
    "iosArm64" -> listOf(
        "-arch", "arm64", "-isysroot", mseSdkPath("iphoneos"),
        "-miphoneos-version-min=12.0",
    )
    else -> emptyList()
}

fun mseLibDir(targetName: String) =
    layout.buildDirectory.dir("mse-native/$targetName").get().asFile

fun ffmpegIosPrefix(targetName: String): File? {
    val slug = when (targetName) {
        "iosArm64" -> "ios-arm64"
        "iosSimulatorArm64" -> "ios-simulator-arm64"
        else -> null
    } ?: return null
    val dir = rootProject.projectDir.parentFile.resolve("ffmpeg-build/out/$slug")
    return dir.takeIf { File(it, "lib/libavcodec.a").isFile }
}

fun registerMseNativeLib(targetName: String): TaskProvider<Exec> {
    val taskName = "buildMseHost${targetName.replaceFirstChar { it.uppercase() }}"
    tasks.findByName(taskName)?.let {
        @Suppress("UNCHECKED_CAST")
        return tasks.named(taskName) as TaskProvider<Exec>
    }
    val out = mseLibDir(targetName)
    val ffmpeg = ffmpegIosPrefix(targetName)
    val qjsInc = rootProject.projectDir.resolve("quickjs/native")
    val task = tasks.register<Exec>(taskName) {
        // `*.o` / `ar r` would otherwise pick up a previous variant's objects (stub vs ffmpeg).
        doFirst {
            out.deleteRecursively()
            out.mkdirs()
        }
        inputs.dir(mseNativeSrc)
        if (ffmpeg != null) {
            inputs.dir(File(ffmpeg, "include"))
            inputs.dir(qjsInc)
        }
        outputs.file(File(out, "libmsehost.a"))
        inputs.property("production", productionC)
        workingDir(out)
        val arch = mseClangArch(targetName).joinToString(" ")
        val cmd = if (ffmpeg != null) {
            val srcs = listOf(
                "mse_host.c", "mse_ffmpeg.c", "mse_vt.c", "mse_vt_blit.c", "audio_fx.c",
                "vpresent.c", "vpresent_cpu.c", "vpresent_vt.c", "vpipe.c", "vpipe_hw_vt.c",
            )
                .joinToString(" ") { "'${mseNativeSrc.resolve(it)}'" }
            val objc = listOf("mse_vt_layer.m", "mse_vt_dec.m")
                .joinToString(" ") { "'${mseNativeSrc.resolve(it)}'" }
            "set -e; cc $arch -O2 -w -DPRODUCTION=$productionC -c $srcs " +
                "-I'${mseNativeSrc.absolutePath}' -I'${qjsInc.absolutePath}' " +
                "-I'${File(ffmpeg, "include").absolutePath}' && " +
                "cc $arch -O2 -w -fobjc-arc -DPRODUCTION=$productionC -c $objc " +
                "-I'${mseNativeSrc.absolutePath}' && ar rcs libmsehost.a *.o"
        } else {
            "set -e; cc $arch -O2 -w -c '${mseNativeSrc.resolve("mse_stub.c")}' " +
                "-I'${mseNativeSrc.absolutePath}' && ar rcs libmsehost.a *.o"
        }
        commandLine("bash", "-c", cmd)
    }
    return task
}

val configureMseNative: KotlinNativeTarget.() -> Unit = {
    val targetName = name
    val libTask = registerMseNativeLib(targetName)
    val libDir = mseLibDir(targetName)
    val ffmpeg = ffmpegIosPrefix(targetName)
    compilations.getByName("main").cinterops.create("mse") {
        defFile(project.file("native/cinterop/mse.def"))
        includeDirs(mseNativeSrc)
        extraOpts("-libraryPath", libDir.absolutePath)
        if (ffmpeg != null) {
            // Baked into the klib, so :dynamic-engine / :playground do not repeat -L/-l/frameworks.
            linkerOpts("-L${File(ffmpeg, "lib").absolutePath}")
            linkerOpts("-lavformat", "-lavcodec", "-lswscale", "-lswresample", "-lavutil")
            val flib = File(ffmpeg, "lib")
            if (File(flib, "libdav1d.a").isFile) linkerOpts("-ldav1d")
            if (File(flib, "libvpx.a").isFile) linkerOpts("-lvpx")
            linkerOpts(
                "-framework", "AudioToolbox",
                "-framework", "CoreAudio",
                "-framework", "VideoToolbox",
                "-framework", "CoreMedia",
                "-framework", "CoreFoundation",
                "-framework", "CoreVideo",
                "-framework", "AVFoundation",
                "-framework", "QuartzCore",
                "-lz", "-lbz2", "-liconv",
            )
        }
    }
    tasks.named("cinteropMse${targetName.replaceFirstChar { it.uppercase() }}").configure {
        dependsOn(libTask)
        inputs.file(libDir.resolve("libmsehost.a"))
    }
}

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }
    sourceSets.all { languageSettings.optIn("kotlin.ExperimentalMultiplatform") }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.mse"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        packaging { jniLibs { useLegacyPackaging = true } }
    }

    withIosTargets(configureMseNative)
    withMacosTargets(configureMseNative)

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":kmpCommon"))
                api(Libs.Kotlinx.coroutinesCore)
            }
        }

        androidMain.dependencies {
            implementation(Libs.Media3.exoplayer)
        }
        jvmMain.dependencies {
            implementation(Libs.Kotlinx.coroutinesSwing)
            api(Libs.AndroidX.annotation)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Host libav JNI (leftover + demux + clock). Android NDK build is the same C; the only JNI
// media call is setVideoSurface.
val mseJniDir = layout.buildDirectory.dir("mse-jni")
val javaHomeForJni: String = run {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.let { add(it) }
        add(System.getProperty("java.home"))
        runCatching {
            ProcessBuilder("/usr/libexec/java_home").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    candidates.firstOrNull { File(it, "include/jni.h").exists() } ?: ""
}
val ffmpegDesktopPrefix: File? = FfmpegBuild.hostPrefix(project)
fun canBuildMseJni(log: org.gradle.api.logging.Logger): Boolean {
    val missing = when {
        javaHomeForJni.isEmpty() -> "no JDK with include/jni.h (set JAVA_HOME)"
        !mseNativeSrc.resolve("CMakeLists.txt").exists() -> "no mse/native/CMakeLists.txt"
        ffmpegDesktopPrefix == null -> "no ffmpeg dev prefix: build ../ffmpeg-build (scripts/build-desktop.sh)"
        hostOs.isWindows && mingwToolchainHome() == null -> MINGW_MISSING
        else -> return true
    }
    log.warn("w: skipping the desktop MSE JNI build, $missing; desktop playback will not load")
    return false
}
val mseCmakeConfigure by tasks.registering(Exec::class) {
    onlyIf { canBuildMseJni(logger) }
    val out = mseJniDir.get().asFile
    doFirst { out.mkdirs() }
    inputs.dir(mseNativeSrc)
    inputs.property("javaHome", javaHomeForJni)
    inputs.property("ffmpegRoot", ffmpegDesktopPrefix?.absolutePath ?: "")
    inputs.property("production", productionC)
    inputs.property("mingwHome", mingwToolchainHome()?.absolutePath ?: "")
    inputs.property("macosMin", Versions.macosMin)
    outputs.file(File(out, "CMakeCache.txt"))
    environment("JAVA_HOME", javaHomeForJni)
    val args = listOf(
        "cmake", "-S", mseNativeSrc.absolutePath, "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=${Versions.macosMin}",
        "-DFFMPEG_ROOT=${ffmpegDesktopPrefix?.absolutePath ?: ""}",
        "-DPRODUCTION=$productionC",
    )
    // After onlyIf: a Windows host without MinGW skips with canBuildMseJni's warning.
    doFirst { commandLine(args + mingwCmakeArgs()) }
}
val mseCmakeBuild by tasks.registering(Exec::class) {
    dependsOn(mseCmakeConfigure)
    onlyIf { canBuildMseJni(logger) }
    inputs.dir(mseNativeSrc)
    outputs.dir(mseJniDir)
    // MinGW Makefiles (Windows) and Unix Makefiles (macOS/Linux) are single-config; the Visual
    // Studio multi-config generator is avoided above with the forced MinGW toolchain on Windows.
    commandLine("cmake", "--build", mseJniDir.get().asFile.absolutePath, "-j", "4")
    doLast {
        // Siblings of libmseffmpegjni so @loader_path / $ORIGIN resolves in tests (mse.native.dir).
        // Windows: ffmpeg's `make install` puts the shared libs in bin/ (next to ffmpeg.exe); the
        // sibling MinGW runtime DLLs (zlib1, libwinpthread-1) also live there.
        val prefix = ffmpegDesktopPrefix ?: return@doLast
        val sources = listOf("lib", "bin").map { File(prefix, it) }.filter { it.isDirectory }
        for (src in sources) {
            src.listFiles()?.forEach { f ->
                val n = f.name
                if (n.endsWith(".a") || n.endsWith(".pc") || n.endsWith(".la")) return@forEach
                val sharedAv = n.startsWith("libav") || n.startsWith("libsw")
                val winAvDll = (n.startsWith("av") || n.startsWith("sw")) && n.endsWith(".dll")
                val winRuntimeDll = (n == "zlib1.dll" || n == "libwinpthread-1.dll")
                if (sharedAv || winAvDll || winRuntimeDll) {
                    f.copyTo(File(mseJniDir.get().asFile, n), overwrite = true)
                }
            }
        }
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(mseCmakeBuild)
    systemProperty("mse.native.dir", mseJniDir.get().asFile.absolutePath)
}
val mseHostJniFolder: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPrefix = when {
        os.contains("mac") || os.contains("osx") -> "macos"
        os.contains("linux") -> "linux"
        os.contains("win") -> "windows"
        else -> "unknown"
    }
    val archSuffix = when (arch) {
        "aarch64" -> "aarch64"
        "amd64", "x86_64" -> "x64"
        else -> arch
    }
    "${osPrefix}_$archSuffix"
}
val mseJniResourcesDir = layout.buildDirectory.dir("generated/mseJniResources")
val bundleMseJniLib by tasks.registering(Copy::class) {
    dependsOn(mseCmakeBuild)
    from(mseJniDir) {
        include(System.mapLibraryName("mseffmpegjni"))
        include("libav*", "libsw*", "av*.dll", "sw*.dll")
        // MinGW runtime DLLs ffmpeg-build packs on Windows (zlib1, libwinpthread-1) — avcodec and
        // mseffmpegjni import them, so they must sit next to the JNI at load time.
        include("zlib*.dll", "libwinpthread*.dll")
        exclude("*.a", "*.pc", "*.la")
    }
    into(mseJniResourcesDir.map { it.dir("jni/$mseHostJniFolder") })
    doLast {
        val dir = mseJniResourcesDir.get().asFile.resolve("jni/$mseHostJniFolder")
        val names = dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()
        dir.resolve("natives.txt").writeText(names.joinToString("\n"))
    }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(mseJniResourcesDir).builtBy(bundleMseJniLib))

// Android NDK: same C as the host, linked against ffmpeg-build out/android-<abi>.
val ffmpegBuildDir = (rootProject.projectDir.parentFile.resolve("ffmpeg-build")).takeIf { it.isDirectory }
val mseAndroidAbis = FfmpegBuild.ANDROID_ABIS
// ANDROID_NDK_ROOT pins the NDK (CI matches the one its ffmpeg packs were built with); otherwise the
// newest NDK installed in the SDK.
val ndkDir: File? = System.getenv("ANDROID_NDK_ROOT")?.let(::File)?.takeIf { it.isDirectory } ?: run {
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.exists() }?.path
    sdk?.let { File(it, "ndk").listFiles()?.filter { d -> d.isDirectory }?.maxByOrNull { d -> d.name } }
}
val stageAndroidJni by tasks.registering {
    val jniLibsDir = projectDir.resolve("src/androidMain/jniLibs")
    inputs.dir(mseNativeSrc)
    inputs.property("production", productionC)
    outputs.dir(jniLibsDir)
    onlyIf {
        ffmpegBuildDir != null && mseAndroidAbis.any { File(ffmpegBuildDir, "out/android-$it/include").isDirectory }
    }
    doLast {
        val ndk = ndkDir ?: error("Android NDK not found (set ANDROID_HOME with an installed NDK)")
        val toolchain = File(ndk, "build/cmake/android.toolchain.cmake")
        val root = ffmpegBuildDir ?: error("ffmpeg-build not found next to YMusicV4-mse")
        val sharedMissing = mseAndroidAbis.filter { abi ->
            val prefix = File(root, "out/android-$abi")
            prefix.resolve("include").isDirectory &&
                !prefix.resolve("lib/libavformat.so").isFile
        }
        if (sharedMissing.isNotEmpty()) {
            throw GradleException(
                "ffmpeg-build out/android-{${sharedMissing.joinToString()}} has no shared libavformat.so. " +
                    "Rebuild with scripts/build-android.sh <abi> (--enable-shared). " +
                    "Do not static-link libav into libmseffmpegjni.so.",
            )
        }
        fun sh(vararg args: String) {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("command failed: ${args.joinToString(" ")}\n$out")
        }
        mseAndroidAbis.forEach { abi ->
            val prefix = File(root, "out/android-$abi")
            if (!File(prefix, "lib/libavformat.so").isFile) return@forEach
            val buildDir = layout.buildDirectory.dir("mse-android/$abi").get().asFile
            buildDir.mkdirs()
            sh(
                "cmake", "-S", mseNativeSrc.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-${Versions.minSdk}",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DFFMPEG_ROOT=${prefix.absolutePath}",
                "-DPRODUCTION=$productionC",
            )
            sh("cmake", "--build", buildDir.absolutePath, "-j", "4")
            val so = File(buildDir, "libmseffmpegjni.so")
            val destDir = File(jniLibsDir, abi)
            destDir.mkdirs()
            so.copyTo(File(destDir, "libmseffmpegjni.so"), overwrite = true)
            File(prefix, "lib").listFiles()?.forEach { f ->
                if (f.name.endsWith(".so") && (f.name.startsWith("libav") || f.name.startsWith("libsw"))) {
                    f.copyTo(File(destDir, f.name), overwrite = true)
                }
            }
        }
    }
}
tasks.matching {
    it.name.matches(Regex("merge.*JniLibFolders"))
}.configureEach { dependsOn(stageAndroidJni) }
