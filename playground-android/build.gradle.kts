plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.ymusic.playground.app"
    compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
    defaultConfig {
        applicationId = "io.ymusic.playground"
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":playground"))
    implementation(Libs.AndroidX.activityCompose)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
}
