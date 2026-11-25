package io.github.langkurst.ruru.vendors.jbl

/**
 * The resource keys of JBL's controls in the app's `strings_jbl.xml` (one file per language next
 * to `strings.xml`), so the screen can show them in the person's language. The key of a control
 * title is `vendor_` plus its id; a hint is that key plus `_hint`. The English text in the file is
 * the same as the code's, and `JblStringsTest` checks that it stays so.
 */
object JblStrings {
    /** Control id -> key of its title. */
    val TITLE: Map<String, String> = mapOf(
        JblStateMapping.ANC_LEVEL to "vendor_jbl_anc_level",
        JblStateMapping.EQ_PRESET to "vendor_jbl_eq_preset",
        JblStateMapping.VOICE_PROMPTS to "vendor_jbl_voice_prompts",
    )

    /** Control id -> key of its one-line hint (the control's subtitle). */
    val HINT: Map<String, String> = mapOf(
        JblStateMapping.ANC_LEVEL to "vendor_jbl_anc_level_hint",
        JblStateMapping.EQ_PRESET to "vendor_jbl_eq_preset_hint",
        JblStateMapping.VOICE_PROMPTS to "vendor_jbl_voice_prompts_hint",
    )

    /** English option label -> key: the names of JBL's EQ presets. */
    val OPTION: Map<String, String> = mapOf(
        "Off" to "jbl_option_off",
        "Jazz" to "jbl_option_jazz",
        "Vocal" to "jbl_option_vocal",
        "Bass" to "jbl_option_bass",
        "Club" to "jbl_option_club",
        "Studio" to "jbl_option_studio",
        "Extreme Bass" to "jbl_option_extreme_bass",
    )

    /** The notes shown for these headphones ([JblStateMapping.SUPPORT_NOTE], [JblStateMapping.SILENT_NOTE]). */
    const val NOTE_EXPERIMENTAL = "jbl_note_experimental"
    const val NOTE_SILENT = "jbl_note_silent"

    /** The refusals a connect can end in, as [io.github.langkurst.ruru.link.Words] keys (shown as sentences). */
    const val MESSAGE_NEWER_PROTOCOL = JblModel.NEWER_PROTOCOL
    const val MESSAGE_AIROHA = JblModel.AIROHA
    const val MESSAGE_UNKNOWN_MODEL = JblModel.UNKNOWN
}
