import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No android target: pure Kotlin, an Android KMP consumer resolves the `jvm` variant.
    withAppleTargets()
    withLinuxTargets()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmpCommon"))
            implementation(project(":kmpHttp:core")) // codedError(): a transport failure is the "network" category
            api(Libs.Kotlinx.serializationJson)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
