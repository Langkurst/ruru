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
import io.github.langkurst.ruru.app.PhoneTab
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import io.github.langkurst.ruru.ui.pages.PageAvailability
import io.github.langkurst.ruru.ui.pages.SystemKind
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
 * appear from 880dp, answer Ctrl+1 to 6 and remember its page in the model; narrower, nothing changes.
 * (The render needs the native Skia library; on a machine without it the checks are skipped.)
 */
class DeskLayoutTest {
    private fun withScene(widthDp: Int, pages: PageAvailability? = null, block: (AppModel, ImageComposeScene) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = AppModel(DemoPlatform(), scope, startInDemo = true)
        model.start()
        try {
            runBlocking { withTimeout(20_000) { model.controller.value.state.first { it.phase == Phase.Connected } } }
            val scene = try {
                ImageComposeScene(width = widthDp, height = 720, density = Density(1f)) {
                    RuruTheme(dark = true) { RuruScreen(model, "0.0.0", pages = pages) }
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
    fun `Ctrl 1 to 4 open Sound, Devices, Settings and Tools and the model remembers the page`() = withScene(1100) { model, scene ->
        assertEquals(DeskPage.Sound, model.page.value)
        scene.sendKeyEvent(ctrl(Key.Two))
        assertEquals(DeskPage.Devices, model.page.value, "Ctrl+2 is the second row, Devices (the demo headphones report two paired devices)")
        scene.sendKeyEvent(ctrl(Key.Three))
        assertEquals(DeskPage.Settings, model.page.value)
        scene.sendKeyEvent(ctrl(Key.Four))
        assertEquals(DeskPage.Tools, model.page.value)
        scene.sendKeyEvent(ctrl(Key.Five))
        assertEquals(DeskPage.Tools, model.page.value, "there is no fifth row")
        scene.sendKeyEvent(ctrl(Key.One))
        assertEquals(DeskPage.Sound, model.page.value)
    }

    @Test
    fun `Ctrl 1 to 6 open the available pages in sidebar order`() {
        val all = PageAvailability(DeskPage.entries.toSet(), SystemKind.Windows)
        withScene(1100, all) { model, scene ->
            val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)
            val order = listOf(DeskPage.Sound, DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Settings, DeskPage.Tools)
            for (i in keys.indices.reversed()) {
                scene.sendKeyEvent(ctrl(keys[i]))
                assertEquals(order[i], model.page.value, "Ctrl+${i + 1}")
            }
        }
    }

    @Test
    fun `the narrow layout has a bottom bar whose tabs switch the column`() {
        withScene(400) { model, scene ->
            assertEquals(PhoneTab.Headphones, model.tab.value)
            // Headphones and More share the 400dp: More is the right half of the 56dp strip at the bottom.
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 700f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 700f))
            for (i in 11..14) scene.render(i * 50_000_000L)
            assertEquals(PhoneTab.More, model.tab.value, "the More tab did not open")
        }
    }

    @Test
    fun `the narrow layout draws a line above the bottom bar and the wide one does not`() {
        var wide = 0L
        var narrow = 0L
        withScene(1100) { _, scene -> wide = barLineSum(scene) }
        withScene(400) { _, scene -> narrow = barLineSum(scene) }
        // Skipped renders give zero for both; real ones differ along the row where the 1px line is.
        assertTrue(wide == 0L && narrow == 0L || wide != narrow, "the narrow layout shows no bottom bar line")
    }

    /** A number that changes with the pixels of the row where the bottom bar's top line is (720 - 56). */
    private fun barLineSum(scene: ImageComposeScene): Long {
        val bitmap = scene.render(1_000_000_000L).let { image ->
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocN32Pixels(image.width, image.height)
            org.jetbrains.skia.Canvas(bmp).drawImage(image, 0f, 0f)
            bmp
        }
        var sum = 0L
        for (x in 0 until 200 step 5) sum += bitmap.getColor(x, 720 - 56)
        return sum
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
