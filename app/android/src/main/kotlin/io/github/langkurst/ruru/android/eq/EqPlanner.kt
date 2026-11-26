package io.github.langkurst.ruru.android.eq

import io.github.langkurst.ruru.dsp.DynamicsProcessingPlan
import io.github.langkurst.ruru.dsp.ProEqState

/** Produces a plan for whatever band count the phone ended up accepting. */
fun interface Planner {
    fun plan(bandCount: Int): DynamicsProcessingPlan.Plan
}

/** Turns what the Equaliser page edits into what Android's DynamicsProcessing is told. Pure. */
object EqPlanner {
    /**
     * The plan for [state]: the bands that are switched on, the preamp the limiter allows
     * ([ProEqState.effectivePreampDb]) as the effect's input gain, and the limiter on or off. Ruru has no
     * loudness boost, so that part of the plan is 0.
     */
    fun planner(state: ProEqState, sampleRate: Int): Planner {
        val bands = state.outputBands()
        val preamp = state.effectivePreampDb()
        val limiter = state.limiterEnabled
        return Planner { n -> DynamicsProcessingPlan.plan(bands, preamp, 0.0, limiter, n, sampleRate) }
    }
}
