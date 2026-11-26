package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.core.ModeResolver
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.CancellationException

/**
 * What a night's log line or an ability note means. The screens turn each code into words in the
 * person's language; nothing here carries English text.
 */
enum class SleepNoteCode {
    // Why a step can't happen on this headset.
    NO_DRIVER, CONTROLS_OFFLINE, NOISE_NONE, TOUCH_NONE, PROMPTS_NONE, NO_HEADSET,
    DISCONNECT_UNSUPPORTED, DISCONNECT_NOT_LINKED, POWER_OFF_NONE, POWER_OFF_FALLBACK,

    // What a step did.
    TOUCH_LOCKED, TOUCH_FAILED, PROMPTS_OFF, PROMPTS_FAILED, NOISE_OFF, NOISE_FAILED, NOISE_ALREADY_OFF,
    POWERED_OFF, POWER_OFF_REFUSED, DISCONNECTED, DISCONNECT_FAILED, RESTORED,
}

/** One night step's outcome, for tonight's log. */
data class StepNote(val code: SleepNoteCode, val isProblem: Boolean = false, val detail: String? = null)

/** Whether the current headset can do one night step, and if not, why. */
data class SleepAbility(val available: Boolean, val why: SleepNoteCode? = null)

/** What the Sleep screens know about what this headset can do tonight. */
data class HeadsetAbilities(
    val noiseOff: SleepAbility = SleepAbility(false),
    val disconnect: SleepAbility = SleepAbility(false),
    val powerOff: SleepAbility = SleepAbility(false),
    val touchLock: SleepAbility = SleepAbility(false),
    val promptsOff: SleepAbility = SleepAbility(false),
    /** The phone can page the headset again (before an earbud alarm) after a disconnect. */
    val canReconnect: Boolean = false,
)

/** The phone's connect / disconnect of a linked headset, as the night needs it (a seam for each platform and for tests). */
interface HeadsetLink {
    val canConnect: Boolean
    fun isLinked(address: String): Boolean
    fun connect(address: String)
    fun disconnect(address: String)
}

/**
 * Runs one [HeadsetCommand] on the connected headset (the app's controller). It throws when the
 * headset's session refuses, as `HeadsetSession.run` does; this class turns a refusal into a note.
 */
fun interface HeadsetCommandRunner {
    suspend fun run(command: HeadsetCommand)
}

/** What the night changed on the headset and the phone, to put back in the morning. Null fields were not changed. */
@kotlinx.serialization.Serializable
data class RestoreRecord(
    val address: String? = null,
    /** The listening mode before noise cancelling went off, by meaning, never a bare slot. */
    val mode: ModeRef? = null,
    val ancEnabled: Boolean? = null,
    val touchControlsEnabled: Boolean? = null,
    val voicePromptsEnabled: Boolean? = null,
    /** Vendor prompt switches (ids starting with `prompt_`) and their values before the night. */
    val vendorPrompts: Map<String, Int> = emptyMap(),
    /** Phone side, filled and read by the Android runtime: the interruption filter before Do Not Disturb. */
    val interruptionFilter: Int? = null,
    val ourInterruptionFilter: Int? = null,
    /** Phone side: the media volume index before the cap, and the output it belongs to. */
    val musicVolume: Int? = null,
    val musicVolumeOutput: String? = null,
    /** When to restore if no alarm does it first. */
    val restoreAtMillis: Long = 0L,
    /** Morning has come but the headset was away: restore as soon as its controls connect again. */
    val due: Boolean = false,
    /** This restore has had its one connect; what is left waits for a connect the person makes. */
    val connectTried: Boolean = false,
) {
    val headsetPart: Boolean
        get() = mode != null || ancEnabled != null || touchControlsEnabled != null || voicePromptsEnabled != null || vendorPrompts.isNotEmpty()
    val phonePart: Boolean get() = interruptionFilter != null || musicVolume != null
    val isEmpty: Boolean get() = !headsetPart && !phonePart
}

/**
 * The headset half of a night, for any brand, through [HeadsetCommand] intents only: no vendor
 * code here. Modes are chosen by meaning ([ModeResolver]); a step the session refuses becomes a
 * [StepNote], never an exception, so one missing feature can't stop the night.
 */
