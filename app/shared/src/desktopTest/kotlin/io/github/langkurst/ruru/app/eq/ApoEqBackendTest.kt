package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.dsp.ApoConfigWriter
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Equalizer APO backend against a temporary folder standing in for APO's config folder: Ruru's one
 * line in config.txt, the file it writes, what it asks first, and what it does when it cannot write.
 * No real APO folder is read or touched.
 */
class ApoEqBackendTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val settings = MemorySettingsStore()

    /** A config.txt as Equalizer APO's Configurator leaves one: Windows line breaks, a device section, a user's own filter. */
    private val original = ("# Configurator wrote this\r\n" + "Device: Speakers\r\n" + "Preamp: -2 dB\r\n" + "Filter: ON PK Fc 60 Hz Gain 3 dB Q 1.0\r\n").toByteArray()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruru-apo-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(dir, "config.txt").writeBytes(original)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        File(dir, "config.txt").setWritable(true)
        dir.deleteRecursively()
    }

    private fun backend(folder: File? = dir) = ApoEqBackend(settings, scope, locator = { folder })

    private val config get() = File(dir, "config.txt")
    private val ruru get() = File(dir, ApoConfigWriter.FILE_NAME)

    private val on = ProEqState(enabled = true).withGraphicGain(2, 4.0).withGraphicGain(6, -3.0)

    @Test
    fun `nothing is written until the person agrees, then config txt gains exactly one line at the top`() = runBlocking {
        val b = backend()
        b.detect()
        assertEquals(EqBackendStatus.Ready, b.status.value)
        b.apply(on)
        val ask = (b.status.value as EqBackendStatus.NeedsSetup).action
        assertEquals(EqSetupKind.ALLOW, ask.kind)
        assertEquals(Words("eq_apo_consent", dir.path), ask.message, "the folder it will write to is named")
        assertContentEquals(original, config.readBytes(), "config.txt is untouched before consent")
        assertFalse(ruru.exists(), "ruru.txt is not written before consent")

        b.perform(ask)
        waitFor { b.status.value == EqBackendStatus.On }
        val expected = "Include: ruru.txt\r\n".toByteArray() + original
        assertContentEquals(expected, config.readBytes(), "golden config.txt after switching on")
        assertEquals(ApoConfigWriter.write(on), ruru.readText())
        assertTrue(ruru.readText().startsWith(ApoConfigWriter.HEADER))
    }

    @Test
    fun `switching on twice keeps one include line, and a later change only rewrites ruru txt`() = runBlocking {
        settings.putBoolean(ApoEqBackend.CONSENT_KEY, true)
        val b = backend()
        b.apply(on)
        val afterFirst = config.readBytes()
        val changed = on.withGraphicGain(4, 6.0)
        b.apply(changed)
        assertContentEquals(afterFirst, config.readBytes(), "config.txt is not rewritten for a new curve")
        assertEquals(1, String(config.readBytes()).lines().count { it.trim() == "Include: ruru.txt" })
        assertEquals(ApoConfigWriter.write(changed), ruru.readText())
    }

    @Test
    fun `switching off takes out only that line and removes ruru txt, giving back the original bytes`() = runBlocking {
        settings.putBoolean(ApoEqBackend.CONSENT_KEY, true)
        val b = backend()
        b.apply(on)
        assertEquals(EqBackendStatus.On, b.status.value)
        b.off()
        assertContentEquals(original, config.readBytes(), "golden config.txt after switching off")
        assertFalse(ruru.exists())
        assertEquals(EqBackendStatus.Ready, b.status.value)
        // Applying a state that is off does the same.
        b.apply(on)
        b.apply(on.copy(enabled = false))
        assertContentEquals(original, config.readBytes())
    }

    @Test
    fun `an include line that is already there is found, and the status says On`() = runBlocking {
        config.writeBytes("Include: ruru.txt\r\n".toByteArray() + original)
        val b = backend()
        b.detect()
        assertEquals(EqBackendStatus.On, b.status.value)
    }

    @Test
    fun `no APO gives a button that opens its website, and nothing is created`() = runBlocking {
        val b = backend(folder = null)
        b.apply(on)
        val action = (b.status.value as EqBackendStatus.NeedsSetup).action
        assertEquals(EqSetupKind.OPEN_WEBSITE, action.kind)
        assertEquals("https://equalizerapo.com/", action.url)
        assertTrue(b.status.value == EqStatuses.apoNotInstalled())
        val gone = backend(folder = File(dir, "missing"))
        gone.detect()
        assertTrue(gone.status.value == EqStatuses.apoNotInstalled(), "a registry path to a folder that is gone is the same as not installed")
        assertFalse(ruru.exists())
    }

    @Test
    fun `a folder without config txt says to open the Configurator`() = runBlocking {
        config.delete()
        val b = backend()
        b.detect()
        val action = (b.status.value as EqBackendStatus.NeedsSetup).action
        assertEquals(EqSetupKind.CHECK_AGAIN, action.kind)
        assertEquals(Words("eq_apo_no_config", dir.path), action.message)
    }

    @Test
    fun `a config txt Ruru cannot write says how to fix it and writes nothing`() = runBlocking {
        settings.putBoolean(ApoEqBackend.CONSENT_KEY, true)
        config.setReadOnly()
        if (Files.isWritable(config.toPath())) {
            println("skipped: this account can write read-only files")
            return@runBlocking
        }
        val b = backend()
        b.apply(on)
        val action = (b.status.value as EqBackendStatus.NeedsSetup).action
        assertEquals(EqSetupKind.CHECK_AGAIN, action.kind)
        assertEquals(Words("eq_apo_cant_write", dir.path), action.message)
        assertFalse(ruru.exists(), "no half-done write")
        config.setWritable(true)
        b.refresh()
        waitFor { b.status.value == EqBackendStatus.Ready }
    }

    @Test
    fun `a UTF-16 config txt is refused rather than damaged`() = runBlocking {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Preamp: -1 dB\r\n".toByteArray(Charsets.UTF_16LE)
        config.writeBytes(utf16)
        val b = backend()
        settings.putBoolean(ApoEqBackend.CONSENT_KEY, true)
        b.apply(on)
        assertTrue(b.status.value is EqBackendStatus.Unavailable)
        assertContentEquals(utf16, config.readBytes())
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting" }
            Thread.sleep(10)
        }
    }
}
