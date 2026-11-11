import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":platform:windows"))
    implementation(project(":platform:linux"))
    implementation(project(":platform:macos"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

val ruruVersion: String = providers.gradleProperty("ruru.version").get()

compose.desktop {
    application {
        mainClass = "io.github.langkurst.ruru.desktop.MainKt"
        // Ruru sits in the tray all day, so the JVM is tuned for a small footprint: a capped heap
        // that is handed back to the system, one light JIT tier, small thread stacks.
        jvmArgs += listOf(
            "-Xms16m", "-Xmx128m", "-Xss512k",
            "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
            "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxMetaspaceSize=128m",
            "-Dfile.encoding=UTF-8",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Ruru"
            packageVersion = ruruVersion
            description = "Open-source app for Bose headphones"
            vendor = "Langkurst"
            copyright = "Copyright (c) 2026 Langkurst and Earshot contributors. MIT licence."
            licenseFile.set(rootProject.file("LICENSE"))
            modules("jdk.unsupported")
            // OS-specific files bundled with the app (the macOS Bluetooth helper is built into resources/macos by CI).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                menuGroup = "Ruru"
                perUserInstall = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f1d1c0e-5a8b-4b8e-9d55-2f4b7a3c9e21"
                iconFile.set(rootProject.file("art/ruru.ico"))
            }
            macOS {
                bundleID = "io.github.langkurst.ruru"
                iconFile.set(rootProject.file("art/ruru.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>Ruru talks to your Bose headphones over Bluetooth to show and change their settings.</string>
                    """.trimIndent()
                }
            }
            linux {
                packageName = "ruru"
                iconFile.set(rootProject.file("art/ruru.png"))
                debMaintainer = "Langkurst <145878628+Langkurst@users.noreply.github.com>"
                menuGroup = "AudioVideo"
            }
        }
    }
}

// Renders the real app to a PNG: ./gradlew :app:desktop:snapshot -Pout=shots/home.png -Pdemo -Plight
tasks.register<JavaExec>("snapshot") {
    group = "ruru"
    description = "Render the app off-screen to a PNG"
    mainClass.set("io.github.langkurst.ruru.desktop.SnapshotKt")
    classpath = sourceSets["main"].runtimeClasspath
    val outPath = rootProject.file(providers.gradleProperty("out").getOrElse("build/snapshots/ruru.png")).path
    args(
        listOfNotNull(
            outPath,
            "--height=" + providers.gradleProperty("height").getOrElse("1600"),
            if (providers.gradleProperty("demo").isPresent) "--demo" else null,
            if (providers.gradleProperty("light").isPresent) "--light" else null,
        ),
    )
}
