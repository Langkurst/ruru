package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.desktop_start_at_sign_in
import io.github.langkurst.ruru.ui.res.desktop_start_at_sign_in_hint
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Start when you sign in" on macOS and Linux, so the night schedule is still running after a restart.
 * macOS gets a launch agent in the person's own `~/Library/LaunchAgents` and Linux an autostart entry in
 * `~/.config/autostart`; both start Ruru hidden in the tray and neither needs an administrator.
 * Only offered for the installed app (jpackage sets `jpackage.app-path`), not a dev run. Windows has its own,
 * in [StartWithWindows].
 */
internal object StartAtSignIn {
    const val AGENT_LABEL = "io.github.langkurst.ruru"

    fun switchOrNull(): PlatformSwitch? {
        val os = System.getProperty("os.name").orEmpty()
        val file = fileFor(os, Path.of(System.getProperty("user.home")), System.getenv("XDG_CONFIG_HOME")) ?: return null
        val exe = System.getProperty("jpackage.app-path") ?: return null
        return PlatformSwitch(
            label = Res.string.desktop_start_at_sign_in,
            hint = Res.string.desktop_start_at_sign_in_hint,
            isOn = { isOn(file) },
            set = { on -> set(file, os, exe, on) },
            id = PlatformSwitch.START_AT_SIGN_IN,
        )
    }

    /** Where this system keeps the entry, or null on a system with none (Windows has its registry key instead). */
    fun fileFor(os: String, home: Path, xdgConfigHome: String?): Path? = when {
        os.startsWith("Mac") -> home.resolve("Library").resolve("LaunchAgents").resolve("$AGENT_LABEL.plist")
        os.startsWith("Linux") -> {
            val config = xdgConfigHome?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home.resolve(".config")
            config.resolve("autostart").resolve("ruru.desktop")
        }
        else -> null
    }

    fun isOn(file: Path): Boolean = Files.isRegularFile(file)

    /** Writes the entry for [exe] (on) or removes it (off). False when the file could not be written or removed. */
    fun set(file: Path, os: String, exe: String, on: Boolean): Boolean = runCatching {
        if (on) {
            Files.createDirectories(file.parent)
            val text = if (os.startsWith("Mac")) launchAgentPlist(exe) else autostartEntry(exe)
            Files.writeString(file, text)
        } else {
            Files.deleteIfExists(file)
        }
        true
    }.getOrDefault(false)

    /** The launch agent: start Ruru hidden when the person signs in. It is read at the next sign-in, so nothing is loaded now. */
    fun launchAgentPlist(exe: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$AGENT_LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>${xml(exe)}</string>
        <string>--hidden</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"""

    /** The freedesktop autostart entry. The path is quoted as the Desktop Entry spec asks, so a space in it is safe. */
    fun autostartEntry(exe: String): String = """[Desktop Entry]
Type=Application
Name=Ruru
Comment=Controls your headphones from the tray
Exec=${execQuoted(exe)} --hidden
Terminal=false
X-GNOME-Autostart-enabled=true
"""

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Double quotes around the path, with the four characters the spec says to escape inside them (and % doubled). */
    private fun execQuoted(path: String): String {
        val escaped = buildString {
            for (ch in path) {
                when (ch) {
                    '"', '`', '$', '\\' -> append('\\').append(ch)
                    '%' -> append("%%")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }
}
