package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.core.BatteryDetail
import io.github.langkurst.ruru.core.BatteryLevel
import io.github.langkurst.ruru.core.DeviceInfo
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.HeadphoneState

/** How an AirPods or Beats advert reads as [HeadphoneState]: the batteries, and capabilities that say "battery only". */
object AppleStateMapping {
    /** The note shown on Home (key [AppleStrings.NOTE_BATTERY_ONLY]). */
    const val BATTERY_ONLY_NOTE = "Ruru reads the battery from the adverts your AirPods or Beats send. It sends nothing to them and can't change their settings."

    /** Said on a system that cannot hear the adverts (key [AppleStrings.NOTE_NOT_POSSIBLE]); Mac and iPhone never can. */
    const val NOT_POSSIBLE_NOTE = "Ruru can't read AirPods or Beats battery on this system. On a Mac or iPhone Apple doesn't allow it."

    /** What Ruru can do with these headsets: show the battery, nothing else. */
    fun capabilities(headphones: Boolean) = HeadphoneCapabilities(
        spatialAudio = false, ancToggle = false, liveNoiseControl = false, noiseCancelling = false,
        ancAlwaysOn = false, windBlock = false, editableModes = false, favoriteModes = false,
        listeningModes = false, verified = false, ownSettings = false, modesOnHome = false,
        formFactor = if (headphones) FormFactor.HEADPHONES else FormFactor.EARBUDS,
        supportNote = BATTERY_ONLY_NOTE, batteryOnly = true,
    )

    /**
     * [state] with [advert] (null: nothing heard lately) folded in. Everything but the battery stays
     * as it was: Ruru has no other way to learn anything about these headsets.
     */
    fun apply(state: HeadphoneState, advert: ContinuityAdvert?, name: String, now: Long): HeadphoneState {
        val headphones = advert?.isHeadphones ?: false
        return state.copy(
            batteryPercent = advert?.headlinePercent,
            isCharging = advert?.charging,
            battery = advert?.let {
                if (headphones) BatteryDetail(overall = level(it.headlinePercent, it.charging))
                else BatteryDetail(
                    left = level(it.leftPercent, it.leftCharging),
                    right = level(it.rightPercent, it.rightCharging),
                    case = level(it.casePercent, it.caseCharging),
                )
            },
            capabilities = capabilities(headphones),
            info = DeviceInfo(productName = advert?.modelName ?: name, modelName = advert?.modelName),
            lastUpdatedMillis = now,
        )
    }

    /** A part the advert gave no reading for ("15") is no battery at all, not 0 %. */
    private fun level(percent: Int?, charging: Boolean): BatteryLevel? = percent?.let { BatteryLevel(it, charging = charging) }
}
