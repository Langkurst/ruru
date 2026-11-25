package io.github.langkurst.ruru.vendors.bragi

/**
 * The resource keys of the Skullcandy controls in the app's `strings_skullcandy.xml` (one file per
 * language next to `strings.xml`), so the screen can show them in the person's language. The key of
 * a control title is `vendor_` plus its id; a hint is that key plus `_hint`. The English text in the
 * file is the same as the code's, and `BragiStringsTest` checks that it stays so.
 */
object BragiStrings {
    /** Control id -> key of its title. Written out in full: a test finds every key's use as a literal. */
    val TITLE: Map<String, String> = mapOf(
        BragiStateMapping.CRUSHER to "vendor_skullcandy_crusher",
        BragiStateMapping.CRUSHER_INTENSITY to "vendor_skullcandy_crusher_intensity",
        BragiStateMapping.CRUSHER_FEEL to "vendor_skullcandy_crusher_feel",
    )

    /** Control id -> key of its one-line hint (the control's subtitle). */
    val HINT: Map<String, String> = mapOf(
        BragiStateMapping.CRUSHER to "vendor_skullcandy_crusher_hint",
    )

    /** English option label -> key, for the choices whose labels are words. */
    val OPTION: Map<String, String> = mapOf(
        "High" to "skullcandy_option_high",
        "Medium" to "skullcandy_option_medium",
        "Low" to "skullcandy_option_low",
    )

    /** The notes shown for these headphones ([BragiStateMapping.SUPPORT_NOTE], [BragiStateMapping.METHOD_NOTE]). */
    const val NOTE_EXPERIMENTAL = "skullcandy_note_experimental"
    const val NOTE_METHOD = "skullcandy_note_method"
}
