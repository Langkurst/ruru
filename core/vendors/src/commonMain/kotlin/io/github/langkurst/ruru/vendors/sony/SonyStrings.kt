package io.github.langkurst.ruru.vendors.sony

/**
 * The resource keys of Sony's controls in the app's `strings_sony.xml` (one file per language next
 * to `strings.xml`), so the screen can show them in the person's language. A control whose id is
 * missing here (EQ bands are frequencies, general settings are named by the headset, DSEE is Sony's
 * own name) keeps the English [io.github.langkurst.ruru.core.VendorControl.title]. The English
 * text in the file is the same as the code's, and `SonyStringsTest` checks that it stays so.
 */
object SonyStrings {
    /** Control id -> key of its title. */
    val TITLE: Map<String, String> = mapOf(
        SonyStateMapping.AMBIENT_LEVEL to "vendor_sony_ambient_level",
        SonyStateMapping.FOCUS_ON_VOICE to "vendor_sony_focus_on_voice",
        SonyStateMapping.AUTO_AMBIENT to "vendor_sony_auto_ambient",
        SonyStateMapping.AUTO_AMBIENT_SENSITIVITY to "vendor_sony_auto_ambient_sensitivity",
        SonyStateMapping.EQ_PRESET to "vendor_sony_eq_preset",
        SonyStateMapping.AUTO_POWER_OFF to "vendor_sony_auto_power_off",
        SonyStateMapping.SPEAK_TO_CHAT to "vendor_sony_speak_to_chat",
        SonyStateMapping.PAUSE_WHEN_TAKEN_OFF to "vendor_sony_pause_when_taken_off",
        SonyStateMapping.CONNECTION_PRIORITY to "vendor_sony_connection_priority",
        SonyStateMapping.VOICE_GUIDANCE to "vendor_sony_voice_guidance",
    )

    /** Control id -> key of its one-line hint (the control's subtitle). */
    val HINT: Map<String, String> = mapOf(
        SonyStateMapping.AMBIENT_LEVEL to "vendor_sony_ambient_level_hint",
        SonyStateMapping.FOCUS_ON_VOICE to "vendor_sony_focus_on_voice_hint",
        SonyStateMapping.AUTO_AMBIENT to "vendor_sony_auto_ambient_hint",
        SonyStateMapping.EQ_PRESET to "vendor_sony_eq_preset_hint",
        SonyStateMapping.SPEAK_TO_CHAT to "vendor_sony_speak_to_chat_hint",
        SonyStateMapping.DSEE to "vendor_sony_dsee_hint",
    )

    /** English option label -> key, for the choices whose labels are fixed (the headset's own lists stay English). */
    val OPTION: Map<String, String> = mapOf(
        "Low" to "sony_option_low",
        "Standard" to "sony_option_standard",
        "High" to "sony_option_high",
        "Sound quality" to "sony_option_sound_quality",
        "Stable connection" to "sony_option_stable_connection",
        "When taken off" to "sony_option_when_taken_off",
    )

    /** The notes shown for these headphones ([SonyStateMapping.SUPPORT_NOTE], [SonyStateMapping.READ_ONLY_NOTE]) and the MDR v1 refusal. */
    const val NOTE_EXPERIMENTAL = "sony_note_experimental"
    const val NOTE_READ_ONLY = "sony_note_read_only"
    const val MESSAGE_V1_ONLY = "sony_v1_only"
}
