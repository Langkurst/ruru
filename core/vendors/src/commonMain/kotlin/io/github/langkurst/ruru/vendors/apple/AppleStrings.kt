package io.github.langkurst.ruru.vendors.apple

/**
 * The resource keys of the AirPods and Beats notes in the app's `strings_apple.xml` (one file per
 * language next to `strings.xml`). The English text in the file is the same as the code's, and
 * `AppleStringsTest` checks that it stays so.
 */
object AppleStrings {
    /** [AppleStateMapping.BATTERY_ONLY_NOTE]: shown on Home next to the batteries. */
    const val NOTE_BATTERY_ONLY = "apple_note_battery_only"

    /** [AppleStateMapping.NOT_POSSIBLE_NOTE]: shown where the adverts can't be heard (always on a Mac or iPhone). */
    const val NOTE_NOT_POSSIBLE = "apple_note_not_possible"

    /** Everything this file needs from the language files. */
    val KEYS: Set<String> = setOf(NOTE_BATTERY_ONLY, NOTE_NOT_POSSIBLE)
}
