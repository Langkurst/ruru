package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.TrayPreference
import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.desktop_keep_in_tray
import io.github.langkurst.ruru.ui.res.desktop_keep_in_tray_hint

/**
 * "Keep running in the tray": on, closing the window leaves Ruru in the tray; off, closing it quits.
 * Only offered when there is a system tray (without one, closing the window already quits).
 */
internal fun keepInTraySwitch(preference: TrayPreference, hasTray: Boolean): PlatformSwitch? {
    if (!hasTray) return null
    return PlatformSwitch(
        label = Res.string.desktop_keep_in_tray,
        hint = Res.string.desktop_keep_in_tray_hint,
        isOn = { preference.keepRunning },
        set = { on -> preference.keepRunning = on },
    )
}