class SleepHeadsetActions(
    private val runner: HeadsetCommandRunner,
    private val link: HeadsetLink? = null,
) {
    /** How noise cancelling goes off on this headset. */
    sealed interface NoiseOffPlan {
        data class Mode(val slot: Int) : NoiseOffPlan
        data object AncSwitch : NoiseOffPlan
        data object AlreadyOff : NoiseOffPlan
    }

    /** Null when this headset can't switch noise cancelling off from an app. */
    fun noiseOffPlan(s: HeadphoneState): NoiseOffPlan? {
        if (!s.isConnected || !s.capabilities.ownSettings) return null
        val current = s.currentMode
        if (current != null && ModeResolver.meaningOf(current) == ModeMeaning.OFF) return NoiseOffPlan.AlreadyOff
        // A mode that means Off first (Sleep A30, Crusher, Sony, Sennheiser, Soundcore Normal)...
        if (s.capabilities.listeningModes) {
            ModeResolver.slotFor(ModeRef(ModeMeaning.OFF), s)?.let { return NoiseOffPlan.Mode(it) }
        }
        // ...else the noise switch (the QC Ultra 2 has no Off mode but has the ANC byte). Only where
        // noise changes are live: elsewhere the change would rewrite the active custom mode.
        val noise = s.noise
        if (noise != null && s.capabilities.noiseCancelling && s.capabilities.ancToggle && s.capabilities.liveNoiseControl && !s.capabilities.ancAlwaysOn) {
            return if (noise.ancEnabled) NoiseOffPlan.AncSwitch else NoiseOffPlan.AlreadyOff
        }
        return null
    }

    fun abilities(s: HeadphoneState): HeadsetAbilities {
        val address = s.connection.deviceAddress
        val own = s.capabilities.ownSettings
        val driven = s.isConnected

        fun ownStep(available: Boolean, none: SleepNoteCode): SleepAbility = when {
            !own -> SleepAbility(false, SleepNoteCode.NO_DRIVER)
            !driven -> SleepAbility(false, SleepNoteCode.CONTROLS_OFFLINE)
            available -> SleepAbility(true)
            else -> SleepAbility(false, none)
        }

        val l = link
        val disconnect = when {
            address == null -> SleepAbility(false, SleepNoteCode.NO_HEADSET)
            l == null || !l.canConnect -> SleepAbility(false, SleepNoteCode.DISCONNECT_UNSUPPORTED)
            !l.isLinked(address) -> SleepAbility(false, SleepNoteCode.DISCONNECT_NOT_LINKED)
            else -> SleepAbility(true)
        }
        val powerOff = when {
            !own -> SleepAbility(false, SleepNoteCode.POWER_OFF_NONE)
            !driven -> SleepAbility(false, SleepNoteCode.CONTROLS_OFFLINE)
            // Only some makers' sessions can (Bose, Sony); the others refuse and the night falls back to disconnect.
            else -> SleepAbility(true, SleepNoteCode.POWER_OFF_FALLBACK)
        }
        return HeadsetAbilities(
            noiseOff = ownStep(noiseOffPlan(s) != null, SleepNoteCode.NOISE_NONE),
            disconnect = disconnect,
            powerOff = powerOff,
            touchLock = ownStep(s.touchControlsEnabled != null, SleepNoteCode.TOUCH_NONE),
            promptsOff = ownStep(s.voicePrompts != null || promptControls(s).isNotEmpty(), SleepNoteCode.PROMPTS_NONE),
            canReconnect = address != null && l != null && l.canConnect && l.isLinked(address),
        )
    }

    /** Vendor switches for spoken prompts (the Sleep A30's `prompt_*` controls), currently on. */
    private fun promptControls(s: HeadphoneState) =
        s.vendorControls.filter { it.id.startsWith(PROMPT_PREFIX) && it.options == null && it.range == null && it.value != null }

    /** Touch lock and prompts off at the start of the night; returns what to restore. */
    suspend fun applyNightExtras(plan: SleepPlan, s: HeadphoneState, record: RestoreRecord, notes: MutableList<StepNote>): RestoreRecord {
        var r = record.copy(address = record.address ?: s.connection.deviceAddress)
        if (NightExtra.TOUCH_LOCK in plan.nightExtras) {
            when {
                s.touchControlsEnabled == true -> step(notes, SleepNoteCode.TOUCH_LOCKED, SleepNoteCode.TOUCH_FAILED) {
                    runner.run(HeadsetCommand.SetTouchControls(false))
                    r = r.copy(touchControlsEnabled = true)
                }
                s.touchControlsEnabled == false -> Unit // already off: nothing to restore
                else -> notes += StepNote(abilities(s).touchLock.why ?: SleepNoteCode.TOUCH_NONE, isProblem = true)
            }
        }
        if (NightExtra.PROMPTS_OFF in plan.nightExtras) {
            val prompts = s.voicePrompts
            val vendor = promptControls(s).filter { it.value != 0 }
            if (prompts == null && promptControls(s).isEmpty()) {
                notes += StepNote(abilities(s).promptsOff.why ?: SleepNoteCode.PROMPTS_NONE, isProblem = true)
            }
            if (prompts?.enabled == true) step(notes, SleepNoteCode.PROMPTS_OFF, SleepNoteCode.PROMPTS_FAILED) {
                runner.run(HeadsetCommand.SetVoicePrompts(false))
                r = r.copy(voicePromptsEnabled = true)
            }
            if (vendor.isNotEmpty()) {
                val turnedOff = HashMap<String, Int>()
                for (control in vendor) {
                    if (attempt { runner.run(HeadsetCommand.SetVendorControl(control.id, 0)) }) turnedOff[control.id] = control.value ?: 1
                }
                if (turnedOff.isNotEmpty()) {
                    r = r.copy(vendorPrompts = r.vendorPrompts + turnedOff)
                    if (prompts?.enabled != true) notes += StepNote(SleepNoteCode.PROMPTS_OFF)
                }
                if (turnedOff.size < vendor.size) notes += StepNote(SleepNoteCode.PROMPTS_FAILED, isProblem = true)
            }
        }
        return r
    }

    /** Noise cancelling off now; returns the record with what to put back. */
    suspend fun noiseOff(s: HeadphoneState, record: RestoreRecord, notes: MutableList<StepNote>): RestoreRecord {
        val plan = noiseOffPlan(s)
        if (plan == null) {
            notes += StepNote(abilities(s).noiseOff.why ?: SleepNoteCode.NOISE_NONE, isProblem = true)
            return record
        }
        var r = record.copy(address = record.address ?: s.connection.deviceAddress)
        when (plan) {
            NoiseOffPlan.AlreadyOff -> notes += StepNote(SleepNoteCode.NOISE_ALREADY_OFF)
            is NoiseOffPlan.Mode -> step(notes, SleepNoteCode.NOISE_OFF, SleepNoteCode.NOISE_FAILED) {
                // Remember the mode by meaning, never a bare slot, so the morning picks it again.
                val before = s.currentMode?.let { ModeResolver.refFor(it, s.connection.deviceAddress) }
                runner.run(HeadsetCommand.SetMode(plan.slot))
                if (r.mode == null) r = r.copy(mode = before)
            }
            NoiseOffPlan.AncSwitch -> step(notes, SleepNoteCode.NOISE_OFF, SleepNoteCode.NOISE_FAILED) {
                runner.run(HeadsetCommand.SetNoise(s.noise!!.copy(ancEnabled = false)))
                if (r.ancEnabled == null) r = r.copy(ancEnabled = true)
            }
        }
        return r
    }

    /**
     * The end of the timer, as the rules in [EndActionRules.steps] order it: switch off, else
     * disconnect, else nothing. Returns true when the headset was sent away, so the caller keeps the
     * morning restore from connecting it straight back.
     */
    suspend fun endAction(action: SleepEndAction, s: HeadphoneState, notes: MutableList<StepNote>): Boolean {
        val address = s.connection.deviceAddress
        for (end in EndActionRules.steps(action)) {
            when (end) {
                EndStep.POWER_OFF -> {
                    val off = s.isConnected && s.capabilities.ownSettings && attempt { runner.run(HeadsetCommand.PowerOff) }
                    if (off) {
                        notes += StepNote(SleepNoteCode.POWERED_OFF)
                        return true
                    }
                    notes += StepNote(SleepNoteCode.POWER_OFF_REFUSED)
                }
                EndStep.DISCONNECT -> return disconnect(address, notes)
            }
        }
        return false
    }

    /** False when nothing was disconnected (no link to do it with); true once the headset was asked to go. */
    private fun disconnect(address: String?, notes: MutableList<StepNote>): Boolean {
        val l = link
        if (address == null || l == null || !l.canConnect || !l.isLinked(address)) {
            val why = if (address != null && (l == null || !l.canConnect)) SleepNoteCode.DISCONNECT_UNSUPPORTED else SleepNoteCode.DISCONNECT_NOT_LINKED
            notes += StepNote(why, isProblem = true)
            return false
        }
        runCatching { l.disconnect(address) }
            .onSuccess { notes += StepNote(SleepNoteCode.DISCONNECTED) }
            .onFailure { notes += StepNote(SleepNoteCode.DISCONNECT_FAILED, isProblem = true, detail = it.message) }
        return true
    }

    /** Pages a headset that was disconnected for the night (before an earbud alarm). Best effort. */
    fun reconnect(address: String): Boolean {
        val l = link ?: return false
        return l.canConnect && l.isLinked(address) && runCatching { l.connect(address) }.isSuccess
    }

    /**
     * True once [s] is [record]'s headset with its settings read: a session publishes "connected"
     * before it has read the modes and noise settings, and restoring into that empty state would
     * find no mode to go back to.
     */
    fun readyToRestore(record: RestoreRecord, s: HeadphoneState): Boolean {
        val address = s.connection.deviceAddress ?: return false
        if (!s.isConnected || (record.address != null && !record.address.equals(address, ignoreCase = true))) return false
        if (record.mode != null && s.modes.none { it.isConfigured }) return false
        if (record.ancEnabled != null && s.noise == null) return false
        if (record.touchControlsEnabled != null && s.touchControlsEnabled == null) return false
        if (record.voicePromptsEnabled != null && s.voicePrompts == null) return false
        return true
    }

    /**
     * Puts back the headset settings in [record] when [s] is that headset with its settings read.
     * A field is cleared only when its command worked; what is left stays pending for the next
     * time the headset connects.
     */
    suspend fun restore(record: RestoreRecord, s: HeadphoneState, notes: MutableList<StepNote>): RestoreRecord {
        if (!record.headsetPart || !readyToRestore(record, s)) return record
        var r = record
        r.mode?.let { ref ->
            val slot = ModeResolver.slotFor(ref, s)
            // A headset that no longer has that mode can't get it back: drop it rather than retry forever.
            if (slot == null || attempt { runner.run(HeadsetCommand.SetMode(slot)) }) r = r.copy(mode = null)
        }
        if (r.ancEnabled == true) {
            val noise = s.noise
            if (noise == null || attempt { runner.run(HeadsetCommand.SetNoise(noise.copy(ancEnabled = true))) }) r = r.copy(ancEnabled = null)
        }
        if (r.touchControlsEnabled == true && attempt { runner.run(HeadsetCommand.SetTouchControls(true)) }) r = r.copy(touchControlsEnabled = null)
        if (r.voicePromptsEnabled == true && attempt { runner.run(HeadsetCommand.SetVoicePrompts(true)) }) r = r.copy(voicePromptsEnabled = null)
        val prompts = r.vendorPrompts.filterNot { (id, value) -> attempt { runner.run(HeadsetCommand.SetVendorControl(id, value)) } }
        r = r.copy(vendorPrompts = prompts)
        notes += StepNote(SleepNoteCode.RESTORED, isProblem = r.headsetPart)
        return r
    }

    /** Runs a headset command; false when the session refused. Cancellation is never swallowed. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun step(notes: MutableList<StepNote>, ok: SleepNoteCode, failed: SleepNoteCode, block: suspend () -> Unit) {
        if (attempt(block)) notes += StepNote(ok) else notes += StepNote(failed, isProblem = true)
    }

    companion object {
        /** Vendor controls with this id prefix are spoken-prompt switches (a naming convention, not a vendor reference). */
        const val PROMPT_PREFIX = "prompt_"
    }
}
