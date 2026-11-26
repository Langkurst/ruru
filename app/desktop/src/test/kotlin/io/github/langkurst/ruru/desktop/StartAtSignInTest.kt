package io.github.langkurst.ruru.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartAtSignInTest {
    private val home = Path.of("/home/someone")

    @Test
    fun `the entry lives where each system looks for it`() {
        assertEquals(home.resolve("Library/LaunchAgents/io.github.langkurst.ruru.plist"), StartAtSignIn.fileFor("Mac OS X", home, null))
        assertEquals(home.resolve(".config/autostart/ruru.desktop"), StartAtSignIn.fileFor("Linux", home, null))
        assertEquals(Path.of("/cfg/autostart/ruru.desktop"), StartAtSignIn.fileFor("Linux", home, "/cfg"))
        assertEquals(home.resolve(".config/autostart/ruru.desktop"), StartAtSignIn.fileFor("Linux", home, "  "), "a blank XDG_CONFIG_HOME is ignored")
        assertNull(StartAtSignIn.fileFor("Windows 11", home, null), "Windows has its registry key instead")
    }

    @Test
    fun `the launch agent starts the app hidden when the person signs in and says nothing else`() {
        val plist = StartAtSignIn.launchAgentPlist("/Applications/Ruru.app/Contents/MacOS/Ruru")
        assertTrue("<key>RunAtLoad</key>\n    <true/>" in plist)
        assertTrue("<string>/Applications/Ruru.app/Contents/MacOS/Ruru</string>\n        <string>--hidden</string>" in plist)
        assertTrue("<string>io.github.langkurst.ruru</string>" in plist)
        assertFalse("KeepAlive" in plist, "it starts once; closing Ruru must not bring it back")
    }

    @Test
    fun `a path with special characters stays one argument`() {
        assertTrue("<string>/Apps/R&amp;D/Ruru</string>" in StartAtSignIn.launchAgentPlist("/Apps/R&D/Ruru"))
        val entry = StartAtSignIn.autostartEntry("/opt/my apps/ruru \"x\" \$HOME/bin/Ruru")
        assertTrue("Exec=\"/opt/my apps/ruru \\\"x\\\" \\\$HOME/bin/Ruru\" --hidden\n" in entry, entry)
        assertTrue(entry.startsWith("[Desktop Entry]\nType=Application\n"))
    }

    @Test
    fun `turning it on writes the file, off removes it and off twice is fine`() {
        val dir = Files.createTempDirectory("ruru-signin")
        try {
            val file = StartAtSignIn.fileFor("Linux", dir, null)!!
            assertFalse(StartAtSignIn.isOn(file))
            assertTrue(StartAtSignIn.set(file, "Linux", "/opt/ruru/bin/Ruru", on = true))
            assertTrue(StartAtSignIn.isOn(file))
            assertTrue(Files.readString(file).contains("Exec=\"/opt/ruru/bin/Ruru\" --hidden"))
            assertTrue(StartAtSignIn.set(file, "Linux", "/opt/ruru/bin/Ruru", on = false))
            assertFalse(StartAtSignIn.isOn(file))
            assertTrue(StartAtSignIn.set(file, "Linux", "/opt/ruru/bin/Ruru", on = false))
            val agent = StartAtSignIn.fileFor("Mac OS X", dir, null)!!
            assertTrue(StartAtSignIn.set(agent, "Mac OS X", "/Applications/Ruru.app/Contents/MacOS/Ruru", on = true))
            assertTrue(Files.readString(agent).startsWith("<?xml"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a folder that cannot be written is reported as not done`() {
        val dir = Files.createTempFile("ruru-signin", ".txt") // a file where a folder is needed
        try {
            val file = dir.resolve("sub").resolve("ruru.desktop")
            assertFalse(StartAtSignIn.set(file, "Linux", "/opt/ruru/bin/Ruru", on = true))
        } finally {
            Files.deleteIfExists(dir)
        }
    }
}
