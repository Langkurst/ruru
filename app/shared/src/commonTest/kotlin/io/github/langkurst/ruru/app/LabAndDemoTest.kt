package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.app.tools.LabController
import io.github.langkurst.ruru.app.tools.LabReport
import io.github.langkurst.ruru.app.tools.LabSource
import io.github.langkurst.ruru.app.tools.LabState
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.SupportTier
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.VendorRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Demo mode per maker, the Lab through the real controller, and the pairing question. */
class LabAndDemoTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        Logging.sink = { _, _, _, _ -> }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private val address = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")

    private fun hasDemo(family: DeviceFamily) = family == DeviceFamily.BOSE || family == DeviceFamily.APPLE_ADVERT ||
        VendorRegistry.forFamily(family)?.virtual() != null

    private fun connect(family: DeviceFamily): HeadsetController {
        val c = HeadsetController(DemoHeadsets.platform(family), scope, demo = true, advertPauseMs = 20)
        c.start()
        runBlocking { withTimeout(30_000) { c.state.first { it.phase == Phase.Connected } } }
        return c
    }

    @Test
    fun `demo mode connects to each maker's simulated headset and gives the tier the table says`() {
        for (family in DeviceFamily.entries.filter { it != DeviceFamily.GENERIC && hasDemo(it) }) {
            val c = connect(family)
            try {
                val state = runBlocking { withTimeout(30_000) { c.state.first { it.headset.info != null || family == DeviceFamily.APPLE_ADVERT } } }
                val expected = when (family) {
                    DeviceFamily.APPLE_ADVERT -> SupportTier.BATTERY_ONLY
                    else -> SupportTier.EXPERIMENTAL
                }
                // On Windows only the Bose 1st Gen is Tested; the same Bose demo on any other system is Experimental.
                val onWindows = SupportTier.of(state.headset, "Windows")
                assertEquals(if (family == DeviceFamily.BOSE) SupportTier.TESTED else expected, onWindows, "$family on Windows")
                assertEquals(if (family == DeviceFamily.BOSE) SupportTier.EXPERIMENTAL else expected, SupportTier.of(state.headset, "Linux"), "$family on Linux")
            } finally {
                c.stop()
            }
        }
    }

    @Test
    fun `the Lab through the controller reads something for every maker with a session and never shows an address`() {
        for (family in DeviceFamily.entries.filter { it != DeviceFamily.GENERIC && it != DeviceFamily.APPLE_ADVERT && hasDemo(it) }) {
            val c = connect(family)
            try {
                runBlocking { withTimeout(30_000) { c.state.first { it.headset.info != null } } }
                val lines = runBlocking { withTimeout(60_000) { c.scanLab() } }
                assertTrue(lines.isNotEmpty(), "$family: the Lab read nothing")
                for (line in lines) assertFalse(address.containsMatchIn(line), "$family: an address in the Lab: $line")
            } finally {
                c.stop()
            }
        }
    }

    @Test
    fun `AirPods have no session, so the Lab has nothing to scan`() {
        val c = connect(DeviceFamily.APPLE_ADVERT)
        try {
            val e = runCatching { runBlocking { c.scanLab() } }.exceptionOrNull()
            assertTrue(e is HeadphoneCommandException, "the Lab on AirPods: $e")
        } finally {
            c.stop()
        }
    }

    @Test
    fun `the hidden demo brand names a maker and anything else is Bose`() {
        assertEquals(DeviceFamily.SONY, DemoHeadsets.familyOf("sony"))
        assertEquals(DeviceFamily.SONY, DemoHeadsets.familyOf(" Sony "))
        assertEquals(DeviceFamily.SENNHEISER, DemoHeadsets.familyOf("sennheiser"))
        assertEquals(DeviceFamily.SOUNDCORE, DemoHeadsets.familyOf("soundcore"))
        assertEquals(DeviceFamily.JBL, DemoHeadsets.familyOf("jbl"))
        assertEquals(DeviceFamily.BRAGI, DemoHeadsets.familyOf("skullcandy"))
        assertEquals(DeviceFamily.APPLE_ADVERT, DemoHeadsets.familyOf("airpods"))
        assertEquals(DeviceFamily.BOSE, DemoHeadsets.familyOf("bose"))
        assertEquals(DeviceFamily.BOSE, DemoHeadsets.familyOf(null))
        assertEquals(DeviceFamily.BOSE, DemoHeadsets.familyOf(""))
        assertEquals(DeviceFamily.BOSE, DemoHeadsets.familyOf("generic"))
        assertEquals(DeviceFamily.BOSE, DemoHeadsets.familyOf("nonsense"))
    }

    // ---- the Lab's own state machine

    private class Gate : LabSource {
        val release = CompletableDeferred<List<String>>()
        var calls = 0
        override suspend fun scan(): List<String> { calls++; return release.await() }
    }

    @Test
    fun `a scan runs once at a time and ends with its lines, addresses masked`() = runBlocking {
        val gate = Gate()
        val lab = LabController(scope, gate)
        assertEquals(LabState.Idle, lab.state.value)
        lab.start()
        lab.start()
        assertEquals(LabState.Scanning, lab.state.value)
        delay(50)
        assertEquals(1, gate.calls, "a second press while scanning does not start a second scan")
        gate.release.complete(listOf("[0.5] Firmware: 3 bytes 312e32", "paired with AA:BB:CC:00:00:01 here"))
        val done = withTimeout(5_000) { lab.state.first { it is LabState.Done } } as LabState.Done
        assertEquals(listOf("[0.5] Firmware: 3 bytes 312e32", "paired with xx:xx:xx:xx:xx:xx here"), done.lines)
    }

    @Test
    fun `a refused or failed scan says so in words`() = runBlocking {
        val unavailable = LabController(scope, object : LabSource {
            override suspend fun scan(): List<String> = throw HeadphoneCommandException(HeadsetSession.LAB_UNAVAILABLE)
        })
        unavailable.start()
        assertEquals(LabState.Problem(Words("tools_lab_unavailable")), withTimeout(5_000) { unavailable.state.first { it is LabState.Problem } })
        val broken = LabController(scope, object : LabSource {
            override suspend fun scan(): List<String> = error("boom")
        })
        broken.start()
        assertEquals(LabState.Problem(Words("tools_lab_failed")), withTimeout(5_000) { broken.state.first { it is LabState.Problem } })
    }

    @Test
    fun `the report names the app, system and model and masks addresses`() {
        val text = LabReport.build("0.3.0", "Windows", "QC Ultra Headphones (1st Gen)", listOf("a: 1 bytes 00", "peer AA-BB-CC-00-00-02"))
        assertTrue(text.startsWith("Ruru 0.3.0 on Windows, Lab (read only)"))
        assertTrue("Model: QC Ultra Headphones (1st Gen)" in text)
        assertTrue("Reads: 2" in text)
        assertFalse(address.containsMatchIn(text))
        assertTrue("xx:xx:xx:xx:xx:xx" in text)
    }

    // ---- the pairing question

    @Test
    fun `a pairing question waits for the person and Pair says yes`() = runBlocking {
        val requests = PairingRequests()
        var answer: Boolean? = null
        val job = scope.launch { answer = requests.ask(123456, 6) }
        val request = withTimeout(5_000) { requests.pending.first { it != null } }!!
        assertEquals(123456, request.passcode)
        assertEquals(6, request.digits)
        assertNull(answer, "nothing is decided before the person answers")
        requests.answer(request, true)
        job.join()
        assertEquals(true, answer)
        assertNull(requests.pending.value)
    }

    @Test
    fun `Not now says no and a cancelled question leaves nothing on screen`() = runBlocking {
        val requests = PairingRequests()
        var answer: Boolean? = null
        val first = scope.launch { answer = requests.ask(null, 0) }
        val request = withTimeout(5_000) { requests.pending.first { it != null } }!!
        assertNull(request.passcode)
        requests.answer(request, false)
        first.join()
        assertEquals(false, answer)
        val second = scope.launch { requests.ask(111111, 6) }
        withTimeout(5_000) { requests.pending.first { it != null } }
        second.cancel()
        second.join()
        assertNull(requests.pending.value, "a session that gave up takes its question off the screen")
    }
}
