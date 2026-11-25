package io.github.langkurst.ruru.app.update

import java.nio.file.Path

/**
 * What to start to install a downloaded file and open Ruru again: a [command], and the text of a
 * small script it runs ([scriptName] in the update folder), when the job takes more than one step.
 * Nothing here asks for administrator rights: the installs are all per-user.
 */
class InstallPlan(val command: List<String>, val scriptName: String? = null, val scriptText: String? = null)

object Installer {
    /** Silent flags of the setup program (see packaging/windows). /RELAUNCH opens Ruru when it is done. */
    val SETUP_FLAGS = listOf("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH")

    fun plan(kind: InstallKind, file: Path, scriptDir: Path): InstallPlan = when (kind) {
        is InstallKind.Setup -> InstallPlan(listOf(file.toString()) + SETUP_FLAGS)
        is InstallKind.Msi -> {
            val script = scriptDir.resolve(MSI_SCRIPT)
            InstallPlan(listOf("cmd.exe", "/c", script.toString()), MSI_SCRIPT, msiScript(file, kind.appExe))
        }
        is InstallKind.MacApp -> {
            val script = scriptDir.resolve(MAC_SCRIPT)
            InstallPlan(listOf("/bin/sh", script.toString()), MAC_SCRIPT, macScript(file, kind.bundle))
        }
        InstallKind.Other -> throw IllegalStateException("This copy of Ruru can't install updates by itself")
    }

    /** A per-user MSI upgrades without an administrator. msiexec doesn't make cmd wait, so `start /wait` does. */
    internal fun msiScript(msi: Path, appExe: Path): String = listOf(
        "@echo off",
        "rem Waits for Ruru to close, installs the new version, opens Ruru again.",
        "ping -n 3 127.0.0.1 >nul",
        "start \"\" /wait msiexec /i \"${cmd(msi)}\" /qn /norestart",
        "start \"\" \"${cmd(appExe)}\"",
    ).joinToString("\r\n", postfix = "\r\n")

    /** Mounts the disk image without showing it, swaps in the new app if the folder is writable, else shows the image in Finder. */
    internal fun macScript(dmg: Path, bundle: Path): String = """
        #!/bin/sh
        # Waits for Ruru to close, copies the new app over it, opens it again.
        APP=${sh(bundle.toString())}
        DMG=${sh(dmg.toString())}
        sleep 2
        MNT=${'$'}(mktemp -d /tmp/ruru-update.XXXXXX) || exit 1
        if ! hdiutil attach -nobrowse -readonly -mountpoint "${'$'}MNT" "${'$'}DMG" >/dev/null 2>&1; then
          rmdir "${'$'}MNT" 2>/dev/null
          open "${'$'}DMG"
          exit 0
        fi
        DONE=0
        if [ -w "${'$'}(dirname "${'$'}APP")" ] && [ -d "${'$'}MNT/Ruru.app" ]; then
          if ditto "${'$'}MNT/Ruru.app" "${'$'}APP.new" && rm -rf "${'$'}APP" && mv "${'$'}APP.new" "${'$'}APP"; then DONE=1; fi
        fi
        hdiutil detach "${'$'}MNT" -force >/dev/null 2>&1
        rmdir "${'$'}MNT" 2>/dev/null
        if [ "${'$'}DONE" = 1 ]; then open -n "${'$'}APP"; else open "${'$'}DMG"; fi
        """.trimIndent() + "\n"

    /** A path inside a double-quoted cmd string: % would be read as a variable. */
    private fun cmd(p: Path): String = p.toString().replace("%", "%%")

    /** A word for /bin/sh in single quotes. */
    private fun sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private const val MSI_SCRIPT = "ruru-update.cmd"
    private const val MAC_SCRIPT = "ruru-update.sh"
}
