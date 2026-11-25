@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.langkurst.ruru.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.DemoPlatform
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.PhoneTab
import io.github.langkurst.ruru.app.eq.EqBackendStatus
import io.github.langkurst.ruru.app.eq.EqStatuses
import io.github.langkurst.ruru.app.eq.FakeEqBackend
import io.github.langkurst.ruru.app.eq.ProEqModel
import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.ui.pages.fixed
import io.github.langkurst.ruru.ui.pages.formatFrequency
import io.github.langkurst.ruru.ui.pages.signedGain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Draws the Equaliser page off screen, wide and narrow, and checks that it is listed and reachable.
 * (The render needs the native Skia library; without it the drawing checks are skipped.)
 */
class EqualiserPageTest {
    private fun withScene(widthDp: Int, backend: FakeEqBackend = FakeEqBackend(), block: (AppModel, ProEqModel, ImageComposeScene?) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = AppModel(DemoPlatform(), scope, startInDemo = true)
        val eq = ProEqModel(MemorySettingsStore(), scope, backend, demoBackend = { backend }, debounceMs = 0, saveMs = 0)
        model.start()
        try {
            runBlocking { withTimeout(20_000) { model.controller.value.state.first { it.phase == Phase.Connected } } }
            val scene = try {
                ImageComposeScene(width = widthDp, height = 1500, density = Density(1f)) {
                    RuruTheme(dark = true) { RuruScreen(model, "0.0.0", proEq = eq) }
                }
            } catch (e: UnsatisfiedLinkError) {
                println("skipped: no native Skia here (${e.message})")
                null
            }
            try {
                scene?.let { for (i in 0..10) it.render(i * 50_000_000L) }
                block(model, eq, scene)
            } finally {
                scene?.close()
            }
        } finally {
            eq.close()
            model.stop()
            scope.cancel()
        }
    }

    private fun ctrl(key: Key) = KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = true)

    @Test
    fun `Ctrl 2 opens the Equaliser, which draws in every editor and state without failing`() = withScene(1100) { model, eq, scene ->
        scene ?: return@withScene
        scene.sendKeyEvent(ctrl(Key.Two))
        assertEquals(DeskPage.Equaliser, model.page.value, "the Equaliser is the second row on Windows")
        val graphic = scene.render(1_000_000_000L).hashCode()
        eq.setEnabled(true)
        eq.setMode(io.github.langkurst.ruru.dsp.ProEqMode.PARAMETRIC)
        eq.addBand()
        for (i in 11..14) scene.render(i * 50_000_000L)
        val parametric = scene.render(2_000_000_000L)
        assertNotEquals(graphic, parametric.hashCode(), "switching the editor changed nothing on screen")
        eq.applyPreset(io.github.langkurst.ruru.dsp.BuiltInPresets.QC_ULTRA_ID)
        for (i in 15..18) scene.render(i * 50_000_000L)
        assertEquals(EqBackendStatus.On, eq.ui.value.status)
    }

    @Test
    fun `the not installed state and the narrow Sound tab draw`() {
        withScene(1100, FakeEqBackend(EqStatuses.apoNotInstalled())) { model, eq, scene ->
            scene ?: return@withScene
            model.page.value = DeskPage.Equaliser
            for (i in 11..16) scene.render(i * 50_000_000L)
            assertTrue(eq.ui.value.status is EqBackendStatus.NeedsSetup)
            assertTrue(!eq.ui.value.status.switchable)
        }
        withScene(400) { model, _, scene ->
            scene ?: return@withScene
            model.tab.value = PhoneTab.Sound
            for (i in 11..16) scene.render(i * 50_000_000L)
        }
    }

    @Test
    fun `numbers are written with a dot, half up, and a real minus sign`() {
        assertEquals("3.5", fixed(3.46, 1))
        assertEquals("-0.5", fixed(-0.5, 1))
        assertEquals("0.0", fixed(-0.04, 1), "no minus on a value that rounds to zero")
        assertEquals("+4.0", signedGain(4.0, 1))
        assertEquals("−2.5", signedGain(-2.5, 1))
        assertEquals("0.0", signedGain(0.0, 1))
        assertEquals("105", formatFrequency(105.0))
        assertEquals("1234.5", formatFrequency(1234.5))
    }
}
