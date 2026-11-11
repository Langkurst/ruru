package io.github.langkurst.ruru.app

/**
 * When to tell someone about their headphones' battery: once when a charge finishes, once when
 * the battery runs low. Fed every reading in order; returns the notice to show, if any.
 *
 * Only a change seen while Ruru runs counts, so opening Ruru next to full or flat headphones says
 * nothing. A notice doesn't repeat until things change: unplugged after a full charge, or charged
 * back above the low mark. A missing reading (disconnected) forgets the last one, so reconnecting
 * is never mistaken for a change.
 */
class BatteryNotices(private val lowAt: Int = LOW_AT) {
    enum class Notice { CHARGED, LOW }

    private var last: Int? = null
    private var toldCharged = false
    private var toldLow = false

    fun next(percent: Int?, charging: Boolean?): Notice? {
        val previous = last
        last = percent
        if (percent == null) return null
        val onCharger = charging == true
        if (!onCharger) toldCharged = false
        if (onCharger || percent > lowAt) toldLow = false
        if (previous == null) return null
        return when {
            onCharger && percent >= 100 && previous < 100 && !toldCharged -> Notice.CHARGED.also { toldCharged = true }
            !onCharger && percent <= lowAt && previous > lowAt && !toldLow -> Notice.LOW.also { toldLow = true }
            else -> null
        }
    }

    companion object {
        /** The same mark at which the battery figure turns the alarm colour. */
        const val LOW_AT = 15
    }
}
