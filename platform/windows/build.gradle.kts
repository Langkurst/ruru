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

// Windows Bluetooth for the desktop app: the paired-device list and the Serial Port control
// channel, through Winsock and the Win32 Bluetooth API (JNA); Bluetooth LE through the helper
// program in helper/ (ruru-ble-win.exe, C++/WinRT, built by helper/build.ps1 and bundled by CI).
dependencies {
    api(project(":core:session"))
    implementation(libs.jna)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
