plugins {
    // AGP + core Kotlin plugins - versions resolved from the buildSrc classpath (see
    // buildSrc/build.gradle.kts), so they're applied without a version here.
    id("com.android.application") apply false
    id("com.android.library") apply false
    kotlin("android") apply false
    kotlin("multiplatform") apply false
    // Kotlin compiler plugins - need explicit version (not bundled in kotlin-gradle-plugin)
    kotlin("plugin.compose") version Versions.kotlin apply false
    kotlin("plugin.serialization") version Versions.kotlin apply false
    kotlin("plugin.allopen") version Versions.kotlin apply false
    id("org.jetbrains.compose") version Versions.composeMultiplatform apply false
    id("org.jetbrains.kotlinx.benchmark") version Versions.kotlinxBenchmark apply false
    id("com.diffplug.spotless") version "8.8.0"
}

val ciSkippedTests: List<String> =
    if (providers.gradleProperty("ci").orNull == "true") {
        rootDir.resolve(".github/ci-skip-tests.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    } else {
        emptyList()
    }

subprojects {
    installDebugBuildFlag()
    // No test runs longer than 30 s: JUnit Platform aborts it (runTest's own clock agrees).
    tasks.withType<org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest>().configureEach {
        useJUnitPlatform()
        systemProperty("junit.jupiter.execution.timeout.default", "30 s")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        systemProperty("kotlinx.coroutines.test.default_timeout", "30s")
        filter {
            isFailOnNoMatchingTests = false
            ciSkippedTests.forEach { excludeTestsMatching("*.$it") }
        }
    }
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        // vendored / generated / foreign trees stay untouched
        targetExclude("**/build/**", "YMusicLegacy/**", "temp/**", "**/publicsuffix/PslDafsa.kt")
        // Spotless does not discover .editorconfig on its own — without the explicit path ktlint
        // runs on ktlint_official defaults and restructures code the config deliberately protects.
        ktlint("1.8.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "buildSrc/*.gradle.kts")
        ktlint("1.8.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
}

// ---- ffmpeg packs from the sibling ffmpeg-build (see buildSrc/FfmpegBuild.kt) -----------------
// Registered here rather than per module so the two staging directories have exactly one producer
// each. Consumers depend on the one they need: :androidApp and :kmpFfmpeg's device tests on the
// android ABI packs, :desktopApp's run and tests on the host pack (CLI + the same libav DSOs).
fun Project.registerFfmpegSync(name: String, packs: Map<String, String>, dir: File, what: String) =
    tasks.register<SyncFfmpeg>(name) {
        group = "build setup"
        description = "Stage $what from ../ffmpeg-build/out."
        this.packs.set(packs)
        ffmpegBuildRoot.set(FfmpegBuild.root(project).absolutePath)
        packFiles.from(packs.values.map { FfmpegBuild.pack(project, it) })
        outputDir.set(dir)
    }

registerFfmpegSync(
    "syncFfmpegHost",
    listOfNotNull(FfmpegBuild.hostSlug()).associateBy { "" },
    FfmpegBuild.hostDir(project),
    "the host ffmpeg pack (CLI + libav) the desktop app ships and the tests exec",
)

registerFfmpegSync(
    "syncFfmpegAndroid",
    FfmpegBuild.ANDROID_ABIS.associateWith(FfmpegBuild::androidSlug),
    FfmpegBuild.androidDir(project),
    "the per-ABI ffmpeg pack the apk ships as lib/<abi>/{libffmpeg.so,libav*.so}",
)

tasks.register("syncFfmpeg") {
    group = "build setup"
    description = "Stage every ffmpeg pack this host can use."
    dependsOn("syncFfmpegHost", "syncFfmpegAndroid")
}

tasks.register("cleanBuildDir", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Make lifecycle clean task depend on our custom clean
tasks.matching { it.name == "clean" }.configureEach {
    dependsOn("cleanBuildDir")
}

// ONE command refreshes everything derived from the engine-js bundle after a source edit or a
// engineJsVersion / playerJsVersion bump: build the bundles, restage the Android asset + desktop jar
// resource, and regenerate the embedded consts + their versions. (Consumers also pull all of this on
// their own builds — this exists so `./gradlew syncEngineJs` refreshes every copy in one go.)
tasks.register("syncEngineJs") {
    dependsOn(
        ":engine-js:closureJs",
        ":engine-js:closurePlayerJs",
        ":androidApp:copyEngineJs",
        ":desktopApp:stageEngineJs",
        ":dynamic-engine:generateEngineJsBundleKt",
        ":dynamic-engine:generateEnginePlayerJsBundleKt",
    )
}
