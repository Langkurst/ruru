package io.github.langkurst.ruru.app.settings

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupTest {
    private fun newNode(): Preferences = Preferences.userRoot().node("io/github/langkurst/ruru/test-" + UUID.randomUUID())

    private val nodes = ArrayList<Preferences>()
    private fun freshStore() = DesktopSettingsStore(newNode().also { nodes += it })
    private val store = freshStore()
    private val keys = listOf(
        BackupKey("a.text", SettingType.STRING), BackupKey("a.count", SettingType.INT), BackupKey("a.on", SettingType.BOOLEAN),
        BackupKey("a.level", SettingType.FLOAT), BackupKey("a.list", SettingType.STRING_LIST),
    )

    @AfterTest
    fun cleanUp() = nodes.forEach { it.removeNode() }

    private fun fill(s: SettingsStore) {
        s.putString("a.text", "quote \" and \\ and é\n")
        s.putInt("a.count", 7)
        s.putBoolean("a.on", true)
        s.putFloat("a.level", 0.5f)
        s.putStringList("a.list", listOf("one", "t\"wo"))
    }

    @Test
    fun `settings survive an export and import on another store`() {
        fill(store)
        val text = Backup.export(store, keys)
        val target = freshStore()
        assertIs<BackupImport.Valid>(Backup.import(target, text, keys))
        assertEquals("quote \" and \\ and é\n", target.getString("a.text"))
        assertEquals(7, target.getInt("a.count", 0))
        assertTrue(target.getBoolean("a.on", false))
        assertEquals(0.5f, target.getFloat("a.level", 0f))
        assertEquals(listOf("one", "t\"wo"), target.getStringList("a.list"))
        assertTrue(text.contains("\"version\": 1") && text.contains("\"ruru\": \"backup\""))
    }

    @Test
    fun `a different version is rejected and nothing is applied`() {
        val r = Backup.import(store, """{"ruru":"backup","version":2,"settings":{"a.text":"x"}}""", keys)
        assertIs<BackupImport.Invalid>(r)
        assertFalse(store.contains("a.text"))
        assertIs<BackupImport.Invalid>(Backup.decode("""{"ruru":"other","version":1,"settings":{}}""", keys))
        assertIs<BackupImport.Invalid>(Backup.decode("not json", keys))
    }

    @Test
    fun `a wrong type rejects the whole file and applies none of it`() {
        val r = Backup.import(store, """{"ruru":"backup","version":1,"settings":{"a.text":"fine","a.count":"seven","a.on":1}}""", keys)
        val bad = assertIs<BackupImport.Invalid>(r)
        assertEquals(2, bad.errors.size)
        assertFalse(store.contains("a.text"))
        assertIs<BackupImport.Invalid>(Backup.decode("""{"ruru":"backup","version":1,"settings":{"a.count":1.5}}""", keys))
        assertIs<BackupImport.Invalid>(Backup.decode("""{"ruru":"backup","version":1,"settings":{"a.list":["x",2]}}""", keys))
    }

    @Test
    fun `unknown keys are ignored`() {
        val r = Backup.import(store, """{"ruru":"backup","version":1,"settings":{"a.text":"hi","device.address":"AA:BB","window.x":3}}""", keys)
        val ok = assertIs<BackupImport.Valid>(r)
        assertEquals(listOf("device.address", "window.x"), ok.ignored)
        assertEquals("hi", store.getString("a.text"))
        assertFalse(store.contains("device.address"))
    }

    @Test
    fun `only allow-listed keys are ever exported`() {
        fill(store)
        store.putString("device.address", "AA:BB:CC")
        store.putInt("window.x", 5)
        val text = Backup.export(store, keys)
        assertFalse(text.contains("device.address") || text.contains("AA:BB") || text.contains("window.x"))
        assertFalse(Backup.encode(mapOf("device.serial" to "123", "a.text" to "ok"), keys).contains("serial"))
        // The real list carries no address, serial or window setting.
        assertTrue(BackupKeys.all.none { k -> listOf("address", "serial", "window", "mac").any { k.key.contains(it) } })
    }

    @Test
    fun `a store with no node holds nothing and does not throw`() {
        val off = DesktopSettingsStore(null)
        off.putInt("a.count", 3)
        assertEquals(9, off.getInt("a.count", 9))
        assertFalse(off.contains("a.count"))
    }
}
