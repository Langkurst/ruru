import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// The screens and the connection controller every Ruru app shares.
kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:session"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// RURU_VERSION in common code comes from gradle.properties, so the apps and installers agree.
val ruruVersion: String = providers.gradleProperty("ruru.version").get()
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/ruru/kotlin")
    val version = ruruVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("io/github/langkurst/ruru/app/RuruVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.github.langkurst.ruru.app\n\n/** Generated from gradle.properties (ruru.version). */\nconst val RURU_VERSION = \"$version\"\n")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateVersion) }

compose.resources {
    publicResClass = false
    packageOfResClass = "io.github.langkurst.ruru.ui.res"
}
