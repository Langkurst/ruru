package io.github.langkurst.ruru.android.eq

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import io.github.langkurst.ruru.dsp.DynamicsProcessingPlan
import io.github.langkurst.ruru.dsp.DynamicsProcessingPlan.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why the equaliser is not fully working, as the engine sees it. The backend turns it into words. */
enum class EngineWarning {
    /** The phone's audio stack has no DynamicsProcessing effect at all. */
    Unsupported,

    /** Another app's sound effect holds a session, so the equaliser is off for that app. */
    TakenOver,

    /** The phone refuses the global output mix (the experimental option). */
    GlobalRefused,
}

/** What the engine is doing right now. */
data class EngineStatus(
    val attachedSessions: Set<Int> = emptySet(),
    /**
     * False once the phone has refused to create any DynamicsProcessing effect. Not sticky: a later
     * successful attach (the failure was a passing hiccup) sets it back to true.
     */
    val isAvailable: Boolean = true,
    val warning: EngineWarning? = null,
)

/**
 * One effect on one audio session, as the engine drives it. The real one wraps
 * `android.media.audiofx.DynamicsProcessing` ([AndroidEffects]); tests use a fake. Both throw what the
 * framework throws: [UnsupportedOperationException] when a layout is refused or another app's
 * higher-priority effect has taken the session, [IllegalStateException] once released, a bare
 * [RuntimeException] when audioserver died.
 */
internal interface SessionEffect {
    fun apply(plan: Plan, enabled: Boolean)

    /** Switch off and free the effect. May throw if the handle is already dead. */
    fun release()
}

/** Creates the effect on a session with a given post-EQ band count; throws as the DynamicsProcessing constructor does. */
internal fun interface SessionEffectFactory {
    fun create(sessionId: Int, bandCount: Int): SessionEffect
}

/**
 * Owns one `android.media.audiofx.DynamicsProcessing` per attached audio session (ported from Earshot).
 *
 * Rules that matter (learned across makers' phones):
 *  - An effect is created on the session before or while the player's AudioTrack lives. Creating it is a
 *    binder call that can take tens of milliseconds and can throw a bare [RuntimeException] on phones whose
 *    effect library lacks DynamicsProcessing. Never call from the main thread.
 *  - The layout (band counts, stages in use) is fixed at construction. Changing it means release and
 *    create again, which is what [attach] does when a phone rejects the preferred layout.
 *  - Effects are counted per session across apps: a leaked one keeps running in the audio HAL until the
 *    phone restarts. Every path that drops a session calls `release()`.
 *  - A working effect can stop working: another app takes the session with a higher-priority effect, or
 *    audioserver restarts and the handle dies. [attach] then drops the dead handle and builds a new one;
 *    while that keeps failing the session is "lost" and tried again on every attach.
 *  - Session 0 (the global output mix) is deprecated since Android 12 and most Android 13+ phones refuse it
 *    or give a silent no-op. It is an opt-in, experimental option and a refusal is reported, not hidden.
 *
 * All methods are synchronised.
 */
