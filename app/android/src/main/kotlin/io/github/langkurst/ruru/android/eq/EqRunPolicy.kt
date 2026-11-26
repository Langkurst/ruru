package io.github.langkurst.ruru.android.eq

import android.media.AudioDeviceInfo
import android.os.Build

/** What the equaliser should be doing right now. */
data class EqDecision(
    /** The sessions that should carry the effect. Empty while it is off, paused, or waiting for an app to play. */
    val targets: Set<Int>,
    /** The equaliser is on but the sound is not going to Bluetooth headphones, so nothing is filtered. */
    val pausedByRoute: Boolean,
    /** The equaliser is on and the headphones are there, but no app has announced its sound yet. */
    val waitingForApp: Boolean,
)

/** The pure rules for when the effect runs and on which sessions. */
object EqRunPolicy {
    /**
     * The equaliser only filters sound that goes to Bluetooth headphones: the same curve on the phone's
     * speaker sounds wrong, and the effect would keep running when the headphones drop out in a call.
     * Every announced session gets it; the global mix (session 0) only when the person turned that
     * experimental option on.
     */
    fun decide(enabled: Boolean, bluetoothHeadphones: Boolean, sessions: List<AudioSession>, globalMix: Boolean): EqDecision {
        if (!enabled) return EqDecision(emptySet(), pausedByRoute = false, waitingForApp = false)
        if (!bluetoothHeadphones) return EqDecision(emptySet(), pausedByRoute = true, waitingForApp = false)
        val targets = buildSet {
            for (s in sessions) add(s.sessionId)
            if (globalMix) add(0)
        }
        return EqDecision(targets, pausedByRoute = false, waitingForApp = targets.isEmpty())
    }

    /**
     * Whether a list of output device types ([AudioDeviceInfo.getType]) has Bluetooth headphones: A2DP,
     * and on Android 12 and newer Bluetooth LE audio. A phone call's Bluetooth route (SCO) is not music.
     */
    fun isBluetoothHeadphones(deviceTypes: Collection<Int>, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val wanted = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (sdk >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        return deviceTypes.any { it in wanted }
    }
}
