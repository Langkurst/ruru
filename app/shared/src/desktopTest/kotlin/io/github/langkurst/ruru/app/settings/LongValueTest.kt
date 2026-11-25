package io.github.langkurst.ruru.app.settings

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A text past the 8 KB prefs limit (many saved equaliser presets) is kept whole, not dropped. */
class LongValueTest {
    private val node: Preferences = Preferences.userRoot().node("io/github/langkurst/ruru/test-" + UUID.randomUUID())
    private val store = DesktopSettingsStore(node)

    @AfterTest
    fun cleanUp() = node.removeNode()

    @Test
    fun `a long text is stored in pieces and read back whole`() {
        val long = (0 until 30_000).joinToString("") { ('a' + it % 26).toString() }
        store.putString("eq.long", long)
        assertEquals(long, store.getString("eq.long"))
        assertTrue(store.contains("eq.long"))
        assertEquals(long, DesktopSettingsStore(node).getString("eq.long"), "a second reader of the same node sees it")
    }

    @Test
    fun `a list that grows past the limit and shrinks again leaves no pieces behind`() {
        store.putStringList("eq.list", List(3) { "x".repeat(6_000) })
        assertEquals(3, store.getStringList("eq.list").size)
        store.putStringList("eq.list", listOf("short"))
        assertEquals(listOf("short"), store.getStringList("eq.list"))
        assertFalse(node.keys().any { it.contains(".part") }, "old pieces are removed: ${node.keys().toList()}")
        store.putString("eq.long", "y".repeat(20_000))
        store.remove("eq.long")
        assertNull(store.getString("eq.long"))
        assertFalse(node.keys().any { it.contains(".part") })
    }

    @Test
    fun `a short text is stored as it was before`() {
        store.putString("eq.short", "hello")
        assertEquals("hello", node.get("eq.short", null))
    }
}