class DynamicsProcessingEngine internal constructor(
    private val preferredBandCount: Int,
    private val factory: SessionEffectFactory,
) {
    constructor(preferredBandCount: Int = DynamicsProcessingPlan.DEFAULT_BAND_COUNT) : this(preferredBandCount, AndroidEffects)

    private class Attached(val effect: SessionEffect, val bandCount: Int)

    private val lock = Any()
    private val effects = HashMap<Int, Attached>()

    /** Sessions whose working effect broke and could not be rebuilt yet, with the band count that worked. */
    private val lost = HashMap<Int, Int>()

    /** Session 0 was refused; forgotten when it works or the person turns the option off. */
    private var globalRefused = false

    /** Why creating failed while no session had a working effect, or null: the phone lacks the effect. */
    private var unsupportedReason: String? = null

    private val current = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = current

    /** Session ids currently holding an effect. */
    val attached: Set<Int> get() = synchronized(lock) { effects.keys.toSet() }

    /**
     * Create (or reuse) the effect on [sessionId] and apply [planner]'s plan. Returns true if the session
     * now has a working effect. On failure the session is left without one and [status] says why.
     */
    fun attach(sessionId: Int, planner: Planner, enabled: Boolean): Boolean = synchronized(lock) {
        val existing = effects[sessionId]
        if (existing != null) {
            if (applyTo(existing, sessionId, planner, enabled)) return true
            // applyTo released the broken effect. Forget it and build a fresh one below: that works after an
            // audioserver restart; while another app holds control it fails the same way and the session
            // stays lost until a later attach succeeds.
            effects.remove(sessionId)
            lost[sessionId] = existing.bandCount
            publish()
        }
        // The preferred resolution first, then coarser layouts: some phones cap the post-EQ at 16 or 10 bands.
        // A lost session tries only the layout it already had working.
        var lastError: Throwable? = null
        val layouts = lost[sessionId]?.let { listOf(it) } ?: candidateBandCounts()
        for (bandCount in layouts) {
            val effect = try {
                factory.create(sessionId, bandCount)
            } catch (e: RuntimeException) {
                // Every refusal moves on to the next layout, IllegalArgumentException included: the framework
                // throws it for a missing effect type and for a band count the effect HAL caps.
                lastError = e
                continue
            }
            val created = Attached(effect, bandCount)
            if (applyTo(created, sessionId, planner, enabled)) {
                effects[sessionId] = created
                lost.remove(sessionId)
                // A working effect proves the phone supports it, whatever an earlier create said.
                unsupportedReason = null
                if (sessionId == 0) globalRefused = false
                logI("attached to session $sessionId with $bandCount bands")
                publish()
                return true
            }
            lastError = IllegalStateException("the phone rejected the $bandCount-band layout")
        }
        onCreateFailed(sessionId, lastError)
        return false
    }

    /** Release every effect on a session outside [sessionIds] and stop retrying lost ones there. */
    fun retain(sessionIds: Set<Int>) = synchronized(lock) {
        for (id in effects.keys - sessionIds) effects.remove(id)?.let { release(it.effect, id) }
        lost.keys.retainAll(sessionIds)
        publish()
    }

    fun detachAll() = synchronized(lock) {
        for ((session, attached) in effects) release(attached.effect, session)
        effects.clear()
        lost.clear()
        publish()
    }

    /** Forget that the global output mix was refused (the person turned the option off). */
    fun clearWarning() = synchronized(lock) {
        globalRefused = false
        publish()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun candidateBandCounts(): List<Int> =
        (listOf(preferredBandCount) + FALLBACK_BAND_COUNTS).filter { it <= preferredBandCount }.distinct()

    /** Push the plan into [attached]. Returns false (and releases the effect) when the phone rejects it. */
    private fun applyTo(attached: Attached, sessionId: Int, planner: Planner, enabled: Boolean): Boolean {
        val plan = planner.plan(attached.bandCount)
        return try {
            attached.effect.apply(plan, enabled)
            true
        } catch (e: UnsupportedOperationException) {
            logW("session $sessionId rejected the ${attached.bandCount}-band plan: ${e.message}")
            release(attached.effect, sessionId)
            false
        } catch (e: IllegalStateException) {
            logW("session $sessionId effect in a bad state: ${e.message}")
            release(attached.effect, sessionId)
            false
        } catch (e: RuntimeException) {
            logW("session $sessionId apply failed: ${e.message}")
            release(attached.effect, sessionId)
            false
        }
    }

    private fun release(effect: SessionEffect, sessionId: Int) {
        try {
            effect.release()
        } catch (e: RuntimeException) {
            logW("release on session $sessionId threw: ${e.message}")
        }
    }

    private fun onCreateFailed(sessionId: Int, cause: Throwable?) {
        val reason = cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"
        when {
            // It had a working effect a moment ago, so the phone does support it: most likely another app holds the session.
            sessionId in lost -> logW("session $sessionId: effect lost and not rebuilt yet: $reason")
            sessionId == 0 -> {
                // Expected on modern phones; not a sign the engine is broken.
                logW("the global output mix (session 0) was refused: $reason")
                globalRefused = true
            }
            else -> {
                logW("cannot create DynamicsProcessing on session $sessionId: $reason")
                // One refused session next to working ones is a per-app quirk (a session the player already
                // closed). No working session after a real create failure means the phone lacks the effect.
                unsupportedReason = if (effects.isNotEmpty()) null else reason
            }
        }
        publish()
    }

    /** Must be called with [lock] held. The most important explanation wins. */
    private fun publish() {
        current.value = EngineStatus(
            attachedSessions = effects.keys.toSet(),
            isAvailable = unsupportedReason == null,
            warning = when {
                unsupportedReason != null -> EngineWarning.Unsupported
                lost.isNotEmpty() -> EngineWarning.TakenOver
                globalRefused -> EngineWarning.GlobalRefused
                else -> null
            },
        )
    }

    // Wrapped so the engine also runs in plain JVM unit tests, where android.util.Log is a stub that throws.
    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    internal companion object {
        private const val TAG = "Ruru/Dp"
        private val FALLBACK_BAND_COUNTS = listOf(16, 10, 6)
    }
}

/** The real effects: one `DynamicsProcessing` per session. */
private object AndroidEffects : SessionEffectFactory {
    /** A stereo pair. The framework re-configures to the real channel count on first process. */
    private const val STEREO_CHANNELS = 2

    override fun create(sessionId: Int, bandCount: Int): SessionEffect {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            STEREO_CHANNELS,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ false, /* mbcBandCount = */ 0,
            /* postEqInUse = */ true, /* postEqBandCount = */ bandCount,
            /* limiterInUse = */ true,
        ).build() // frame duration left at the framework default
        return AndroidEffect(DynamicsProcessing(/* priority = */ 0, sessionId, config))
    }
}

private class AndroidEffect(private val effect: DynamicsProcessing) : SessionEffect {
    override fun apply(plan: Plan, enabled: Boolean) {
        plan.bands.forEachIndexed { i, band ->
            effect.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(/* enabled = */ true, band.cutoffHz.toFloat(), band.gainDb.toFloat()))
        }
        effect.setInputGainAllChannelsTo(plan.inputGainDb.toFloat())
        val l = plan.limiter
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ l.enabled,
                /* linkGroup = */ 0,
                l.attackMs.toFloat(),
                l.releaseMs.toFloat(),
                l.ratio.toFloat(),
                l.thresholdDb.toFloat(),
                l.postGainDb.toFloat(),
            ),
        )
        effect.enabled = enabled
    }

    override fun release() {
        try {
            effect.enabled = false
        } catch (_: RuntimeException) {
            // already dead; release anyway
        }
        effect.release()
    }
}
