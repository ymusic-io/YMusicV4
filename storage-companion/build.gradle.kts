plugins {
    // Keep in step with Versions.agp of the main build.
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "io.ymusic.files"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ymusic.files"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // YMusic builds pass its own release key here: the picker grants folders only to apps signed like itself.
    val releaseSigning = System.getenv("YMUSIC_ANDROID_STORE_FILE")?.takeIf { file(it).isFile }?.let { path ->
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = System.getenv("YMUSIC_ANDROID_STORE_PASSWORD")
            keyAlias = System.getenv("YMUSIC_ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("YMUSIC_ANDROID_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
