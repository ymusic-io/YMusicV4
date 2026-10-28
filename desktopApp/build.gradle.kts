import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("io.ymusic.desktop.db")
            srcDirs.setFrom("src/jvmMain/sqldelight")
        }
    }
}

compose.resources {
    // Desktop-only assets (the dock tile); shared brand assets stay in :commonUi.
    packageOfResClass = "io.ymusic.desktop.resources"
}

val desktopVersion = appVersionName()
// BUMP ON EVERY RELEASE. The update check compares this and nothing else — desktopVersion is a
// display string. Desktop counts separately from androidApp's versionCode (separate cadence).
val desktopVersionCode = 16
version = desktopVersion

/** The first candidate JDK that carries jpackage — the marker for "full JDK, not a JBR/JRE". */
val fullJdkHome: String? = run {
    val candidates = listOfNotNull(
        providers.gradleProperty("ymusic.jdkHome").orNull,
        System.getenv("JAVA_HOME"),
        System.getProperty("java.home"),
        // macOS keeps its installs out of JAVA_HOME's reach; ask the OS for a 17+ one.
        runCatching {
            providers.exec { commandLine("/usr/libexec/java_home", "-v", "17+") }
                .standardOutput.asText.get().trim()
        }.getOrNull(),
    )
    candidates.firstOrNull { it.isNotBlank() && File(it, "bin/jpackage").exists() }
}

// The DSN is machine-local (see SentryProps in buildSrc), so it reaches the app as generated
// source rather than a checked-in constant. The release carries the git commit — that's how an
// event traces back to git history without a source-context upload.
// The key app updates must be signed with, from the environment/local.properties so a fork builds
// without ours. No key means no update is ever offered.
val updatePublicKeyValue: String = providers.gradleProperty("ymusic.updatePublicKey").orNull
    ?: updatePublicKey()

// The key remote JS bundles must be signed with. Empty: only the shipped bundles ever run.
val bundlePublicKeyValue: String = providers.gradleProperty("ymusic.bundlePublicKey").orNull
    ?: bundleSigningPublicKey()

// Base url of the remote config service. Empty leaves the app on its compiled-in defaults.
val remoteConfigBaseUrl: String = localServerUrl()
    ?: providers.gradleProperty("ymusic.remoteConfigUrl").orNull
    ?: remoteConfigUrl()

// local.server.ip with -PuseLocalServerIp, else "": a released build must not depend on
// (or reveal) where it was built.
val localIp: String = localServerIp()

// Dev and test runs exec the ffmpeg staged from the sibling ffmpeg-build through
// -Dio.ymusic.ffmpeg. A packaged build never sees this property: it uses the copy packed next to
// the compose resources (see stagePackedFfmpeg). Set on the run and test TASKS, never on
// compose.desktop.application.jvmArgs — those are baked into the packaged launcher.
val ffmpegForDev: File? = FfmpegBuild.hostBinary(project)
if (ffmpegForDev != null) {
    val syncFfmpegHost = rootProject.tasks.named("syncFfmpegHost")
    val ffmpegPath = ffmpegForDev.absolutePath
    tasks.withType<Test>().configureEach {
        dependsOn(syncFfmpegHost)
        systemProperty("io.ymusic.ffmpeg", ffmpegPath)
    }
    tasks.withType<JavaExec>().matching { !it.name.contains("Distributable") && !it.name.contains("Release") }
        .configureEach {
            dependsOn(syncFfmpegHost)
            systemProperty("io.ymusic.ffmpeg", ffmpegPath)
        }
}

