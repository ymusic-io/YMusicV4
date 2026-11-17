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
        binaries.framework {
            baseName = "YMusicApp"
            isStatic = false
            // Escape hatch for Xcode < 26 only (the Xcode build phase passes it there): Compose MP 1.11.1's
            // ui-uikit references the iOS 26 UIViewLayoutRegion, absent from older SDKs. It hides every
            // other undefined symbol too, so a missing library becomes a launch crash.
            if (providers.gradleProperty("iosAllowUndefined").orNull == "true") {
                logger.warn(
                    "w: -PiosAllowUndefined=true: YMusicApp links with -undefined dynamic_lookup; a missing " +
                        "symbol crashes at runtime instead of failing the link. Only for Xcode < 26.",
                )
                linkerOpts("-undefined", "dynamic_lookup")
            }
            linkerOpts("-framework", "MediaPlayer")
            linkerOpts("-framework", "LocalAuthentication")
            // sqliter's Apple cinterop klib carries no -lsqlite3; SQLDelight's linkSqlite adds it only to
            // binaries of modules applying its plugin, so a framework of a consumer states it.
            linkerOpts("-lsqlite3")
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
