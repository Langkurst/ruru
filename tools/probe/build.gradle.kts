import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":platform:windows"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("io.github.langkurst.ruru.probe.MainKt")
}
