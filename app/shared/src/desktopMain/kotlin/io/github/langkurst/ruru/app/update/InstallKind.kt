package io.github.langkurst.ruru.app.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How this copy of Ruru was installed, which decides the file to download and the way to install it.
 * Anything Ruru can't replace by itself (Linux packages, a run from the source tree) is [Other]:
 * it opens the download page instead.
 */
sealed interface InstallKind {
    /** The setup program (Inno) put it here; [appExe] is Ruru.exe in the install folder. */
    data class Setup(val appExe: Path) : InstallKind

    /** The per-user MSI put it here. */
    data class Msi(val appExe: Path) : InstallKind

    /** A macOS app bundle, [bundle] being the Ruru.app folder. */
    data class MacApp(val bundle: Path) : InstallKind

    data object Other : InstallKind

    /** The name of the file to download for the release [version], or null for [Other]. */
    fun assetName(version: String, assetNames: List<String>): String? = when (this) {
        is Setup -> "Ruru-$version-setup.exe"
        is Msi -> "Ruru-$version.msi"
        is MacApp -> assetNames.firstOrNull { it.startsWith("Ruru-$version") && it.endsWith(".dmg") }
            ?: "Ruru-$version-macos-arm64.dmg"
        Other -> null
    }

    companion object {
        /** Name of the uninstaller the setup program leaves next to Ruru.exe. */
        const val SETUP_UNINSTALLER = "unins000.exe"

        /**
         * Works out the install from the system and from the launcher's own path (the `jpackage.app-path`
         * property, which the packaged app's launcher sets; a run from Gradle has none).
         */
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            launcherPath: String? = System.getProperty("jpackage.app-path"),
            exists: (Path) -> Boolean = Files::exists,
        ): InstallKind {
            val launcher = launcherPath?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() } ?: return Other
            return when {
                osName.startsWith("Windows") -> {
                    val folder = launcher.parent ?: return Other
                    if (exists(folder.resolve(SETUP_UNINSTALLER))) Setup(launcher) else Msi(launcher)
                }
                osName.startsWith("Mac") -> {
                    var p: Path? = launcher
                    while (p != null && p.fileName?.toString()?.endsWith(".app") != true) p = p.parent
                    if (p != null) MacApp(p) else Other
                }
                else -> Other
            }
        }
    }
}
