package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.LogRing

/**
 * Text a user can paste into a bug report: app and headset facts plus the recent connection log.
 * Bluetooth addresses are masked and paired-device names are left out.
 */
object ConnectionReport {
    private val ADDRESS = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")

    fun build(state: AppState, appVersion: String): String {
        val h = state.headset
        val info = h.info
        val caps = h.capabilities
        val text = buildString {
            appendLine("Ruru $appVersion on ${state.platformName}${if (state.demo) " (demo mode)" else ""}")
            appendLine("State: ${state.phase::class.simpleName}")
            appendLine("Model: ${info?.modelName ?: "unknown"} (product id ${info?.productId?.let { "0x" + it.toString(16).padStart(4, '0') } ?: "unknown"})")
            appendLine("Firmware: ${info?.firmwareVersion ?: "unknown"}, BMAP ${info?.bmapVersion ?: "unknown"}")
            appendLine("Modes: ${h.modes.size}, live noise control ${caps.liveNoiseControl}, ANC switch ${caps.ancToggle}, live updates ${h.livePushEnabled}")
            appendLine()
            appendLine("Recent log:")
            LogRing.tail(150).forEach { appendLine(it) }
        }
        return text.replace(ADDRESS, "xx:xx:xx:xx:xx:xx")
    }
}
