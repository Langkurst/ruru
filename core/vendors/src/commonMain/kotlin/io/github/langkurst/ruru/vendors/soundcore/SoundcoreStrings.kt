package io.github.langkurst.ruru.vendors.soundcore

/**
 * The resource keys of Soundcore's controls in the app's `strings_soundcore.xml` (one file per
 * language next to `strings.xml`), so the screen can show them in the person's language. The
 * English text in the file is the same as the code's, and `SoundcoreStringsTest` checks that it
 * stays so. The labels of the auto power-off durations ("10 min", "1 hour") are not here: they
 * come from the earbuds' own list of choices and stay as they are, as Sony's do.
 */
object SoundcoreStrings {
    /** Control id -> key of its title (`vendor_` plus the id). Written out in full: a test finds every key's use as a literal. */
    val TITLE: Map<String, String> = mapOf(
        SoundcoreStateMapping.ANC to "vendor_soundcore_anc",
        SoundcoreStateMapping.LISTENING to "vendor_soundcore_listening_mode",
        SoundcoreStateMapping.DEFAULT_LISTENING to "vendor_soundcore_default_listening_mode",
        SoundcoreStateMapping.AUTO_SWITCH_ASLEEP to "vendor_soundcore_auto_switch_asleep",
        SoundcoreStateMapping.POST_SLEEP to "vendor_soundcore_post_sleep_action",
        SoundcoreStateMapping.INCOMING_CALLS to "vendor_soundcore_incoming_calls",
        SoundcoreStateMapping.PROMPT_LOW_BATTERY to "vendor_soundcore_prompt_low_battery",
        SoundcoreStateMapping.PROMPT_ANC to "vendor_soundcore_prompt_anc",
        SoundcoreStateMapping.PROMPT_LISTENING to "vendor_soundcore_prompt_listening_mode",
        SoundcoreStateMapping.PROMPT_AUTO_OFF to "vendor_soundcore_prompt_auto_off",
        SoundcoreWrites.NC_TYPE to "vendor_soundcore_nc_type",
        SoundcoreWrites.NC_LEVEL to "vendor_soundcore_nc_level",
        SoundcoreWrites.TRANSPARENCY_TYPE to "vendor_soundcore_transparency_type",
        SoundcoreWrites.WIND to "vendor_soundcore_wind",
        SoundcoreSettings.TOUCH_TONE to "vendor_soundcore_touch_tone",
        SoundcoreSettings.GAMING_MODE to "vendor_soundcore_gaming_mode",
        SoundcoreSettings.LEAK_COMPENSATION to "vendor_soundcore_leak_compensation",
        SoundcoreSettings.MULTIPOINT to "vendor_soundcore_multipoint",
        SoundcoreSettings.LOW_BATTERY_PROMPT to "vendor_soundcore_low_battery_prompt",
        SoundcoreSettings.WEARING_TONE to "vendor_soundcore_wearing_tone",
        SoundcoreSettings.WEAR_DETECTION to "vendor_soundcore_wear_detection",
        SoundcoreSettings.SURROUND to "vendor_soundcore_surround",
        SoundcoreSettings.AUTO_OFF to "vendor_soundcore_auto_off",
    )

    /** Control id -> key of its one-line hint (the control's subtitle). */
    val HINT: Map<String, String> = mapOf(
        SoundcoreStateMapping.LISTENING to "vendor_soundcore_listening_mode_hint",
        SoundcoreStateMapping.DEFAULT_LISTENING to "vendor_soundcore_default_listening_mode_hint",
        SoundcoreStateMapping.AUTO_SWITCH_ASLEEP to "vendor_soundcore_auto_switch_asleep_hint",
        SoundcoreStateMapping.POST_SLEEP to "vendor_soundcore_post_sleep_action_hint",
        SoundcoreWrites.NC_TYPE to "vendor_soundcore_nc_type_hint",
        SoundcoreWrites.NC_LEVEL to "vendor_soundcore_nc_level_hint",
        SoundcoreWrites.TRANSPARENCY_TYPE to "vendor_soundcore_transparency_type_hint",
        SoundcoreWrites.WIND to "vendor_soundcore_wind_hint",
        SoundcoreSettings.TOUCH_TONE to "vendor_soundcore_touch_tone_hint",
        SoundcoreSettings.GAMING_MODE to "vendor_soundcore_gaming_mode_hint",
        SoundcoreSettings.MULTIPOINT to "vendor_soundcore_multipoint_hint",
        SoundcoreSettings.WEAR_DETECTION to "vendor_soundcore_wear_detection_hint",
    )
    /** English option label -> key, for the choices whose labels are words ("Bluetooth" is a name and stays). */
    val OPTION: Map<String, String> = mapOf(
        "Off" to "soundcore_option_off",
        "Local sounds" to "soundcore_option_local_sounds",
        "Pause" to "soundcore_option_pause",
        "Play local sounds" to "soundcore_option_play_local_sounds",
        "Manual" to "soundcore_option_manual",
        "Adaptive" to "soundcore_option_adaptive",
        "Transportation" to "soundcore_option_transportation",
        "Multi-scene" to "soundcore_option_multi_scene",
        "Fully transparent" to "soundcore_option_fully_transparent",
        "Vocal mode" to "soundcore_option_vocal_mode",
    )

    /** The notes shown for these headphones (see [SoundcoreModelMapping]) and the refusal for a model Ruru doesn't drive. */
    const val NOTE_EXPERIMENTAL = "soundcore_note_experimental"
    const val NOTE_READ_ONLY = "soundcore_note_read_only"
    const val NOTE_BATTERY_ONLY = "soundcore_note_battery_only"
    const val NOTE_UNREADABLE = "soundcore_note_unreadable"
    const val MESSAGE_UNKNOWN_MODEL = SoundcoreSession.UNKNOWN_MODEL
}