// Host ffmpeg pack (CLI + libav DSOs) plus the MSE JNI that links the same libav, into compose
// app-resources — the one copy of each library a package carries (:mse's jar copy is stripped
// below). Each library ships once, under the soname the CLI and libav itself link against: the
// pack's other names are symlinks, which a copy would turn into full duplicates.
val ffmpegPackedName = FfmpegBuild.hostCliName()
val ffmpegAppResources = layout.buildDirectory.dir("ffmpegAppResources")
val mseJniName: String = System.mapLibraryName("mseffmpegjni")
val stagePackedFfmpeg by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("syncFfmpegHost"), ":mse:bundleMseJniLib")
    val stagedDir = ffmpegAppResources.map { it.dir("common") }
    val ffmpegSigningIdentity = macSigningIdentity()
    inputs.property("ffmpegSigningIdentity", ffmpegSigningIdentity)
    // A stale stage would keep names this one no longer produces.
    doFirst { stagedDir.get().asFile.deleteRecursively() }
    if (FfmpegBuild.hostSlug() != null) {
        from(FfmpegBuild.hostDir(project)) {
            eachFile {
                if (Files.isSymbolicLink(file.toPath())) {
                    exclude()
                } else {
                    // libavcodec.63.2.100.dylib → libavcodec.63.dylib; libavcodec.so.63.2.100 → libavcodec.so.63
                    name = name.replace(Regex("""^(lib\w+)\.(\d+)\.\d+\.\d+\.dylib$"""), "$1.$2.dylib")
                        .replace(Regex("""^(lib\w+\.so\.\d+)\.\d+\.\d+$"""), "$1")
                }
            }
        }
        from(project(":mse").layout.buildDirectory.dir("generated/mseJniResources/jni")) {
            include("*/$mseJniName")
            eachFile { path = name }
        }
        includeEmptyDirs = false
        into(stagedDir)
        doLast {
            val dir = stagedDir.get().asFile
            val cli = dir.resolve(ffmpegPackedName)
            cli.setExecutable(true)
            val jni = dir.resolve(mseJniName)
            if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && jni.isFile) relinkMacJni(jni)
            // Compose signs the .dylib resources but not an extension-less executable, and notarization
            // rejects an unsigned one: sign the CLI with the app's identity, hardened runtime + timestamp.
            if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && ffmpegSigningIdentity.isNotBlank()) {
                val codesign = ProcessBuilder(
                    "codesign", "--force", "--options", "runtime", "--timestamp",
                    "--sign", ffmpegSigningIdentity, cli.absolutePath,
                ).redirectErrorStream(true).start()
                val output = codesign.inputStream.bufferedReader().readText()
                check(codesign.waitFor() == 0) { "codesign $cli failed: $output" }
            }
        }
    }
}

// jpackage copies app resources without their mode bits, so the bundled CLI arrives unexecutable and
// the app falls back to whatever ffmpeg is on PATH. Installers are made from this image.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    val imageDir = layout.buildDirectory.dir("compose/binaries")
    doLast {
        imageDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name == ffmpegPackedName && it.parentFile.name == "resources" }
            .forEach { check(it.setExecutable(true, false)) { "could not mark $it executable" } }
    }
}

/**
 * Point the MSE JNI at the libav sonames staged beside it (it was linked against the full-version
 * names), and drop absolute rpaths the build machine leaked into it — only @loader_path is valid
 * in a package. Re-signed ad hoc after: arm64 refuses to load a dylib whose signature is broken.
 */
fun relinkMacJni(jni: File) {
    fun exec(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
        return output
    }
    val fullVersion = Regex("""@rpath/(lib\w+)\.(\d+)\.\d+\.\d+\.dylib""")
    val changes = fullVersion.findAll(exec("otool", "-L", jni.path))
        .flatMap { listOf("-change", it.value, "@rpath/${it.groupValues[1]}.${it.groupValues[2]}.dylib") }
    val absoluteRpaths = Regex("""(?m)^\s+path (/\S+) \(offset""").findAll(exec("otool", "-l", jni.path))
        .flatMap { listOf("-delete_rpath", it.groupValues[1]) }
    val args = (changes + absoluteRpaths).toList()
    if (args.isNotEmpty()) exec("install_name_tool", *args.toTypedArray(), jni.path)
    exec("codesign", "--force", "--sign", "-", jni.path)
}

