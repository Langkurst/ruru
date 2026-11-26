package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.desktop_start_with_windows
import io.github.langkurst.ruru.ui.res.desktop_start_with_windows_hint
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
            label = Res.string.desktop_start_with_windows,
            hint = Res.string.desktop_start_with_windows_hint,
            isOn = { reg("query", KEY, "/v", VALUE) },
            set = { on ->
                if (on) reg("add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"$exe\" --hidden", "/f")
                else reg("delete", KEY, "/v", VALUE, "/f")
            },
            id = PlatformSwitch.START_AT_SIGN_IN,
        )
    }

    private fun reg(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
