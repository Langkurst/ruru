package io.github.langkurst.ruru.ui

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.protocol.bose.ButtonEvent
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.StringResource

/*
 * The shared code names its choices in English ("Still", "Cycle modes"). These tables give each one
 * its string resource, so the screen shows the person's language. A name the tables don't know
 * (a new button press, a model note) is shown as the shared code wrote it.
 */

internal fun SpatialAudio.resource(): StringResource = when (this) {
    SpatialAudio.OFF -> Res.string.option_off
    SpatialAudio.ROOM -> Res.string.noise_spatial_still
    SpatialAudio.HEAD -> Res.string.noise_spatial_motion
}

internal fun Sidetone.resource(): StringResource = when (this) {
    Sidetone.OFF -> Res.string.option_off
    Sidetone.LOW -> Res.string.setting_self_voice_low
    Sidetone.MEDIUM -> Res.string.setting_self_voice_medium
    Sidetone.HIGH -> Res.string.setting_self_voice_high
}

internal fun ButtonAction.resource(): StringResource = when (this) {
    ButtonAction.NOT_CONFIGURED -> Res.string.shortcut_action_not_configured
    ButtonAction.VOICE_ASSISTANT -> Res.string.shortcut_action_voice_assistant
    ButtonAction.ANC_CYCLE -> Res.string.shortcut_action_anc_cycle
    ButtonAction.BATTERY_LEVEL -> Res.string.shortcut_action_battery_level
    ButtonAction.PLAY_PAUSE -> Res.string.shortcut_action_play_pause
    ButtonAction.INCREASE_CNC -> Res.string.shortcut_action_increase_cnc
    ButtonAction.DECREASE_CNC -> Res.string.shortcut_action_decrease_cnc
    ButtonAction.TOGGLE_WAKE_WORD -> Res.string.shortcut_action_toggle_wake_word
    ButtonAction.SWITCH_DEVICE -> Res.string.shortcut_action_switch_device
    ButtonAction.CONVERSATION_MODE -> Res.string.shortcut_action_conversation_mode
    ButtonAction.TRACK_FORWARD -> Res.string.shortcut_action_track_forward
    ButtonAction.TRACK_BACK -> Res.string.shortcut_action_track_back
    ButtonAction.FETCH_NOTIFICATIONS -> Res.string.shortcut_action_fetch_notifications
    ButtonAction.WIND_MODE -> Res.string.shortcut_action_wind_mode
    ButtonAction.DISABLED -> Res.string.shortcut_action_disabled
    ButtonAction.CLIENT_INTERACTION -> Res.string.shortcut_action_client_interaction
    ButtonAction.SPOTIFY_TAP -> Res.string.shortcut_action_spotify_tap
    ButtonAction.MODES_CAROUSEL -> Res.string.shortcut_action_modes_carousel
    ButtonAction.SPATIAL_AUDIO -> Res.string.shortcut_action_spatial_audio
    ButtonAction.LINE_IN_SWITCH -> Res.string.shortcut_action_line_in_switch
    ButtonAction.LINKING -> Res.string.shortcut_action_linking
    ButtonAction.UNKNOWN -> Res.string.shortcut_action_unknown
}

/** The press the shortcut button listens for, from the label the shared code gives it; null if unknown. */
internal fun buttonEventResource(label: String?): StringResource? = when (ButtonEvent.entries.firstOrNull { it.label == label }) {
    ButtonEvent.SHORT_PRESS -> Res.string.shortcut_event_short_press
    ButtonEvent.SINGLE_PRESS -> Res.string.shortcut_event_single_press
    ButtonEvent.PRESS_AND_HOLD -> Res.string.shortcut_event_press_and_hold
    ButtonEvent.DOUBLE_PRESS -> Res.string.shortcut_event_double_press
    ButtonEvent.DOUBLE_PRESS_AND_HOLD -> Res.string.shortcut_event_double_press_and_hold
    ButtonEvent.TRIPLE_PRESS -> Res.string.shortcut_event_triple_press
    ButtonEvent.LONG_PRESS -> Res.string.shortcut_event_long_press
    ButtonEvent.VERY_LONG_PRESS -> Res.string.shortcut_event_very_long_press
    else -> null
}
