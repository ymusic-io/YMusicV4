plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The YMusic service the shell talks to: remote.config.url, or the local dev server with
// -PuseLocalServerIp (buildSrc LocalIp.kt / UpdateSigning.kt — same rules as Android and desktop).
val generateIosBuildConfig by tasks.registering {
    val url = remoteConfigUrl()
    val localIp = localServerIp()
    val outDir = layout.buildDirectory.dir("generated/buildconfig/kotlin")
    inputs.property("remoteConfigUrl", url)
    inputs.property("localIp", localIp)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("io/ymusic/ios/BuildConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.ymusic.ios

            // Base url of the YMusic service. Empty = no request, compiled-in defaults.
            internal const val REMOTE_CONFIG_URL = "$url"

            // local.server.ip with -PuseLocalServerIp ("" otherwise).
            internal const val LOCAL_IP = "$localIp"
            """.trimIndent() + "\n",
        )
    }
}

/**
 * The iOS product shell over :commonUi — the composition-root peer of :androidApp / :desktopApp.
 * Builds the AppGraph (engine manager, player facade, cookie jar, settings) and produces the
 * `YMusicApp` framework the Xcode project embeds; `MainViewController()` is the Swift entry point.
 */
kotlin {
    withComposeIosTargets {
        val ffmpegSlug = when (name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-simulator-arm64"
            else -> null
        }
        binaries.framework {
            baseName = "YMusicApp"
            isStatic = false
            // Compose MP 1.11.1's ui-uikit links iOS 26 symbols (UIViewLayoutRegion), which need the
            // iOS 26 SDK / Xcode 26. To build on an older Xcode, allow those undefined at link — Compose
            // @available-guards their use, so they're never touched below iOS 26. Drop when on Xcode 26.
            if (providers.gradleProperty("iosAllowUndefined").orNull == "true") {
                linkerOpts("-undefined", "dynamic_lookup")
            }
            linkerOpts("-framework", "MediaPlayer")
            linkerOpts("-framework", "LocalAuthentication")
            // Same as :playground: cinterop linkerOpts is ignored; bake libav + VT here.
            val lib = ffmpegSlug?.let {
                rootProject.projectDir.parentFile.resolve("ffmpeg-build/out/$it/lib")
            }
            if (lib != null && lib.resolve("libavcodec.a").isFile) {
                linkerOpts("-L${lib.absolutePath}")
                linkerOpts("-lavformat", "-lavcodec", "-lswscale", "-lswresample", "-lavutil")
                if (lib.resolve("libdav1d.a").isFile) linkerOpts("-ldav1d")
                if (lib.resolve("libvpx.a").isFile) linkerOpts("-lvpx")
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
    }
    // The KMP plugin requires at least one target, and the Compose iOS targets are host-gated OFF
    // in this Intel host's default mode — a sourceless jvm() keeps the module configurable there.
    // Real builds pass -PkmpTargets=all (the Xcode build phase does).
    if (targets.none { it is org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget }) jvm()

    // Lazy: iosMain only exists once the (host-gated) ios targets are configured.
    sourceSets.matching { it.name == "iosMain" }.configureEach {
        kotlin.srcDir(generateIosBuildConfig)
        dependencies {
            api(project(":commonUi"))
            implementation(project(":yt-media")) // the player-cache router (LocalMediaProxy) for the WebView
            implementation(project(":kmpHttp:core"))
            implementation(project(":kmpCommon"))
            implementation(project(":projectCommon"))
            implementation(project(":log-store")) // the app log (logs.db)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
