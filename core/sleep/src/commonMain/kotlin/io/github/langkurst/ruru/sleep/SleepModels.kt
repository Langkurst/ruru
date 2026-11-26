package io.github.langkurst.ruru.sleep

import kotlinx.serialization.Serializable

/*
 * The Sleep plan and the alarms (Earshot's sleep-mode design, section 4.2). They are saved as JSON
 * under the settings key `sleep.plan`, so every enum constant and field name here is frozen once
 * released: renaming one makes saved settings unreadable. Sounds are referred to by
 * [io.github.langkurst.ruru.sleep.sound.SleepSound.storedId] strings, so this file stays free of
 * the sound engine.
 */

/** One sound in a mix: [soundId] is a `SleepSound.storedId` or a [CustomSound.id], [gain] 0..1. */
@Serializable
data class SleepLayer(val soundId: String, val gain: Float = 0.7f)

/** A sound the user added from their own files. [id] always starts with [ID_PREFIX]. */
@Serializable
data class CustomSound(val id: String, val name: String, val loopSeconds: Int) {
    companion object {
        const val ID_PREFIX = "custom:"
        fun isCustom(soundId: String): Boolean = soundId.startsWith(ID_PREFIX)
    }
}

/** What happens to the headset when the timer runs out. Sounds always stop first. */
@Serializable
enum class SleepEndAction {
    /** Fade out and stop; the headset stays connected (and the alarm can ring in it). */
    STOP_SOUNDS,

    /** Then disconnect the headset; it usually switches itself off later. */
    DISCONNECT,

    /** Then switch the headset off, where its session can; otherwise disconnect, otherwise just stop. */
    POWER_OFF,
}

/** Settings changed for the night only; each is put back by the morning restore. */
@Serializable
enum class NightExtra {
    /** Touch controls off, so a pillow can't pause, skip or change mode. */
    TOUCH_LOCK,

    /** Voice prompts off, so "battery low" can't wake you. */
    PROMPTS_OFF,

    /** The phone's Do Not Disturb for the session (phones only; the app asks for the access). */
    DO_NOT_DISTURB,

    /** Media volume held at or below [SleepPlan.volumeCapPercent] for the session (phones only). */
    VOLUME_CAP,
}

/** Where the sleep sounds play. LEFT_ONLY / RIGHT_ONLY are for side sleepers. */
@Serializable
enum class SoundSide { BOTH, MONO, LEFT_ONLY, RIGHT_ONLY }

/**
 * Tonight's settings, edited on the Sleep tab and kept for next time.
 *
 * [noiseOffAfterMinutes]: null leaves noise cancelling alone, 0 switches it off at the start, a
 * value at or above [durationMinutes] switches it off when the timer ends.
 */
@Serializable
data class SleepPlan(
    val durationMinutes: Int = 60,
    /** Empty = timer only (for a podcast or music from another app). */
    val layers: List<SleepLayer> = listOf(SleepLayer("rain")),
    val noiseOffAfterMinutes: Int? = null,
    val fadeOutMinutes: Int = 10,
    val endAction: SleepEndAction = SleepEndAction.STOP_SOUNDS,
    val nightExtras: Set<NightExtra> = emptySet(),
    /** At the end, also fade out and pause what another app is playing. */
    val pauseOtherAudio: Boolean = false,
    val soundSide: SoundSide = SoundSide.BOTH,
    /** Sleep sounds' own level, 0..1, on top of the media volume. */
    val volume: Float = 0.6f,
    /** With [NightExtra.VOLUME_CAP]: the highest media volume allowed, as a percentage of the maximum. */
    val volumeCapPercent: Int = 40,
) {
    companion object {
        const val MIN_MINUTES = 15
        const val MAX_MINUTES = 6 * 60
        const val MAX_LAYERS = 3

        /** The duration chips; anything in MIN..MAX is allowed through "Custom". */
        val DURATION_CHOICES = listOf(30, 60, 120, 180, 240, 300, 360)

        /** The "Noise cancelling" choices; null = keep, 0 = off at start, MAX_MINUTES = off at the end. */
        val NOISE_OFF_CHOICES = listOf(null, 0, 15, 30, 60, MAX_MINUTES)
    }
}

/** Where the alarm rings. EARBUDS always falls back to the phone (see [SleepAlarm.PHONE_FALLBACK_SECONDS]). */
@Serializable
enum class AlarmOutput { EARBUDS, PHONE, BOTH }

/**
 * A wake-up alarm. [days] uses ISO weekday numbers 1..7 (Monday = 1); empty rings once at the next
 * [hour]:[minute] and then switches itself off.
 *
 * [toneId]: an `AlarmTone.storedId` ("chime", "birds", "classic"), [TONE_SYSTEM] for the phone's
 * own alarm sound, or [TONE_SOUND_PREFIX] + a `SleepSound.storedId` to wake to a sleep sound.
 */
@Serializable
data class SleepAlarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val output: AlarmOutput = AlarmOutput.EARBUDS,
    val toneId: String = "chime",
    /** Minutes before the alarm that a quiet version starts and slowly rises; 0 = off. */
    val wakeWindowMinutes: Int = 0,
    /** Ramp from quiet to full volume once the alarm itself rings. */
    val fadeInSeconds: Int = 30,
    /** Null = no snooze. */
    val snoozeMinutes: Int? = 10,
    /** Vibrate the phone too (only when the phone rings). */
    val vibrate: Boolean = true,
    val label: String? = null,
) {
    companion object {
        const val TONE_SYSTEM = "system"
        const val TONE_SOUND_PREFIX = "sound:"

        /** "Earbuds" moves to the phone when nobody stops it within this long. */
        const val PHONE_FALLBACK_SECONDS = 60
        val WAKE_WINDOW_CHOICES = listOf(0, 10, 20, 30)
        val FADE_IN_CHOICES = listOf(15, 30, 60, 180)
        val SNOOZE_CHOICES = listOf(null, 5, 10, 15)
    }
}

/** A named mix kept for one-tap reuse. */
@Serializable
data class SavedMix(val id: String, val name: String, val layers: List<SleepLayer>)
