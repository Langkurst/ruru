package io.github.langkurst.ruru.android.ble

/**
 * Does an LE advert's name belong to the headset the phone knows by [bluetoothName]? TWS buds that
 * keep their control service on a separate LE identity advertise it as `<name>-LE` (JBL); LE Audio
 * identities show up as `LE-<name>`. Exact matches only, ignoring case and the separator: "Live
 * Buds 3-LE" fits "Live Buds 3", "Live Buds 3 Pro-LE" does not.
 *
 * Ported from Earshot's LeAdvertMatch and LeAudioTwins (MIT, see CREDITS.md). Pure, so it is
 * unit-tested without Bluetooth.
 */
object LeAdvertMatch {
    private val SUFFIX = Regex("""[-_ ]le$""", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("""^le[-_ ]""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = listOf("-", "_", " ")
    private const val MIN_NAME_LENGTH = 4

    /** True for the name Android gives an LE Audio identity ("LE-Bose ..."). */
    fun isLeName(name: String?): Boolean = name != null && PREFIX.containsMatchIn(name.trim())

    /** The name without the "LE-" prefix, or null when nothing is left. */
    fun baseName(name: String?): String? = name?.trim()?.replace(PREFIX, "")?.trim()?.ifEmpty { null }

    /** Too short or generic a name would match strangers' devices; such a headset is not scanned for. */
    fun isUsable(bluetoothName: String?): Boolean = (bluetoothName?.trim()?.length ?: 0) >= MIN_NAME_LENGTH

    fun fits(advertName: String?, bluetoothName: String?): Boolean {
        if (advertName == null || !isUsable(bluetoothName)) return false
        val base = bluetoothName!!.trim()
        val advert = advertName.trim()
        if (advert.equals(base, ignoreCase = true)) return true
        val stripped = baseName(advert.replace(SUFFIX, "")) ?: return false
        return stripped.equals(base, ignoreCase = true)
    }

    /**
     * The names a scan filter asks for: the headset's own and its `LE` suffix and prefix forms with
     * each separator [fits] accepts. Android's name filter is exact and case-sensitive, so these are
     * the spellings seen in practice; an advert carrying the service UUID is found whatever its name.
     */
    fun candidateNames(bluetoothName: String): List<String> {
        val base = bluetoothName.trim()
        return listOf(base) + SEPARATORS.map { "$base${it}LE" } + SEPARATORS.map { "LE$it$base" }
    }
}
