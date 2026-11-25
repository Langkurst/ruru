package io.github.langkurst.ruru.app

import java.util.prefs.Preferences

/** What closing the window does. */
enum class CloseAction {
    /** The window goes away; Ruru stays in the tray and keeps the headphones connected. */
    CloseToTray,

    /** Ruru stops and exits, as the tray menu's Quit does. */
    Quit,
}

/**
 * "Keep running in the tray": whether closing the window leaves Ruru running. On by default (what
 * Ruru has always done). Kept per user with java.util.prefs. Without a system tray, closing the
 * window always quits, because there would be no way to get it back.
 */
class TrayPreference(private val prefs: Preferences? = defaultNode()) {
    var keepRunning: Boolean
        get() = runCatching { prefs?.getBoolean(KEEP, true) }.getOrNull() ?: true
        set(value) {
            runCatching { prefs?.putBoolean(KEEP, value); prefs?.flush() }
        }

    fun closeAction(hasTray: Boolean): CloseAction = if (hasTray && keepRunning) CloseAction.CloseToTray else CloseAction.Quit

    /** True the first time it is asked (and remembers that), false ever after: the one "still running" notice. */
    fun takeFirstTrayNotice(): Boolean {
        val p = prefs ?: return false
        return runCatching {
            if (p.getBoolean(NOTICE_SHOWN, false)) return false
            p.putBoolean(NOTICE_SHOWN, true)
            p.flush()
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val KEEP = "keepRunningInTray"
        const val NOTICE_SHOWN = "trayNoticeShown"
        fun defaultNode(): Preferences? = runCatching { Preferences.userRoot().node("io/github/langkurst/ruru/tray") }.getOrNull()
    }
}
