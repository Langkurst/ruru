import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The headphone makers: how each one's headsets are recognised (DeviceFamily), which link reaches
// them (LinkRoute) and the session that drives them (HeadsetSession). Bose wraps the existing
// session code; the other makers start as stubs that never open a link. jvmMain is for the vendors
// that need the JVM's crypto (Bragi), shared by the desktop and Android apps.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:session"))
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