val generateBuildConfig by tasks.registering {
    val dsn = sentryDsn()
    val release = "ymusic-desktop@$desktopVersion+${gitCommit()}"
    val outDir = layout.buildDirectory.dir("generated/buildconfig/kotlin")
    inputs.property("dsn", dsn)
    inputs.property("release", release)
    inputs.property("engineJsVersion", Versions.engineJsVersion)
    inputs.property("versionCode", desktopVersionCode)
    inputs.property("updatePublicKey", updatePublicKeyValue)
    inputs.property("remoteConfigBaseUrl", remoteConfigBaseUrl)
    inputs.property("localIp", localIp)
    inputs.property("bundlePublicKey", bundlePublicKeyValue)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("BuildConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.ymusic.desktop

            internal const val SENTRY_DSN = "$dsn"
            internal const val SENTRY_RELEASE = "$release"

            // The jar's engine_ymusic.js resource revision (Versions.engineJsVersion).
            internal const val ENGINE_JS_VERSION: Long = ${Versions.engineJsVersion}L

            // What the update check compares against the config's appUpdate.versionCode.
            internal const val APP_VERSION_CODE: Int = $desktopVersionCode
            internal const val APP_VERSION_NAME = "$desktopVersion"

            // The P-256 key app updates must be signed with. Empty disables updates entirely —
            // with nothing to verify against, trusting the network is worse than not updating.
            internal const val UPDATE_PUBLIC_KEY = "$updatePublicKeyValue"

            // Base url of the remote config service. Empty = no request, compiled-in defaults.
            internal const val REMOTE_CONFIG_URL = "$remoteConfigBaseUrl"

            // local.server.ip with -PuseLocalServerIp ("" otherwise).
            internal const val LOCAL_IP = "$localIp"

            // The key remote engine/player bundles must be signed with. Empty = shipped bundles only.
            internal const val BUNDLE_PUBLIC_KEY = "$bundlePublicKeyValue"
            """.trimIndent() + "\n",
        )
    }
}

// The engine bundle ships inside the jar — JsSourceProvider's embed copy (version = the
// generated ENGINE_JS_EMBED_VERSION const),
// no compiled-in constant.
val engineJsBundle = configurations.create("engineJsBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    "engineJsBundle"(project(":engine-js", "engineJs"))
}
val stageEngineJs by tasks.registering(Copy::class) {
    from(engineJsBundle) { rename { "engine_ymusic.js" } }
    into(layout.buildDirectory.dir("engineJsResources"))
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // The resources accessor (Res) only generates when this dependency sits in commonMain —
        // that is where the module's own composeResources (the dock tile) get their accessors.
        commonMain.dependencies {
            implementation(compose.components.resources)
        }

        /** The desktop (JVM) product shell: builds the AppGraph (MSE + ffmpeg, engines) and boots
         *  the shared :commonUi App. `./gradlew :desktopApp:run`. */
        val jvmMain by getting {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                implementation(project(":commonUi"))
                implementation(project(":media-scanner"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(Libs.Jna.core)
                implementation(Libs.SqlDelight.sqliteDriver) // AppStore
                implementation(project(":telemetry")) // api: sentry-kotlin-multiplatform + SentryLogger
                implementation(project(":kmpHttp:core"))
                implementation(project(":projectCommon"))
                implementation(project(":remote-config"))
                implementation(project(":yt-media"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        // Headless snapshot renders of shared components (visual dev checks, not assertions).
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Publishing a release: package on each platform's machine (packageReleaseDmg / Msi / Deb), then
// sign each installer with the config service's scripts/sign-app-update.mjs.

// Standalone UI-component catalogue (see CatalogueMain.kt): ./gradlew :desktopApp:catalogue
tasks.register<JavaExec>("catalogue") {
    group = "application"
    description = "Open the UI-component catalogue window (no engines — fast design loop)."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("io.ymusic.desktop.CatalogueMainKt")
}

compose.desktop {
    application {
        mainClass = "io.ymusic.desktop.MainKt"
        // A FULL JDK is needed both by jpackage/jlink and by ProGuard (it links against the
        // platform classes — without them it can't even resolve java.lang.Object). The Gradle
        // daemon often runs on a JBR that has neither, so find one; -Pymusic.jdkHome overrides.
        fullJdkHome?.let { javaHome = it }
        // Compose SwingPanel (MSE blit) needs AWT internals opened on the JBR/JDK.
        // Heap: without these, packaged HotSpot picks ~1/4 RAM (16g here) and
        // InitialHeapSize=Xmx. Live use was ~50–97m objects / ~38m JIT code.
        // Skia GPU cache default is 256m.
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
            "-Xms96m",
            "-Xmx256m",
            "-XX:ReservedCodeCacheSize=64m",
            "-Dskiko.gpu.resourceCacheLimit=64M",
        )
        // Release packaging (packageReleaseDmg etc.) runs ProGuard SHRINK-ONLY: no obfuscation, no
        // optimization — dead-code removal is the point (material-icons-extended alone is 37MB of
        // mostly-unused icon classes). Reflection/JNI/ServiceLoader keeps: proguard-rules.pro.
        buildTypes.release.proguard {
            version.set("7.7.0")
            optimize.set(false)
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YMusic"
            packageVersion = desktopVersion
            macOS {
                // Stable across releases: the updater and Gatekeeper both identify the app by it.
                bundleID = "io.ymusic.desktop"
                // The rounded-square dock tile; the runtime Taskbar icon (dev runs) is the same
                // artwork from src/jvmMain/resources/ymusic_dock.png. macOS 26+ draws the
                // CFBundleIconName icon from macos/Assets.car instead (installMacIconAssets).
                iconFile.set(project.file("ymusic.icns"))
                // ymusic:// links open the app (Main.kt's open-URI handler takes them).
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleIconName</key>
                        <string>YMusic</string>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>io.ymusic.desktop</string>
                                <key>CFBundleURLSchemes</key>
                                <array><string>ymusic</string></array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
                // Signing is opt-in so a checkout without the identity still builds. An unsigned
                // build is fine to run locally and impossible to ship: Gatekeeper rejects it.
                val signingIdentity = macSigningIdentity()
                signing {
                    sign.set(signingIdentity.isNotBlank())
                    if (signingIdentity.isNotBlank()) identity.set(signingIdentity)
                }
                // The JVM needs executable memory a hardened runtime withholds; see
                // entitlements.plist for why each is there.
                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.plist"))
                val appleId = macNotarizationAppleId()
                if (appleId.isNotBlank()) {
                    notarization {
                        appleID.set(appleId)
                        password.set(macNotarizationPassword())
                        teamID.set(macTeamId())
                    }
                }
            }
            windows {
                // Fixed for the app's lifetime: a new MSI with the same upgrade code replaces the
                // installed one instead of installing beside it. Per-machine, under Program Files.
                upgradeUuid = "6f3c2b9e-4d1a-4e8b-9a57-2c1f0d8e7b34"
                perUserInstall = false
                menu = true
                shortcut = true
                // Multi-res ICO (16/32/48/64/96/128/256) derived from the Android launcher at
                // mipmap-xxxhdpi/ic_launcher.png; without this jpackage falls back to its default
                // Java/Compose icon on the installer dialog, Start-menu entry, and running window.
                iconFile.set(project.file("ymusic.ico"))
            }
            // jlink modules the default set misses but the runtime graph uses: java.sql
            // (sqlite-jdbc), jdk.unsupported (Unsafe — JNA/coroutines/okio),
            // java.management + java.logging (Sentry integrations, JNA), jdk.crypto.ec
            // (TLS for update checks), jdk.accessibility (bundles AccessBridge so AWT
            // doesn't abort at startup when a screen reader / Magnifier is active).
            modules("java.sql", "java.management", "java.logging", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            // Host ffmpeg next to compose resources (`compose.application.resources.dir` at runtime).
            appResourcesRootDir.set(ffmpegAppResources)
        }
    }
}

tasks.matching {
    it.name.contains("AppResources") || it.name.startsWith("package") || it.name.contains("Distributable")
}.configureEach {
    dependsOn(stagePackedFfmpeg)
}

// NOTE (byte-measured, 2026-08-14): jlink's --compress=2 (settable only reflectively — the
// compose plugin's compressionLevel has no public DSL) shrinks the runtime dir 67→45MB on disk
// but GROWS the DMG 72.2→76.1MiB: the DMG's zlib squeezes the raw jimage better than jlink's
// per-resource zip, which is opaque to it. Download size is the goal → runtime stays uncompressed.

// A package is built on, and for, one OS and CPU: the host's. sqlite-jdbc, JNA, skiko and our own
// jni/ jars carry natives for others, which are dead weight there. They are dropped from the
// ProGuard output — what jpackage consumes — in the same pass that normalizes entry timestamps.
val hostOs = org.gradle.internal.os.OperatingSystem.current().let {
    if (it.isMacOsX) "macos" else if (it.isWindows) "windows" else "linux"
}
val hostArm64 = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")

/** `macos-x64`, `windows-x64`, …: the update service's platform key, and the installer's suffix. */
val hostPlatform = "$hostOs-${if (hostArm64) "arm64" else "x64"}"

// macOS 26 re-renders a bundle that has only an .icns into a flat Liquid Glass tile, dropping
// the rings. Assets.car is compiled from macos/YMusic.icon (macos/build-icon.sh, needs Xcode 26);
// jpackage cannot place it in Contents/Resources, so it goes in after, and the outer seal it
// breaks is re-signed with the same identity, entitlements and flags.
val macIconAssets = project.file("macos/Assets.car")
if (hostOs == "macos") {
    tasks.withType<AbstractJPackageTask>().matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
        val signingIdentity = macSigningIdentity()
        inputs.file(macIconAssets)
        inputs.property("macIconSigningIdentity", signingIdentity)
        doLast("installMacIconAssets") {
            val app = destinationDir.get().asFile.listFiles().orEmpty().single { it.name.endsWith(".app") }
            macIconAssets.copyTo(File(app, "Contents/Resources/Assets.car"), overwrite = true)
            val identity = signingIdentity.ifBlank { "-" }
            val command = listOf(
                "codesign", "--force", "--sign", identity,
                if (identity == "-") "--timestamp=none" else "--timestamp",
                "--preserve-metadata=identifier,entitlements,requirements,flags,runtime",
                app.path,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { "re-signing ${app.name} failed: $output" }
        }
    }
}

tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val proguardOut = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    // The plugin never clears this dir, so a jar whose name changes (a dependency version bump)
    // would leave its predecessor behind to be packaged alongside the new one.
    doFirst { proguardOut.get().asFile.deleteRecursively() }
    doLast {
        val sqliteKeep = "org/sqlite/native/" +
            mapOf("macos" to "Mac", "windows" to "Windows", "linux" to "Linux").getValue(hostOs) + "/" +
            (if (hostArm64) "aarch64" else "x86_64") + "/"
        val jnaKeep = "com/sun/jna/" + mapOf("macos" to "darwin", "windows" to "win32", "linux" to "linux").getValue(hostOs) +
            "-" + (if (hostArm64) "aarch64" else "x86-64") + "/"
        // Native dirs are `<os>-<arch>/`; com/sun/jna/win32/ (no dash) is a CLASS package.
        val jnaNativeDir = Regex("^com/sun/jna/[a-z0-9]+-[a-z0-9-]+/")
        val skikoKeep = "libskiko-$hostOs-" + (if (hostArm64) "arm64" else "x64")
        val jniKeep = "jni/${hostOs}_" + (if (hostArm64) "aarch64" else "x64") + "/"
        fun isForeignNative(jar: String, entry: String): Boolean = when {
            // Its JNI and libav ship once, in app resources (stagePackedFfmpeg).
            jar.startsWith("mse-jvm") -> entry.startsWith("jni/")
            entry.startsWith("org/sqlite/native/") -> !entry.startsWith(sqliteKeep)
            jnaNativeDir.containsMatchIn(entry) -> !entry.startsWith(jnaKeep)
            entry.startsWith("libskiko-") -> !entry.startsWith(skikoKeep)
            entry.startsWith("jni/") -> !entry.startsWith(jniKeep)
            else -> false
        }

        // ProGuard stamps the CURRENT TIME into every entry it writes, so a jar with identical
        // content would hash differently on each build. Normalize to a fixed instant, so an
        // unchanged release builds byte-identical.
        fun rewrite(jar: File) {
            val tmp = File(jar.absolutePath + ".norm")
            ZipFile(jar).use { zip ->
                ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || isForeignNative(jar.name, entry.name)) continue
                        // 1980-01-01, the earliest a zip entry can express.
                        out.putNextEntry(ZipEntry(entry.name).apply { time = 315_532_800_000L })
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            check(jar.delete() && tmp.renameTo(jar)) { "could not replace ${jar.name} with its rewritten copy" }
        }

        proguardOut.get().asFile.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .forEach(::rewrite)
    }
}

// The host's installer under a name that says what it is for, ready for sign-app-update.mjs:
//   ./gradlew :desktopApp:releaseInstaller   →   build/release/YMusic-<version>-<platform>.<dmg|msi|deb>
tasks.register<Copy>("releaseInstaller") {
    group = "distribution"
    description = "Package the release installer for this OS/arch and name it YMusic-<version>-<platform>."
    val format = mapOf("macos" to "Dmg", "windows" to "Msi", "linux" to "Deb").getValue(hostOs)
    dependsOn("packageRelease$format")
    from(layout.buildDirectory.dir("compose/binaries/main-release/${format.lowercase()}"))
    include("*.${format.lowercase()}")
    rename { "YMusic-$desktopVersion-$hostPlatform.${format.lowercase()}" }
    into(layout.buildDirectory.dir("release"))
}

kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("engineJsResources")).builtBy(stageEngineJs))

// ---------------------------------------------------------------------------------------------
// Windows: Inno Setup installer. Wraps the jlinked app directory produced by
// createReleaseDistributable in an LZMA2-compressed single-exe installer with a nicer upgrade
// flow than jpackage's canned MSI. Compiler lookup: see buildSrc InnoSetup.kt.
// ---------------------------------------------------------------------------------------------
val isccPath: Provider<File> = providers.provider {
    innoSetupCompiler() ?: error(INNO_SETUP_MISSING)
}

val innoSetupStageDir = layout.buildDirectory.dir("compose/tmp/packageReleaseInnoSetup")
val innoSetupOutDir = layout.buildDirectory.dir("compose/binaries/main-release/innosetup")

val packageReleaseInnoSetup by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "Wrap the release distributable in an Inno Setup installer (Windows only)."
    onlyIf { hostOs == "windows" }
    dependsOn("createReleaseDistributable")

    val templateFile = project.file("installer/windows/ymusic.iss.in")
    val iconFile = project.file("ymusic.ico")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/app/YMusic")

    inputs.file(templateFile)
    inputs.file(iconFile)
    inputs.dir(appDir)
    inputs.property("version", desktopVersion)
    inputs.property("platform", hostPlatform)
    outputs.dir(innoSetupOutDir)

    doFirst {
        val stage = innoSetupStageDir.get().asFile.apply { mkdirs() }
        val out = innoSetupOutDir.get().asFile.apply { mkdirs() }
        val iss = File(stage, "ymusic.iss")
        iss.writeText(templateFile.readText())
        commandLine(
            isccPath.get().absolutePath,
            "/Qp",
            "/DAppVersion=$desktopVersion",
            "/DAppPublisher=YMusic",
            "/DAppURL=https://ymusic.io",
            "/DAppSourceDir=${appDir.get().asFile.absolutePath}",
            "/DIconFile=${iconFile.absolutePath}",
            "/DOutputDir=${out.absolutePath}",
            "/DArch=${if (hostArm64) "arm64" else "x64compatible"}",
            "/DOutputBaseName=YMusic-Setup-$desktopVersion-$hostPlatform",
            iss.absolutePath,
        )
    }
}
