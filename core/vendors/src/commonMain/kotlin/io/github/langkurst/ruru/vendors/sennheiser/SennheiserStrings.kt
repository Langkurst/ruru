package io.github.langkurst.ruru.vendors.sennheiser

/**
 * The resource keys of Sennheiser's controls in the app's `strings_sennheiser.xml` (one file per
 * language next to `strings.xml`), so the screen can show them in the person's language. The key of
 * a control title is `vendor_` plus its id; a hint is that key plus `_hint`. The English text in the
 * file is the same as the code's, and `SennheiserStringsTest` checks that it stays so.
 */
object SennheiserStrings {
    /** Control id -> key of its title. */
    val TITLE: Map<String, String> = mapOf(
        SennheiserStateMapping.ADAPTIVE to "vendor_sennheiser_adaptive",
        SennheiserStateMapping.ANTI_WIND to "vendor_sennheiser_anti_wind",
        SennheiserStateMapping.COMFORT to "vendor_sennheiser_comfort",
        SennheiserStateMapping.TRANSPARENCY_LEVEL to "vendor_sennheiser_transparency_level",
        SennheiserStateMapping.PAUSE_IN_TRANSPARENCY to "vendor_sennheiser_pause_in_transparency",
        SennheiserStateMapping.ON_HEAD to "vendor_sennheiser_on_head",
        SennheiserStateMapping.SMART_PAUSE to "vendor_sennheiser_smart_pause",
        SennheiserStateMapping.AUTO_ANSWER to "vendor_sennheiser_auto_answer",
        SennheiserStateMapping.COMFORT_CALL to "vendor_sennheiser_comfort_call",
        SennheiserStateMapping.SIDETONE to "vendor_sennheiser_sidetone",
        SennheiserStateMapping.AUTO_OFF to "vendor_sennheiser_auto_off",
        SennheiserStateMapping.BASS_BOOST to "vendor_sennheiser_bass_boost",
    )

    /** Control id -> key of its one-line hint on headphones (the control's subtitle). */
    val HINT: Map<String, String> = mapOf(
        SennheiserStateMapping.ADAPTIVE to "vendor_sennheiser_adaptive_hint",
        SennheiserStateMapping.COMFORT to "vendor_sennheiser_comfort_hint",
        SennheiserStateMapping.PAUSE_IN_TRANSPARENCY to "vendor_sennheiser_pause_in_transparency_hint",
        SennheiserStateMapping.ON_HEAD to "vendor_sennheiser_on_head_hint",
        SennheiserStateMapping.SMART_PAUSE to "vendor_sennheiser_smart_pause_hint",
        SennheiserStateMapping.SIDETONE to "vendor_sennheiser_sidetone_hint",
        SennheiserStateMapping.AUTO_OFF to "vendor_sennheiser_auto_off_hint",
    )

    /** The hints that differ on earbuds (they replace [HINT]'s for the same id, or add one). */
    val HINT_EARBUDS: Map<String, String> = mapOf(
        SennheiserStateMapping.ANTI_WIND to "vendor_sennheiser_anti_wind_earbuds_hint",
        SennheiserStateMapping.SMART_PAUSE to "vendor_sennheiser_smart_pause_earbuds_hint",
    )

    /** English option label -> key, for the choices whose labels are fixed. */
    val OPTION: Map<String, String> = mapOf(
        "Off" to "sennheiser_option_off",
        "Max" to "sennheiser_option_max",
        "Auto" to "sennheiser_option_auto",
        "Low" to "sennheiser_option_low",
        "Medium" to "sennheiser_option_medium",
        "High" to "sennheiser_option_high",
        "Never" to "sennheiser_option_never",
        "15 min" to "sennheiser_option_15_min",
        "30 min" to "sennheiser_option_30_min",
        "1 h" to "sennheiser_option_1_h",
    )

    /** The notes shown for these headphones ([SennheiserStateMapping.SUPPORT_NOTE], [SennheiserStateMapping.NO_FEATURES_NOTE]). */
    const val NOTE_EXPERIMENTAL = "sennheiser_note_experimental"
    const val NOTE_NO_FEATURES = "sennheiser_note_no_features"
}
