plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Required for kmp-targets convention plugin.
    // Keep version in sync with Versions.kotlin in Dependencies.kt
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    // Android Gradle Plugin on the shared buildSrc classpath so modules can apply the android plugins
    // without a version. Keep in sync with Versions.agp. (Root only — YMusicLegacy's buildSrc pins its
    // own AGP independently and is untouched.)
    implementation("com.android.tools.build:gradle:9.5.0")
    // SQLDelight Gradle plugin on the shared buildSrc classpath so :media-store can apply
    // `id("app.cash.sqldelight")` without a version. Keep in sync with Versions.sqldelight.
    implementation("app.cash.sqldelight:gradle-plugin:2.4.1")
}
