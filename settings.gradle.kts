pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ruru"

// Shared code, used by every app: the Bose protocol and the EQ maths.
include(":core:protocol")
include(":core:dsp")
include(":core:session")
// Sleep: sound engine, night plan and alarms, night schedule (0.3).
include(":core:sleep")
// Every headphone maker Ruru knows: how each is recognised, reached and driven (0.3).
include(":core:vendors")

// Platform Bluetooth for the desktop app.
include(":platform:windows")
include(":platform:linux")
include(":platform:macos")

// The apps: shared screens, and the desktop app (Windows, macOS, Linux).
include(":app:shared")
include(":app:desktop")
include(":app:android")

// Command-line tools for checking a real headset from a PC.
include(":tools:probe")
