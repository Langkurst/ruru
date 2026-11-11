import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The headset session: one connection, request/reply matching, the Bose model differences and
// the app-facing state. Pure Kotlin like the protocol module; each platform only supplies bytes.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
