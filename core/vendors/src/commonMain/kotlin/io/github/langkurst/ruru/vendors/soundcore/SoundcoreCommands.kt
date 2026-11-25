package io.github.langkurst.ruru.vendors.soundcore

/** Command ids (`(category shl 8) or code`). */
object SoundcoreCommands {
    /** Full state; the Sleep A30 answers with a 150-byte body ([D1301State]). Also pushed. */
    const val REQUEST_STATE = 0x0101
    const val PUSH_TWS = 0x0102
    const val PUSH_BATTERY = 0x0103
    /** Firmware left/right and serial. */
    const val REQUEST_INFO = 0x0105
    /** Sleep A30: noise cancelling `[0 off | 1 on]`. Other models use `06 81` with their own payload. */
    const val SET_ANC_D1301 = 0x0687
    /** `[1 = tap controls disabled]`. */
    const val SET_TAP_DISABLED = 0x1094
    /** Sleep A30: `[0 Bluetooth | 1 Local]` (sound played from the buds). */
    const val SET_LISTENING_MODE = 0x01A9

    // Sleep A30 single-byte settings, `[0|1]`.
    const val SET_DEFAULT_LISTENING_MODE = 0x1095
    const val SET_LOW_BATTERY_PROMPT = 0x1082
    const val SET_AUTO_OFF_PROMPT = 0x109E
    const val SET_LISTENING_MODE_PROMPT = 0x109F
    const val SET_ANC_PROMPT = 0x1077
    const val SET_INCOMING_CALLS = 0x1075
    const val SET_AUTO_SWITCH_ASLEEP = 0x158F

    /**
     * Sleep A30 timer and post-sleep action, 4 bytes: `[FF FF FF action]` sets only the action
     * (FF = leave the timer fields alone), `[enabled, minutes lo, minutes hi, x]` sets the timer.
     */
    const val SET_SLEEP_TIMER = 0x1585

    /**
     * Sleep A30 reads on its original allow-list. Their names come from the category they share
     * with the writes: `15 85` / `15 8F` are the sleep options, `14 81` / `14 82` set and delete an
     * alarm. Ruru doesn't parse either reply; the Lab shows them raw.
     */
    const val SLEEP_SETTINGS_GET = 0x1503
    const val ALARMS_GET = 0x1401

    // ---- Gen-2 earbuds and headphones (research notes §3; ids are reused across models, so each
    // model's table says which of these it may send) ----

    /** `[left charging, right charging]`, pushed. */
    const val PUSH_CHARGING = 0x0104

    /** The sound-mode block, pushed when the modes change on the earbuds. Body = the model's codec size. */
    const val PUSH_SOUND_MODES = 0x0601

    /** Set the sound-mode block (ambient mode, noise-cancelling type and level, wind). */
    const val SET_SOUND_MODES = 0x0681

    /** Wear detection (auto play/pause on some models), `[0|1]`. */
    const val SET_WEAR_DETECTION = 0x0181

    /** Touch tone, `[0|1]`. */
    const val SET_TOUCH_TONE = 0x0183

    /** Auto power-off `[enabled, duration index]`. */
    const val SET_AUTO_OFF = 0x0186

    /** Gaming mode on the Note 3 / A40 / P3 / R50i NC family, `[0|1]`. */
    const val SET_GAMING_MODE = 0x0187

    /** Wearing tone, `[0|1]`. */
    const val SET_WEARING_TONE = 0x018C

    /** 3D surround (A30i), `[0|1]`. */
    const val SET_SURROUND = 0x0286

    /** Gaming mode on the Liberty 4 NC / Liberty 5, `[0|1]`. */
    const val SET_GAMING_MODE_10 = 0x1085

    /** Sound-leak compensation (Liberty 4 NC), `[0|1]`. */
    const val SET_LEAK_COMPENSATION = 0x1086

    /** Multipoint (two phones at once), `[0|1]`. On other models this id has been seen as a host connection, so it is per model. */
    const val SET_MULTIPOINT = 0x0B84

    /** Capability table read, and the empty "session init" some models want before writes. */
    const val SESSION_INFO = 0x0501
    const val SESSION_INIT = 0x0581
}
