package io.github.langkurst.ruru.app

import java.util.prefs.Preferences

/**
 * Where Ruru keeps its small per-user settings (java.util.prefs). Two switches for builds and tests:
 *  - [disabled] (the build's `--warm-up` run): no node at all, so nothing is read or written.
 *  - RURU_PREFS_NODE (or -Druru.prefs): keeps everything under a node of that name instead of the
 *    real one, so a test copy never touches the settings of an installed Ruru.
 */
object PrefsRoot {
    @Volatile
    var disabled: Boolean = false

    private val testName: String? = (System.getProperty("ruru.prefs") ?: System.getenv("RURU_PREFS_NODE"))?.takeIf { it.isNotBlank() }

    /** The settings node for [name] ("window", "tray"), or null when settings are switched off or unavailable. */
    fun node(name: String): Preferences? {
        if (disabled) return null
        return runCatching {
            val base = if (testName == null) "io/github/langkurst/ruru" else "io/github/langkurst/ruru-test/$testName"
            Preferences.userRoot().node("$base/$name")
        }.getOrNull()
    }

    /** Removes everything a test copy wrote; does nothing for the real settings. */
    fun removeTestNode() {
        if (testName == null) return
        runCatching { Preferences.userRoot().node("io/github/langkurst/ruru-test/$testName").apply { removeNode(); flush() } }
    }
}
