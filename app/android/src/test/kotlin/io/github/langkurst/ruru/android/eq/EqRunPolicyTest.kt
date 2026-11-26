package io.github.langkurst.ruru.android.eq

import android.media.AudioDeviceInfo
import android.os.Build
import io.github.langkurst.ruru.dsp.ParametricBand
import io.github.langkurst.ruru.dsp.ProEqMode
import io.github.langkurst.ruru.dsp.ProEqState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the equaliser runs, on which sessions, and the plan it hands to Android. */
class EqRunPolicyTest {
    private fun session(id: Int) = AudioSession(id, null, 0)

    @Test
    fun `off runs nothing and is not paused`() {
        val d = EqRunPolicy.decide(enabled = false, bluetoothHeadphones = true, sessions = listOf(session(5)), globalMix = true)
        assertEquals(emptySet(), d.targets)
        assertFalse(d.pausedByRoute)
        assertFalse(d.waitingForApp)
    }

    @Test
    fun `on without Bluetooth headphones is paused and touches no session`() {
        val d = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = false, sessions = listOf(session(5)), globalMix = true)
        assertEquals(emptySet(), d.targets)
        assertTrue(d.pausedByRoute)
    }

    @Test
    fun `on with headphones runs on every announced session`() {
        val d = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = true, sessions = listOf(session(5), session(7)), globalMix = false)
        assertEquals(setOf(5, 7), d.targets)
        assertFalse(d.pausedByRoute)
        assertFalse(d.waitingForApp)
    }

    @Test
    fun `on with headphones but no announced session waits for an app`() {
        val d = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = true, sessions = emptyList(), globalMix = false)
        assertEquals(emptySet(), d.targets)
        assertTrue(d.waitingForApp)
    }

    @Test
    fun `the global mix is session 0 and only when the option is on`() {
        val on = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = true, sessions = listOf(session(5)), globalMix = true)
        assertEquals(setOf(0, 5), on.targets)
        val noHeadphones = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = false, sessions = emptyList(), globalMix = true)
        assertEquals(emptySet(), noHeadphones.targets, "the option never overrides the route check")
        val alone = EqRunPolicy.decide(enabled = true, bluetoothHeadphones = true, sessions = emptyList(), globalMix = true)
        assertEquals(setOf(0), alone.targets)
        assertFalse(alone.waitingForApp)
    }

    @Test
    fun `A2DP is headphones, LE audio only from Android 12, a phone call link and the speaker are not`() {
        val a2dp = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val le = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertTrue(EqRunPolicy.isBluetoothHeadphones(a2dp, sdk = 28))
        assertTrue(EqRunPolicy.isBluetoothHeadphones(le, sdk = Build.VERSION_CODES.S))
        assertFalse(EqRunPolicy.isBluetoothHeadphones(le, sdk = Build.VERSION_CODES.R))
        assertFalse(EqRunPolicy.isBluetoothHeadphones(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), sdk = 34))
        assertFalse(EqRunPolicy.isBluetoothHeadphones(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES), sdk = 34))
        assertTrue(EqRunPolicy.isBluetoothHeadphones(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), sdk = 34))
    }

    @Test
    fun `the plan carries the bands that are on, the limiter's preamp and the limiter switch`() {
        val flat = EqPlanner.planner(ProEqState(enabled = true), 48_000).plan(32)
        assertEquals(32, flat.bandCount)
        assertTrue(flat.bands.all { kotlin.math.abs(it.gainDb) < 0.01 }, "a flat curve changes nothing")
        assertEquals(0.0, flat.inputGainDb, 0.001)
        assertTrue(flat.limiter.enabled)

        val boosted = ProEqState(
            enabled = true,
            mode = ProEqMode.PARAMETRIC,
            bands = listOf(ParametricBand(100.0, 6.0, 1.0), ParametricBand(5000.0, 4.0, 1.0, enabled = false)),
            limiterEnabled = false,
        )
        val plan = EqPlanner.planner(boosted, 44_100).plan(16)
        assertEquals(16, plan.bandCount)
        assertEquals(44_100, plan.sampleRate)
        assertEquals(boosted.effectivePreampDb(), plan.inputGainDb, 0.001)
        assertTrue(plan.inputGainDb < 0.0, "a +6 dB boost lowers the input so it cannot clip")
        assertFalse(plan.limiter.enabled)
        // The band switched off does not move the curve: the response near 5 kHz is ~0, near 100 Hz about +6.
        val near100 = plan.bands.minBy { kotlin.math.abs(it.cutoffHz - 100.0) }
        val near5k = plan.bands.minBy { kotlin.math.abs(it.cutoffHz - 5000.0) }
        assertTrue(near100.gainDb > 3.0, "bass boost reaches the bands, got ${near100.gainDb}")
        assertTrue(kotlin.math.abs(near5k.gainDb) < 1.0, "a disabled band is left out, got ${near5k.gainDb}")
    }
}
