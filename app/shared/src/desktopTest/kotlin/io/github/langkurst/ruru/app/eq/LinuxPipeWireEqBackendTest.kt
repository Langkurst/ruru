package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.dsp.FilterChainWriter
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A system with PipeWire on it, as far as the backend can tell: a script of what each command answers, and a log of what was run. */
private class FakeSystem : PipeWireSystem {
    @Volatile var versionText: String? = "pipewire\nCompiled with libpipewire 1.0.5\nLinked with libpipewire 1.0.5\n"
    @Volatile var wpctlStatusExit: Int? = 0
    @Volatile var hasPwDump = true
    @Volatile var headphonesConnected = true
    @Volatile var defaultName = "alsa_output.pci-0000_00_1f.3.analog-stereo"
    @Volatile var spawnFails = false
    @Volatile var childDiesAtOnce = false

    val log = CopyOnWriteArrayList<List<String>>()
    val children = CopyOnWriteArrayList<FakeChild>()
    val stoppedLeftovers = CopyOnWriteArrayList<Long>()

    inner class FakeChild(override val pid: Long, val command: List<String>) : PipeWireChild {
        @Volatile override var alive: Boolean = !childDiesAtOnce
        override fun stop() { alive = false }
    }

    val running: FakeChild? get() = children.lastOrNull { it.alive }
    val setDefaults: List<List<String>> get() = log.filter { it.take(2) == listOf("wpctl", "set-default") }

    private fun ruruSinkPresent() = children.any { it.alive }

    /** pw-dump: the sample desktop, without the headset when it is not connected and with Ruru's sink while a child runs. */
    private fun dump(): String {
        val all = PipeWireTextTest.DUMP
        var text = all
        if (!headphonesConnected) text = text.replace("bluez_output.AA_BB_CC_DD_EE_01.1", "gone_output").replace("Sample Headphones", "Gone")
        if (!ruruSinkPresent()) text = text.replace("\"node.name\": \"ruru_eq\"", "\"node.name\": \"not_a_sink\"")
        return text
    }

    private fun idOf(name: String): Int? = PipeWireText.parseSinks(dump()).firstOrNull { it.nodeName == name }?.id

    override fun run(vararg command: String): CommandOutput? {
        log += command.toList()
        return when (command.toList()) {
            listOf("pipewire", "--version") -> versionText?.let { CommandOutput(0, it) }
            listOf("wpctl", "status") -> wpctlStatusExit?.let { CommandOutput(it, "PipeWire 'pipewire-0' [1.0.5]") }
            listOf("pw-dump", "--help") -> if (hasPwDump) CommandOutput(0, "pw-dump [options]") else null
            listOf("pw-dump") -> if (hasPwDump) CommandOutput(0, dump()) else null
            listOf("wpctl", "inspect", "@DEFAULT_AUDIO_SINK@") ->
                CommandOutput(0, PipeWireTextTest.INSPECT.replace("alsa_output.pci-0000_00_1f.3.analog-stereo", defaultName))
            else -> if (command.size == 3 && command[0] == "wpctl" && command[1] == "set-default") {
                val name = PipeWireText.parseSinks(dump()).firstOrNull { it.id == command[2].toIntOrNull() }?.nodeName
                if (name == null) CommandOutput(1, "not found") else { defaultName = name; CommandOutput(0, "") }
            } else null
        }
    }

    override fun spawn(command: List<String>): PipeWireChild? {
        log += command
        if (spawnFails) return null
        return FakeChild(1000L + children.size, command).also { children += it }
    }

    override fun stopLeftover(pid: Long) { stoppedLeftovers += pid }

    fun ruruId() = idOf("ruru_eq")
    fun headsetId() = idOf("bluez_output.AA_BB_CC_DD_EE_01.1")
    fun speakersId() = idOf("alsa_output.pci-0000_00_1f.3.analog-stereo")
}

class LinuxPipeWireEqBackendTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var system: FakeSystem
    private val settings = MemorySettingsStore()

    private val tuned = ProEqState(enabled = true).withGraphicGain(2, 4.0).withGraphicGain(6, -3.0)
    private val conf get() = File(dir, "ruru/eq-filter-chain.conf")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruru-pw-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        system = FakeSystem()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun backend(debounceMs: Long = 80) = LinuxPipeWireEqBackend(
        settings, scope, system, conf, io = Dispatchers.Default, debounceMs = debounceMs, sinkWaitMs = 1_000, installShutdownHook = false,
    ).also { it.setHeadphone("Sample Headphones") }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "Gave up waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun statusKey(b: LinuxPipeWireEqBackend): String? = when (val s = b.status.value) {
        is EqBackendStatus.Unavailable -> s.reason.key
        is EqBackendStatus.NeedsSetup -> s.action.message.key
        else -> null
    }

    // ---------------------------------------------------------------- is it possible here

    @Test
    fun `new enough pipewire with its tools is ready`() = runBlocking {
        val b = backend()
        b.detect()
        assertEquals(EqBackendStatus.Ready, b.status.value)
        assertTrue(b.status.value.switchable)
    }

    @Test
    fun `old pipewire is unavailable and says which version is needed`() = runBlocking {
        system.versionText = "pipewire\nCompiled with libpipewire 0.3.63\nLinked with libpipewire 0.3.63\n"
        val b = backend()
        b.detect()
        val s = b.status.value as EqBackendStatus.Unavailable
        assertEquals(Words("eq_pw_old", "0.3.63", "0.3.64"), s.reason)
        assertFalse(s.switchable)
    }

    @Test
    fun `a system without pipewire (pulseaudio only) is unavailable with a plain reason`() = runBlocking {
        system.versionText = null
        val b = backend()
        b.detect()
        assertEquals(Words("eq_pw_missing"), (b.status.value as EqBackendStatus.Unavailable).reason)
    }

    @Test
    fun `missing wpctl or pw-dump, and pipewire not running, are told apart`() = runBlocking {
        val b = backend()
        system.wpctlStatusExit = null
        b.detect()
        assertEquals("eq_pw_no_tools", statusKey(b))
        system.wpctlStatusExit = 1
        b.detect()
        assertEquals("eq_pw_not_running", statusKey(b))
        system.wpctlStatusExit = 0
        system.hasPwDump = false
        b.detect()
        assertEquals("eq_pw_no_tools", statusKey(b))
    }

    @Test
    fun `nothing is started or changed while only looking`() = runBlocking {
        backend().detect()
        assertTrue(system.children.isEmpty())
        assertTrue(system.setDefaults.isEmpty())
        assertFalse(conf.exists())
    }

    // ---------------------------------------------------------------- switching on

    @Test
    fun `switching on writes the filter for the headphones' sink and runs pipewire -c on it`() = runBlocking {
        val b = backend()
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        assertEquals(listOf("pipewire", "-c", conf.path), system.running!!.command)
        val text = conf.readText()
        assertEquals(FilterChainWriter.write(tuned, "bluez_output.AA_BB_CC_DD_EE_01.1"), text)
        assertTrue(text.contains("target.object = \"bluez_output.AA_BB_CC_DD_EE_01.1\""))
        // Running, but the sound still goes straight to the headphones until the person says so.
        val action = (b.status.value as EqBackendStatus.NeedsSetup).action
        assertEquals(EqSetupKind.ALLOW, action.kind)
        assertTrue(action.running)
        assertTrue(system.setDefaults.isEmpty())
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", system.defaultName)
    }

    @Test
    fun `without connected headphones nothing runs and the person is asked to connect them`() = runBlocking {
        val b = LinuxPipeWireEqBackend(settings, scope, system, conf, io = Dispatchers.Default, debounceMs = 50, installShutdownHook = false)
        b.apply(tuned)
        waitFor("the message") { statusKey(b) == "eq_pw_no_headphones" }
        assertTrue(system.children.isEmpty())
        assertEquals(EqSetupKind.CHECK_AGAIN, (b.status.value as EqBackendStatus.NeedsSetup).action.kind)
        // The headphones appear: it starts by itself.
        b.setHeadphone("Sample Headphones")
        waitFor("the child") { system.running != null }
    }

    @Test
    fun `headphones whose output pipewire does not list are reported by name`() = runBlocking {
        system.headphonesConnected = false
        val b = backend()
        b.apply(tuned)
        waitFor("the message") { statusKey(b) == "eq_pw_no_sink" }
        assertEquals(Words("eq_pw_no_sink", "Sample Headphones"), (b.status.value as EqBackendStatus.NeedsSetup).action.message)
        assertTrue(system.children.isEmpty())
    }

    @Test
    fun `a child that dies at once, or cannot start, becomes a message and not a crash`() = runBlocking {
        system.childDiesAtOnce = true
        val b = backend()
        b.apply(tuned)
        waitFor("the message") { statusKey(b) == "eq_pw_failed" }
        system.childDiesAtOnce = false
        system.spawnFails = true
        b.apply(tuned.withGraphicGain(1, 1.0))
        waitFor("a second failure") { system.log.count { it.firstOrNull() == "pipewire" && it.getOrNull(1) == "-c" } >= 2 }
        assertEquals("eq_pw_failed", statusKey(b))
    }

    @Test
    fun `an unwritable folder becomes a message`() = runBlocking {
        val blocked = File(dir, "blocked").apply { writeText("a file where the folder should be") }
        val b = LinuxPipeWireEqBackend(settings, scope, system, File(blocked, "eq.conf"), io = Dispatchers.Default, debounceMs = 50, installShutdownHook = false)
        b.setHeadphone("Sample Headphones")
        b.apply(tuned)
        waitFor("the message") { statusKey(b) == "eq_pw_cant_write" }
        assertTrue(system.children.isEmpty())
    }

    // ---------------------------------------------------------------- the 300 ms restart

    @Test
    fun `a run of slider changes restarts the child once, after the last one, with the last sound`() = runBlocking {
        val b = backend(debounceMs = 150)
        b.apply(tuned)
        waitFor("the first child") { system.children.size == 1 && b.status.value is EqBackendStatus.NeedsSetup }
        var last = tuned
        for (i in 1..6) {
            last = tuned.withGraphicGain(4, i.toDouble() / 2)
            b.apply(last)
            Thread.sleep(40) // quicker than the wait, so none of these may restart it
        }
        assertEquals(1, system.children.size, "no restart while the sliders still move")
        waitFor("the restart") { system.children.size == 2 && system.running === system.children[1] }
        Thread.sleep(300)
        assertEquals(2, system.children.size, "exactly one restart")
        assertFalse(system.children[0].alive, "the old child is stopped before the new one runs")
        assertEquals(FilterChainWriter.write(last, "bluez_output.AA_BB_CC_DD_EE_01.1"), conf.readText())
    }

    @Test
    fun `the first switch-on does not wait`() = runBlocking {
        val b = backend(debounceMs = 5_000)
        b.apply(tuned)
        waitFor("the child, well before the debounce", timeoutMs = 2_000) { system.running != null }
    }

    @Test
    fun `an unchanged sound does not restart the child`() = runBlocking {
        val b = backend(debounceMs = 50)
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        b.apply(tuned)
        Thread.sleep(400)
        assertEquals(1, system.children.size)
    }

    // ---------------------------------------------------------------- the default output

    @Test
    fun `use as the output makes Ruru EQ the default and switching off puts the old one back`() = runBlocking {
        val b = backend()
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        b.perform((b.status.value as EqBackendStatus.NeedsSetup).action)
        waitFor("the output change") { b.status.value == EqBackendStatus.On }
        assertEquals("ruru_eq", system.defaultName)
        assertEquals(listOf(listOf("wpctl", "set-default", system.ruruId().toString())), system.setDefaults)
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", settings.getString(LinuxPipeWireEqBackend.PREVIOUS_KEY))

        val child = system.running!!
        b.apply(tuned.copy(enabled = false))
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", system.defaultName)
        assertEquals(listOf("wpctl", "set-default", system.speakersId().toString()), system.setDefaults.last())
        assertFalse(child.alive)
        assertEquals(EqBackendStatus.Ready, b.status.value)
    }

    @Test
    fun `off leaves the output alone when the person has already chosen another one`() = runBlocking {
        val b = backend()
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        b.perform((b.status.value as EqBackendStatus.NeedsSetup).action)
        waitFor("on") { b.status.value == EqBackendStatus.On }
        system.defaultName = "bluez_output.AA_BB_CC_DD_EE_01.1" // chosen in the sound settings
        val before = system.setDefaults.size
        b.off()
        assertEquals(before, system.setDefaults.size, "Ruru must not override a later choice")
        assertEquals("bluez_output.AA_BB_CC_DD_EE_01.1", system.defaultName)
        assertNotNull(system.children.firstOrNull())
        assertTrue(system.children.none { it.alive })
    }

    @Test
    fun `the output stays Ruru EQ across a restart caused by a slider`() = runBlocking {
        val b = backend(debounceMs = 60)
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        b.perform((b.status.value as EqBackendStatus.NeedsSetup).action)
        waitFor("on") { b.status.value == EqBackendStatus.On }
        b.apply(tuned.withGraphicGain(5, 2.0))
        waitFor("the restart") { system.children.size == 2 && system.running === system.children[1] && b.status.value == EqBackendStatus.On }
        assertEquals("ruru_eq", system.defaultName)
    }

    @Test
    fun `quitting stops the child and gives the output back`() = runBlocking {
        val b = backend()
        b.apply(tuned)
        waitFor("the child") { system.running != null && b.status.value is EqBackendStatus.NeedsSetup }
        b.perform((b.status.value as EqBackendStatus.NeedsSetup).action)
        waitFor("on") { b.status.value == EqBackendStatus.On }
        b.shutdown()
        assertTrue(system.children.none { it.alive })
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", system.defaultName)
    }

    @Test
    fun `after a crash the next start stops the leftover child and gives the output back`() = runBlocking {
        // What the crashed Ruru had stored: its child's process number and the output it replaced.
        settings.putString(LinuxPipeWireEqBackend.PID_KEY, "4242")
        settings.putString(LinuxPipeWireEqBackend.PREVIOUS_KEY, "alsa_output.pci-0000_00_1f.3.analog-stereo")
        system.defaultName = "ruru_eq"
        // The leftover child's sink is still listed: a fake child that nobody holds.
        system.children += system.FakeChild(4242, listOf("pipewire", "-c", conf.path))
        val b = backend()
        b.detect()
        assertEquals(listOf(4242L), system.stoppedLeftovers)
        assertEquals("alsa_output.pci-0000_00_1f.3.analog-stereo", system.defaultName)
        assertEquals("", settings.getString(LinuxPipeWireEqBackend.PREVIOUS_KEY))
    }

    @Test
    fun `a stored output that no longer exists is forgotten and nothing is changed`() = runBlocking {
        settings.putString(LinuxPipeWireEqBackend.PREVIOUS_KEY, "unplugged_usb_output")
        system.defaultName = "bluez_output.AA_BB_CC_DD_EE_01.1"
        backend().detect()
        assertTrue(system.setDefaults.isEmpty())
        assertEquals("", settings.getString(LinuxPipeWireEqBackend.PREVIOUS_KEY))
    }

    @Test
    fun `the same headphones announced again do not restart the filter`() = runBlocking {
        val b = backend(debounceMs = 50)
        b.setHeadphone("Sample Headphones")
        b.apply(tuned)
        waitFor("the child") { system.running != null }
        b.setHeadphone("Sample Headphones") // same name: no restart
        Thread.sleep(300)
        assertEquals(1, system.children.size)
    }
}
