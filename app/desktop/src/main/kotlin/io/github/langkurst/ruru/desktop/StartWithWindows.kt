package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.ui.PlatformSwitch
import java.util.concurrent.TimeUnit

/**
 * "Start with Windows": a value under the current user's Run key that starts Ruru hidden in the
 * tray. Only offered for the installed app (jpackage sets `jpackage.app-path`), not a dev run.
 */
internal object StartWithWindows {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "Ruru"

    fun switchOrNull(): PlatformSwitch? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        val exe = System.getProperty("jpackage.app-path") ?: return null
        return PlatformSwitch(
            label = "Start with Windows",
            hint = "Opens in the tray when you sign in",
            isOn = { reg("query", KEY, "/v", VALUE) },
            set = { on ->
                if (on) reg("add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"$exe\" --hidden", "/f")
                else reg("delete", KEY, "/v", VALUE, "/f")
            },
        )
    }

    private fun reg(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
