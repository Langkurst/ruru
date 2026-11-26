import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Sleep, common to every app (0.3): the sound engine (synths, mixer, fades, loops, limiter), the
// night's timeline and alarms, the night's headset steps as HeadsetCommand intents, and the night
// schedule with its catch-up rule. Pure Kotlin, no platform code and no java.* in commonMain, so the
// same source runs on Windows, macOS, Linux (JVM), Android (JVM) and iPhone (native). The apps own
// the audio output, the timers and the screens.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:vendors"))
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
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
