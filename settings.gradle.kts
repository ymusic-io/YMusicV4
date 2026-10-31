@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YMusicV4"

include(":commonUi")
include(":image-loader")
include(":desktopApp")
include(":androidApp")
include(":kmpCommon")
include(":projectCommon")
include(":kmpHttp:core")
include(":kmpHttp:json")
include(":kmpHttp:json-benchmark")
// libcurl for the macOS/Linux native targets only: on a Windows host in kmpTargets=host mode it would
// declare no Kotlin target at all, and nothing depends on it there.
if (providers.gradleProperty("kmpTargets").orNull != "host" || !System.getProperty("os.name").startsWith("Windows")) {
    include(":kmpHttp:curl")
}
include(":kmpHttp:testing")
include(":kmpFfmpeg")
include(":media-store")
include(":media-scanner")
include(":dynamic-engine")
include(":engine-js")
include(":quickjs")
include(":player")
include(":mse")
include(":yt-media")
include(":download")
include(":iosAppKotlin")
include(":playground")
include(":playground-android")
include(":settings")
include(":remote-config")
include(":telemetry")
// includeBuild("YMusicLegacy")
