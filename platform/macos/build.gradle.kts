import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// macOS Bluetooth for the desktop app: everything goes through the Swift helper in helper/
// (ruru-bt-mac, IOBluetooth and CoreBluetooth), built by CI and bundled with the app.
dependencies {
    api(project(":core:session"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
