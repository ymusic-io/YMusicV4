import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.playground"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        packaging { jniLibs { useLegacyPackaging = true } }
    }

    withComposeIosTargets {
        binaries.framework {
            baseName = "MsePlayground"
            isStatic = false
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":dynamic-engine"))
                implementation(project(":mse"))
                implementation(project(":settings"))
                implementation(project(":projectCommon"))
                implementation(project(":kmpHttp:core"))
                implementation(project(":quickjs"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":kmpHttp:testing"))
                implementation(project(":yt-media"))
                implementation(Libs.SqlDelight.sqliteDriver)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        sourceSets.matching { it.name == "androidMain" }.configureEach {
            dependencies {
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(Libs.AndroidX.activityCompose)
                implementation(Libs.Kotlinx.coroutinesAndroid)
            }
        }

        sourceSets.matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(project(":dynamic-engine"))
                implementation(project(":mse"))
                implementation(project(":settings"))
                implementation(project(":projectCommon"))
                implementation(project(":kmpHttp:core"))
                implementation(project(":quickjs"))
            }
        }
    }
}

val mseJniDir = project(":mse").layout.buildDirectory.dir("mse-jni")

tasks.withType<JavaExec>().configureEach {
    dependsOn(":mse:mseCmakeBuild")
    systemProperty("mse.native.dir", mseJniDir.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    dependsOn(":mse:mseCmakeBuild")
    systemProperty("mse.native.dir", mseJniDir.get().asFile.absolutePath)
    timeout.set(Duration.ofMinutes(8))
}

compose.desktop {
    application {
        mainClass = "io.ymusic.playground.MainKt"
        jvmArgs(
            "-Dmse.native.dir=${mseJniDir.get().asFile.absolutePath}",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "YMusicPlayground"
            packageVersion = "1.0.0"
        }
    }
}
