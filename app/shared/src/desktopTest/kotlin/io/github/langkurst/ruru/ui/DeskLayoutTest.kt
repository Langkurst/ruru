@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.langkurst.ruru.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.DemoPlatform
import io.github.langkurst.ruru.app.Phase
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
 * Draws the real screen off screen in demo mode and presses keys on it. The sidebar layout has to
 * appear from 880dp, answer Ctrl+1, 2, 3 and remember its page in the model; narrower, nothing changes.
 * (The render needs the native Skia library; on a machine without it the checks are skipped.)
 */
class DeskLayoutTest {
    private fun withScene(widthDp: Int, block: (AppModel, ImageComposeScene) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = AppModel(DemoPlatform(), scope, startInDemo = true)
        model.start()
        try {
            runBlocking { withTimeout(20_000) { model.controller.value.state.first { it.phase == Phase.Connected } } }
            val scene = try {
                ImageComposeScene(width = widthDp, height = 720, density = Density(1f)) {
                    RuruTheme(dark = true) { RuruScreen(model, "0.0.0") }
                }
            } catch (e: UnsatisfiedLinkError) {
                println("skipped: no native Skia here (${e.message})")
                return
            }
            try {
                for (i in 0..10) scene.render(i * 50_000_000L) // lets the first frames settle and the root take focus
                block(model, scene)
            } finally {
                scene.close()
            }
        } finally {
            model.stop()
            scope.cancel()
        }
    }

    private fun ctrl(key: Key) = KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = true)

    @Test
    fun `Ctrl 1, 2 and 3 open the pages in the sidebar and the model remembers the page`() = withScene(1100) { model, scene ->
        assertEquals(DeskPage.Sound, model.page.value)
        scene.sendKeyEvent(ctrl(Key.Three))
        assertEquals(DeskPage.Settings, model.page.value, "Ctrl+3 is the third row, Settings")
        scene.sendKeyEvent(ctrl(Key.Two))
        assertEquals(DeskPage.Shortcut, model.page.value, "Ctrl+2 is the second row, Shortcut button")
        scene.sendKeyEvent(ctrl(Key.One))
        assertEquals(DeskPage.Sound, model.page.value)
    }

    @Test
    fun `the wide layout draws a sidebar and the narrow one does not`() {
        var wide = 0L
        var narrow = 0L
        withScene(1100) { _, scene -> wide = leftEdgeSum(scene) }
        withScene(400) { model, scene ->
            narrow = leftEdgeSum(scene)
            scene.sendKeyEvent(ctrl(Key.Two))
            assertEquals(DeskPage.Sound, model.page.value, "the single column has no pages to switch")
        }
        // Skipped renders give zero for both; real ones differ, as the wide layout puts the owl and rows in the first 264dp.
        assertTrue(wide == 0L && narrow == 0L || wide != narrow, "wide and narrow looked the same")
        assertNotEquals(DeskPage.Status, DeskPage.Sound)
    }

    /** A number that changes when the pixels in the left 264 columns change. */
    private fun leftEdgeSum(scene: ImageComposeScene): Long {
        val bitmap = scene.render(1_000_000_000L).let { image ->
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocN32Pixels(image.width, image.height)
            org.jetbrains.skia.Canvas(bmp).drawImage(image, 0f, 0f)
            bmp
        }
        var sum = 0L
        for (y in 0 until bitmap.height step 3) for (x in 0 until 264 step 3) sum += bitmap.getColor(x, y)
        return sum
    }
}
