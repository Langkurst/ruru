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
    implementation(libs.compose.resources)
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
            // Java2D's Direct3D pipeline costs ~80 MB just to draw the tray icon; the window draws with
            // Compose's own renderer, so the old pipeline is switched off.
            "-Dsun.java2d.d3d=false", "-Dsun.java2d.noddraw=true",
            // The software renderer for the window is chosen in Main.kt, on every system but macOS:
            // macOS has no SOFTWARE_FAST, and the window failed to open there when it was set here.
            "-Dfile.encoding=UTF-8",
        )

        // Release builds (createReleaseDistributable, packageRelease*) shrink the app with ProGuard:
        // unused library code is dropped, nothing is renamed, so a crash report still reads clearly.
        // The rules say what must stay (Ruru's own code, the Windows Bluetooth calls through JNA).
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

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
                // macOS refuses a bundle version that starts with 0, so before 1.0 the bundle says 1.0.0, and so do
                // Finder's Get Info and the About panel. The window's footer and the file name on GitHub show the real version.
                if (ruruVersion.startsWith("0.")) {
                    packageVersion = "1.0.0"
                    dmgPackageVersion = "1.0.0"
                }
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
                // jpackage adds the vendor and the angle brackets itself, so this is the address alone.
                debMaintainer = "145878628+Langkurst@users.noreply.github.com"
                rpmLicenseType = "MIT"
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
            providers.gradleProperty("update").orNull?.let { "--update=$it" },
        ),
    )
}

// Tries Check for updates against GitHub for real, pretending to be an older version. It downloads and
// checks the file, prints the command it would run, and installs nothing:
// ./gradlew :app:desktop:updateCheck -Pfake=0.1.0 -Pkind=msi
tasks.register<JavaExec>("updateCheck") {
    group = "ruru"
    description = "Try the updater against GitHub with a pretend older version (installs nothing)"
    mainClass.set("io.github.langkurst.ruru.desktop.UpdateCheckKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--fake=" + providers.gradleProperty("fake").getOrElse("0.1.0"), "--kind=" + providers.gradleProperty("kind").getOrElse("msi"))
}

// Checks the app's controller against real headphones (changes the mode and bass, then puts them back).
tasks.register<JavaExec>("hardwareCheck") {
    group = "ruru"
    description = "Change and restore settings on connected headphones through the app's controller"
    mainClass.set("io.github.langkurst.ruru.desktop.HardwareCheckKt")
    classpath = sourceSets["main"].runtimeClasspath
    // The check notes here what it is about to change, so the next run can undo a run that was cut short.
    args(layout.buildDirectory.file("hardware-check-pending.txt").get().asFile.path)
}
