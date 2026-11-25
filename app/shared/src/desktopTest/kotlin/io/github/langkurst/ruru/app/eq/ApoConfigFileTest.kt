package io.github.langkurst.ruru.app.eq

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-line edit of config.txt, byte for byte, and reading APO's folder from `reg query`. */
class ApoConfigFileTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private val samples = listOf(
        "",
        "# only a comment",
        "# only a comment\n",
        "a\r\nb\r\n",
        "a\nb\nc",
        "a\rb\r",
        "Preamp: -3 dB\r\n\r\n\r\nFilter: ON PK Fc 100 Hz Gain 1 dB Q 1\r\n",
        "Device: Caf\u00e9 Headphones\r\nInclude: other.txt\r\n",
    )

    @Test
    fun `adding and removing the line gives back the original bytes for any ending style`() {
        for (s in samples) {
            val original = bytes(s)
            val added = ApoConfigFile.withInclude(original)
            assertTrue(ApoConfigFile.hasInclude(added), "added for <$s>")
            assertTrue(String(added, Charsets.ISO_8859_1).startsWith("Include: ruru.txt"), "first line for <$s>")
            assertContentEquals(original, ApoConfigFile.withoutInclude(added), "round trip for <$s>")
            assertContentEquals(added, ApoConfigFile.withInclude(added), "adding twice changes nothing for <$s>")
        }
    }

    @Test
    fun `the new line uses the file's own line break`() {
        assertEquals("Include: ruru.txt\nx\n", String(ApoConfigFile.withInclude(bytes("x\n"))))
        assertEquals("Include: ruru.txt\r\nx\r\n", String(ApoConfigFile.withInclude(bytes("x\r\n"))))
        assertEquals("Include: ruru.txt\r\n", String(ApoConfigFile.withInclude(bytes(""))), "an empty file gets Windows breaks")
    }

    @Test
    fun `a UTF-8 byte order mark stays first`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = bom + bytes("Preamp: -1 dB\r\n")
        val added = ApoConfigFile.withInclude(original)
        assertContentEquals(bom, added.copyOfRange(0, 3))
        assertContentEquals(original, ApoConfigFile.withoutInclude(added))
    }

    @Test
    fun `other Include lines and lookalikes are left alone`() {
        val text = "Include: other.txt\r\n# Include: ruru.txt is how Ruru is added\r\nInclude: ruru.txt.bak\r\n"
        assertFalse(ApoConfigFile.hasInclude(bytes(text)))
        assertContentEquals(bytes(text), ApoConfigFile.withoutInclude(bytes(text)))
    }

    @Test
    fun `every copy of the line comes out and nothing else does`() {
        val text = "a\r\nInclude: ruru.txt\r\nb\r\n  include: RURU.TXT  \r\nc\r\n"
        assertEquals("a\r\nb\r\nc\r\n", String(ApoConfigFile.withoutInclude(bytes(text))))
    }

    @Test
    fun `UTF-16 is recognised`() {
        assertTrue(ApoConfigFile.isUtf16(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0)))
        assertTrue(ApoConfigFile.isUtf16(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0x41)))
        assertFalse(ApoConfigFile.isUtf16(bytes("plain")))
        assertFalse(ApoConfigFile.isUtf16(ByteArray(0)))
    }

    @Test
    fun `the folder is read from reg query output`() {
        val out = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\EqualizerAPO\r\n    ConfigPath    REG_SZ    C:\\Program Files\\EqualizerAPO\\config\r\n\r\n"
        assertEquals(File("C:\\Program Files\\EqualizerAPO\\config"), RegistryApoLocator.parseConfigPath(out))
        assertEquals(File("D:\\APO\\config"), RegistryApoLocator.parseConfigPath("    ConfigPath    REG_SZ    D:\\APO\\config\\\r\n"))
        assertNull(RegistryApoLocator.parseConfigPath("ERROR: The system was unable to find the specified registry key or value.\r\n"))
        assertNull(RegistryApoLocator.parseConfigPath("    InstallPath    REG_SZ    C:\\x\r\n"))
    }
}
