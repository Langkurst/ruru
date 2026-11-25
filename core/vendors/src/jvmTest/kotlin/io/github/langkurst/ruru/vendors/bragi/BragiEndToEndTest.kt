package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.SecureSessionStatus
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.core.SupportTier
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.VendorRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [BragiSession] as the controller drives it (start, run, pushes, poll) over the real connection,
 * PPP framing, CRCs, BOS-RPC headers, tags, streaming subscriptions, write guard and state mapping,
 * against a [VirtualSkullcandy] (Earshot's VirtualCrusherEndToEndTest, MethodEndToEndTest and
 * VirtualCrusherSecureSessionTest, MIT). Every byte has to agree for these to pass. The end-to-end
 * test at the bottom is the whole milestone: connect (the crypto handshake included), read, change
 * one control, and read it back on a new connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BragiEndToEndTest {

    private class Rig(val device: VirtualSkullcandy, val transport: VirtualSkullcandyTransport, val conn: BragiConnection, val session: BragiSession) {
        var state = HeadphoneState()
        fun control(id: String) = state.vendorControls.first { it.id == id }
        suspend fun run(command: HeadsetCommand) { state = session.run(command, state) }
        suspend fun control(id: String, value: Int) = run(HeadsetCommand.SetVendorControl(id, value))
    }

    private fun named(name: String?) = HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTING, deviceName = name))

    private suspend fun TestScope.rig(
        device: VirtualSkullcandy = VirtualSkullcandy(),
        name: String? = VirtualSkullcandy.NAME_CRUSHER,
        trust: BragiTrustStore = MemoryBragiTrustStore(),
        prompt: PairingPrompt = PairingPrompt { _, _ -> true },
        start: Boolean = true,
        replyWrapper: (ByteArray) -> ByteArray = { it },
    ): Rig {
        val transport = VirtualSkullcandyTransport(device, replyWrapper = replyWrapper)
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val session = BragiSession(conn, trust, prompt)
        val r = Rig(device, transport, conn, session)
        r.state = named(name)
        backgroundScope.launch { session.pushes.collect { p -> session.onPush(p, r.state)?.let { r.state = it } } }
        testScheduler.runCurrent()
        if (start) r.state = session.start(r.state)
        return r
    }

    // ---- Routing ---------------------------------------------------------------------------

    @Test fun `Skullcandy is registered with a session, a demo Crusher and the UUID route`() {
        assertTrue(BragiVendor.sessionReady)
        assertSame(BragiVendor, VendorRegistry.forFamily(DeviceFamily.BRAGI))
        val demo = assertNotNull(BragiVendor.virtual())
        assertEquals("Skullcandy CRUSHER 1080 ANC", demo.name)
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName(demo.name))
        for (name in listOf(VirtualSkullcandy.NAME_CRUSHER, VirtualSkullcandy.NAME_METHOD_360, VirtualSkullcandy.NAME_METHOD_540)) {
            assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName(name), name)
        }
    }

    @Test fun `routing and names recognise the Crusher and the Method earbuds and nothing near them`() {
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Skullcandy CRUSHER 1080 ANC"))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Crusher 1080 ANC"))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Skullcandy Method-360_ANC"))
        for (name in listOf("Skullcandy Crusher ANC 2", "Skullcandy Rail ANC", "Skullcandy Method 360", "Skullcandy Sesh ANC", "Fargo")) {
            assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName(name), name)
        }
        // A renamed Crusher is claimed by the person's "Headphone type" setting, not by the name.
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.resolve("bragi", null, "Alex's headphones"))
    }

    @Test fun `the route is the data service by UUID and never the third-party RACE service`() {
        val candidate = io.github.langkurst.ruru.link.HeadsetCandidate(key = "k", name = VirtualSkullcandy.NAME_CRUSHER, connected = true, uuids = listOf(BragiVendor.SERVICE_THIRD_PARTY))
        val route = BragiVendor.route(candidate, setOf(RouteKind.RFCOMM_SERVICE))
        assertEquals(io.github.langkurst.ruru.link.LinkRoute.RfcommService(BragiVendor.SERVICE_DATA), route)
        assertNull(BragiVendor.route(candidate, setOf(RouteKind.BOSE_RFCOMM)), "no UUID route on this platform: nothing is opened")
        assertNull(BragiVendor.route(candidate, emptySet()))
    }

    // ---- Crusher 1080 ----------------------------------------------------------------------

    @Test fun `connect reads the whole virtual Crusher into the state, and dev_info is the first call`() = runTest {
        val r = rig()
        val s = r.state
        // Nothing is sent before dev_info, and its body is "08 00" (the Skullcandy app's).
        val first = r.device.received.first()
        assertEquals(BragiServices.DEV_INFO, first.address)
        assertEquals("0800", first.payload.joinToString("") { "%02x".format(it) })

        assertEquals("Skullcandy Crusher 1080 ANC", s.info?.modelName)
        assertEquals("CHP300", s.info?.productName)
        assertEquals(68, s.batteryPercent)
        assertEquals(false, s.isCharging)
        // Quiet / Aware / Off (slots 0 and 1 line up with the Bose models), ANC active.
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF), s.modes.map { it.meaning })
        assertTrue(s.modes.all { it.isPreset && !it.isEditable && it.isConfigured })
        assertEquals(0, s.currentModeIndex)
        assertEquals(7, s.noise?.cancellingLevel) // gain 7 of 10
        assertEquals(true, s.noise?.ancEnabled)
        assertEquals(SpatialAudio.OFF, s.noise?.spatialAudio)
        assertEquals(Sidetone.MEDIUM, s.sidetone) // volume 3 of 5 = 0.6
        // The Crusher bass as generic controls.
        assertEquals(listOf("skullcandy_crusher", "skullcandy_crusher_intensity", "skullcandy_crusher_feel"), s.vendorControls.map { it.id })
        assertEquals(1, r.control("skullcandy_crusher").value)
        assertEquals(5, r.control("skullcandy_crusher_intensity").value)
        assertEquals(0..10, r.control("skullcandy_crusher_intensity").range)
        assertEquals(1, r.control("skullcandy_crusher_feel").value)
        assertEquals(listOf("High", "Medium", "Low"), r.control("skullcandy_crusher_feel").options)
        assertEquals(true, s.crusher?.active)
        assertEquals(SecureSessionStatus.NOT_REQUIRED, s.secureSession.status)
        assertFalse(r.session.authenticatedAccess)
        // Capabilities: Experimental (Ruru hasn't run on these headphones), headphones, immersive audio, no wind block, no ANC switch.
        assertFalse(s.capabilities.ancToggle)
        assertFalse(s.capabilities.windBlock)
        assertFalse(s.capabilities.verified)
        assertTrue(s.capabilities.spatialAudio)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertEquals(BragiStateMapping.SUPPORT_NOTE, s.capabilities.supportNote)
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(s.capabilities))
    }

    @Test fun `start subscribes once, and the battery poll only reads the battery`() = runTest {
        val r = rig()
        assertTrue(r.device.received.any { it.address == BragiServices.MODE_READ && it.payload.contentEquals(Hearing.modeRead(subscribe = true)) })
        r.device.received.clear()
        r.state = r.session.pollBattery(r.state)
        assertEquals(listOf(BragiServices.BATT_INFO), r.device.received.map { it.address })
    }

    @Test fun `switching to Aware sends mode_configure and the slider then shows Aware's own gain`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetMode(1))
        assertEquals(HearingMode.AT, r.device.hearingMode)
        assertEquals(1, r.state.currentModeIndex)
        val sent = r.device.received.last { it.address == BragiServices.MODE_CONFIGURE }
        assertEquals("08031001", sent.payload.joinToString("") { "%02x".format(it) }) // mode = AT (3), activate
        assertEquals(5, r.state.noise?.cancellingLevel) // aware gain 5 of 10
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(3)) }
    }

    @Test fun `moving the slider changes the active mode's gain only, and is refused while Off`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(cancellingLevel = 10)))
        assertEquals(10, r.device.ancGain)
        assertEquals(5, r.device.atGain) // untouched
        assertEquals(HearingMode.ANC, r.device.hearingMode) // a gain write does not switch modes
        assertEquals(10, r.state.noise?.cancellingLevel)
        r.run(HeadsetCommand.SetMode(2))
        assertEquals(HearingMode.OFF, r.device.hearingMode)
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(cancellingLevel = 3))) }
    }

    @Test fun `the ANC switch the automations send goes to Off and back to the last mode`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetMode(1))
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(ancEnabled = false)))
        assertEquals(HearingMode.OFF, r.device.hearingMode)
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(ancEnabled = true)))
        assertEquals(HearingMode.AT, r.device.hearingMode)
    }

    @Test fun `immersive audio maps Still and Motion by label`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(spatialAudio = SpatialAudio.ROOM)))
        assertEquals(SpatialMode.STILL, r.device.spatial)
        assertEquals(SpatialAudio.ROOM, r.state.noise?.spatialAudio)
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(spatialAudio = SpatialAudio.HEAD)))
        assertEquals(SpatialMode.MOTION, r.device.spatial)
        assertEquals(SpatialAudio.HEAD, r.state.noise?.spatialAudio)
    }

    @Test fun `sidetone folds the volume into thirds and off disables it`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetSidetone(Sidetone.HIGH))
        assertEquals(true, r.device.sidetoneEnabled)
        assertEquals(5, r.device.sidetoneVolume)
        r.run(HeadsetCommand.SetSidetone(Sidetone.LOW))
        assertEquals(2, r.device.sidetoneVolume) // round(5 / 3)
        r.run(HeadsetCommand.SetSidetone(Sidetone.OFF))
        assertEquals(false, r.device.sidetoneEnabled)
        assertEquals(Sidetone.OFF, r.state.sidetone)
    }

    @Test fun `every sidetone level reads back as itself on a new connection, whatever the volume range`() = runTest {
        for (max in listOf(5, 7, 10, 31)) {
            val device = VirtualSkullcandy(sidetoneVolumeMax = max)
            val r = rig(device)
            for (level in listOf(Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH)) {
                r.run(HeadsetCommand.SetSidetone(level))
                val back = rig(device) // what the headphones now report, read through the same mapping
                assertEquals(level, back.state.sidetone, "max $max, wrote $level, volume ${device.sidetoneVolume}")
                back.conn.close()
            }
            r.conn.close()
        }
    }

    @Test fun `the Crusher controls write only what changed and report the applied state`() = runTest {
        val r = rig()
        r.control("skullcandy_crusher_intensity", 8)
        assertEquals(8, r.device.crusherIntensity)
        assertEquals(true, r.device.crusherActive)
        assertEquals(1, r.device.crusherMode)
        assertEquals(8, r.control("skullcandy_crusher_intensity").value)
        r.control("skullcandy_crusher_feel", 2)
        assertEquals(2, r.device.crusherMode)
        r.control("skullcandy_crusher", 0)
        assertEquals(false, r.device.crusherActive)
        assertEquals(0, r.control("skullcandy_crusher").value)
        assertFailsWith<HeadphoneCommandException> { r.control("skullcandy_crusher_intensity", 99) }
        assertFailsWith<HeadphoneCommandException> { r.control("skullcandy_crusher_feel", 7) }
        assertFailsWith<HeadphoneCommandException> { r.control("skullcandy_no_such_setting", 1) }
    }

    @Test fun `a change made on the headphones arrives as a push and updates the state`() = runTest {
        val r = rig()
        r.transport.push(r.device.setHearingModeOnDevice(HearingMode.AT)) // the person pressed the button
        testScheduler.runCurrent()
        assertEquals(1, r.state.currentModeIndex)
        assertEquals(5, r.state.noise?.cancellingLevel)
        // A garbled or unknown push changes nothing and does not throw.
        assertNull(r.session.onPush("not a packet", r.state))
        assertNull(r.session.onPush(BosRpcPacket(BosMessageType.RESPONSE, BragiServices.MODE_READ.service, BragiServices.MODE_READ.method, 1, byteArrayOf(0x0A, 0x7F)), r.state))
    }

    @Test fun `a lost link is reported, not swallowed`() = runTest {
        val r = rig()
        r.conn.close()
        assertFailsWith<HeadphoneCommandException> { r.session.pollBattery(r.state) }
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(1)) }
        assertFalse(r.session.isOpen)
    }

    @Test fun `a firmware-update style service is refused before it is written, and so is an unknown method`() = runTest {
        val r = rig()
        r.device.received.clear()
        val e = assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.Address(service = 99, method = 1)) }
        assertTrue("not a supported" in e.message.orEmpty())
        assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.Address(BragiServices.DEVICE, 99)) }
        assertTrue(r.device.received.isEmpty())
    }

    @Test fun `a method the device doesn't implement is reported as a rejection`() = runTest {
        val r = rig()
        val e = assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.TIME_GET) } // allowed to send, the device does not implement it
        assertTrue("rejected" in e.message.orEmpty(), e.message)
    }

    @Test fun `replies wrapped in a vendor header are still found`() = runTest {
        // 05 5D <len> 00 00 in front of every frame, as a RACE notification would be.
        val vendor: (ByteArray) -> ByteArray = { frame -> byteArrayOf(0x05, 0x5D, (frame.size + 2).toByte(), 0x00, 0x0D, 0x01) + frame }
        val r = rig(replyWrapper = vendor)
        assertEquals(68, r.state.batteryPercent)
        assertTrue(r.conn.hasFramed)
    }

    @Test fun `a headset that opens the socket and stays silent is reported as busy, not as connected`() = runTest {
        val silent = VirtualSkullcandyTransport(VirtualSkullcandy(), acceptsRequest = { false })
        val conn = BragiConnection(silent, backgroundScope, clock = { testScheduler.currentTime })
        val session = BragiSession(conn, MemoryBragiTrustStore(), PairingPrompt.DECLINE)
        val e = assertFailsWith<HeadphoneCommandException> { session.start(named(VirtualSkullcandy.NAME_CRUSHER)) }
        assertTrue("No reply" in e.message.orEmpty(), e.message)
        conn.close()
    }

    @Test fun `commands Ruru doesn't drive throw a sentence and send nothing`() = runTest {
        val r = rig()
        r.device.received.clear()
        for (c in BragiSafetyTest.unsupportedCommands()) {
            val e = assertFailsWith<HeadphoneCommandException>("$c") { r.session.run(c, r.state) }
            assertEquals(BragiSession.UNSUPPORTED, e.message)
        }
        assertTrue(r.device.received.isEmpty(), "${r.device.received.map { it.address }}")
    }

    @Test fun `the Lab scans only read-only calls, shows no serial number, and works through the connection's guard`() = runTest {
        val r = rig()
        r.device.received.clear()
        val profile = assertNotNull(r.session.labProfile())
        assertEquals("Bragi BOS-RPC", profile.title)
        assertEquals(BragiReads.ALL.size, profile.reads.size)
        val lines = r.session.scanLab()
        assertEquals(profile.reads.size, lines.size)
        assertTrue(r.device.received.all { BragiServices.isRead(it.address) }, "${r.device.received.map { it.address }}")
        val devInfo = lines.first { "device.dev_info" in it }
        assertFalse(r.device.serial in devInfo)
        assertTrue("bytes" in devInfo && devInfo.trimEnd().endsWith("bytes"), devInfo)
    }

    // ---- Method earbuds --------------------------------------------------------------------

    private val crusherOnly = setOf(BragiServices.SPATIAL_AUDIO, BragiServices.CRUSHER)

    @Test fun `a Method 360 reads as earbuds with the shared features and none of the Crusher's`() = runTest {
        val r = rig(VirtualSkullcandy.method360(), name = VirtualSkullcandy.NAME_METHOD_360)
        val s = r.state
        assertEquals("Skullcandy Method 360 ANC", s.info?.modelName)
        assertEquals("T99_PLUS", s.info?.productName)
        assertEquals(74, s.batteryPercent)
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertEquals(0, s.currentModeIndex)
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        assertNull(s.crusher)
        assertTrue(s.vendorControls.isEmpty(), "no Crusher bass controls")
        assertFalse(s.capabilities.spatialAudio)
        assertFalse(s.capabilities.windBlock)
        assertFalse(s.capabilities.ancToggle)
        assertFalse(s.capabilities.verified)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertTrue(s.capabilities.noiseCancelling, "the level slider is the active mode's gain, as on the Crusher")
        assertEquals(BragiStateMapping.METHOD_NOTE, s.capabilities.supportNote)
        // Nothing for a feature it does not have ever reached the earbuds.
        assertTrue(r.device.received.none { it.address.service in crusherOnly }, "${r.device.received.map { it.address }}")
    }

    @Test fun `a Method 540 is told apart by its SKU even when the name says nothing`() = runTest {
        val r = rig(VirtualSkullcandy.method540(), name = "Alex's earbuds")
        assertEquals("Skullcandy Method 540 ANC", r.state.info?.modelName)
        assertEquals(81, r.state.batteryPercent)
        assertNull(r.state.crusher)
        assertEquals(FormFactor.EARBUDS, r.state.capabilities.formFactor)
    }

    @Test fun `the Bluetooth name is enough when dev_info gives an unknown SKU`() = runTest {
        val unit = VirtualSkullcandy(model = "UNKNOWN", modelNumber = "ZZZZZ", hasCrusherBass = false, hasSpatialAudio = false)
        val r = rig(unit, name = VirtualSkullcandy.NAME_METHOD_540)
        assertEquals("Skullcandy Method 540 ANC", r.state.info?.modelName)
        assertTrue(r.device.received.none { it.address.service in crusherOnly })
    }

    @Test fun `Quiet, Aware, Off and the gain slider work on the earbuds, and the Crusher-only features are refused unsent`() = runTest {
        val r = rig(VirtualSkullcandy.method360(), name = VirtualSkullcandy.NAME_METHOD_360)
        r.run(HeadsetCommand.SetMode(1))
        assertEquals(HearingMode.AT, r.device.hearingMode)
        r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(cancellingLevel = 9)))
        assertEquals(9, r.device.atGain)
        assertEquals(9, r.state.noise?.cancellingLevel)
        r.run(HeadsetCommand.SetMode(2))
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(cancellingLevel = 3))) }
        r.run(HeadsetCommand.SetSidetone(Sidetone.HIGH))
        assertEquals(5, r.device.sidetoneVolume)
        val e = assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetNoise(r.state.noise!!.copy(spatialAudio = SpatialAudio.ROOM))) }
        assertTrue("immersive audio" in e.message.orEmpty(), e.message)
        assertFailsWith<HeadphoneCommandException> { r.control("skullcandy_crusher", 1) }
        assertTrue(r.device.received.none { it.address.service in crusherOnly })
    }

    @Test fun `a firmware that lacks the services answers unknown method, as the virtual earbud does`() = runTest {
        val r = rig(VirtualSkullcandy.method360(), name = VirtualSkullcandy.NAME_METHOD_360)
        assertTrue("rejected" in assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.CRUSHER_STATE_GET) }.message.orEmpty())
        assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.SPATIAL_INFO_GET) }
    }

    // ---- The secure session ----------------------------------------------------------------

    private fun secure() = VirtualSkullcandy(authenticatedAccess = true)

    @Test fun `protected controls are refused before a session and the dev_info flag says so`() = runTest {
        val r = rig(secure(), start = false)
        val e = assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.MODE_READ, Hearing.modeRead(false), streaming = true) }
        assertTrue("rejected" in e.message.orEmpty(), e.message)
        // dev_info is open and carries the gate.
        assertTrue(DeviceInfo.parseDevInfo(r.conn.call(BragiServices.DEV_INFO, DeviceInfo.devInfo()).payload).authenticatedAccess)
    }

    @Test fun `first connect pairs with a matching passcode and then controls work encrypted`() = runTest {
        val device = secure()
        val trust = MemoryBragiTrustStore()
        val shown = ArrayList<Int?>()
        val r = rig(device, trust = trust, prompt = { passcode, _ -> shown += passcode; true })
        assertEquals(SecureSessionStatus.SECURE, r.state.secureSession.status)
        assertTrue(r.session.authenticatedAccess)
        // The passcode the screen showed is the one the headphones derived.
        assertEquals(1, shown.size)
        assertEquals(device.lastPasscode, shown.single())
        assertTrue(shown.single()!! in 0..999_999)
        // Protected reads worked: they could only have gone through the encrypted channel.
        assertEquals(listOf("Quiet", "Aware", "Off"), r.state.modes.map { it.name })
        assertEquals(5, r.control("skullcandy_crusher_intensity").value)
        assertNotNull(device.trustedClient)
        assertNotNull(trust.trustedServer(BragiHandshake.identityKeyId(device.identityPoint)))
        // Commands after pairing are sealed and the device applies them.
        r.run(HeadsetCommand.SetMode(1))
        assertEquals(HearingMode.AT, device.hearingMode)
    }

    @Test fun `a second connection reuses the trust without asking the person again`() = runTest {
        val device = secure()
        val trust = MemoryBragiTrustStore()
        rig(device, trust = trust)
        val shown = ArrayList<Int?>()
        val r = rig(device, trust = trust, prompt = { p, _ -> shown += p; true })
        assertTrue(shown.isEmpty(), "the person must not be asked again")
        assertEquals(SecureSessionStatus.SECURE, r.state.secureSession.status)
        assertEquals(3, r.state.modes.size)
    }

    @Test fun `a build with no confirmation screen declines the pairing, trusts nothing and reads nothing protected`() = runTest {
        val device = secure()
        val trust = MemoryBragiTrustStore()
        val r = rig(device, trust = trust, prompt = PairingPrompt.DECLINE)
        assertEquals(SecureSessionStatus.DECLINED, r.state.secureSession.status)
        assertNull(device.trustedClient)
        assertTrue(r.state.modes.isEmpty(), "protected reads stay refused without a session")
        // The open service still answered.
        assertEquals(68, r.state.batteryPercent)
    }

    @Test fun `forgetting every pairing makes the next connect pair again`() = runTest {
        val device = secure()
        val trust = MemoryBragiTrustStore()
        rig(device, trust = trust)
        trust.forgetAll()
        val shown = ArrayList<Int?>()
        val r = rig(device, trust = trust, prompt = { p, _ -> shown += p; true })
        assertEquals(1, shown.size)
        assertEquals(SecureSessionStatus.SECURE, r.state.secureSession.status)
    }

    @Test fun `a headphone that never confirms times out instead of hanging`() = runTest {
        val device = VirtualSkullcandy(authenticatedAccess = true, autoConfirmOnDevice = false)
        val trust = MemoryBragiTrustStore()
        val transport = VirtualSkullcandyTransport(device)
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val outcome = BragiSecureSession(trust, { _, _ -> true }, userTimeoutMs = 2_000).establish(conn) { conn.installCipher(it) }
        assertTrue(outcome is BragiSecureSession.Outcome.Failed, outcome.toString())
        assertNull(trust.trustedServer(ByteArray(8)))
    }

    private fun secureRoundTrip(suite: Int, key: Int, nonce: Int, tag: Int) = runTest {
        val device = VirtualSkullcandy(authenticatedAccess = true, channelSuite = suite, channelKeyLength = key, channelNonceLength = nonce, channelTagLength = tag)
        val r = rig(device)
        assertEquals(SecureSessionStatus.SECURE, r.state.secureSession.status, "suite $suite key $key nonce $nonce tag $tag")
        assertEquals(listOf("Quiet", "Aware", "Off"), r.state.modes.map { it.name })
        r.run(HeadsetCommand.SetMode(1))
        assertEquals(HearingMode.AT, device.hearingMode)
        assertEquals(1, r.state.currentModeIndex)
    }

    @Test fun `the session works with AES-CCM`() = secureRoundTrip(suite = 1, key = 16, nonce = 12, tag = 16)
    @Test fun `the session works with AES-CCM and the short nonce and tag`() = secureRoundTrip(suite = 1, key = 16, nonce = 8, tag = 8)
    @Test fun `the session works with AES-GCM and a 256-bit key`() = secureRoundTrip(suite = 2, key = 32, nonce = 12, tag = 16)
    @Test fun `the session works with ChaCha20-Poly1305`() = secureRoundTrip(suite = 3, key = 32, nonce = 12, tag = 16)

    @Test fun `a key store failure becomes a failed session, not a failed connect`() = runTest {
        val broken = object : BragiTrustStore {
            override fun identity(): BragiHandshake.EcKeyPair = throw java.security.ProviderException("key store unavailable")
            override fun trustedServer(keyId: ByteArray): ByteArray? = null
            override fun trust(serverPoint: ByteArray) {}
            override fun forgetAll() {}
        }
        val r = rig(secure(), trust = broken)
        assertEquals(SecureSessionStatus.FAILED, r.state.secureSession.status)
        assertTrue("key store" in r.state.secureSession.message.orEmpty(), r.state.secureSession.message)
        assertEquals(68, r.state.batteryPercent) // the rest of the connect still ran
    }

    @Test fun `headphones that need no session skip the handshake entirely`() = runTest {
        val r = rig(VirtualSkullcandy(authenticatedAccess = false), prompt = { _, _ -> error("must not be asked") })
        assertEquals(SecureSessionStatus.NOT_REQUIRED, r.state.secureSession.status)
        assertEquals(3, r.state.modes.size)
        assertTrue(r.device.received.none { it.address.service == BragiServices.AUTHENTICATED_ACCESS })
    }

    // ---- The milestone ---------------------------------------------------------------------

    @Test fun `END TO END - connect with the crypto handshake, read, change one control, read it back on a new connection`() = runTest {
        val device = secure()
        val trust = MemoryBragiTrustStore()
        val shown = ArrayList<Int?>()

        // 1. Connect: dev_info, then hello, the one-time pairing (passcode shown and accepted) and the signed key exchange.
        val first = rig(device, trust = trust, prompt = { p, _ -> shown += p; true })
        assertEquals(SecureSessionStatus.SECURE, first.state.secureSession.status)
        assertEquals(1, shown.size)
        // 2. Read: the protected state arrived through the encrypted channel.
        assertEquals(5, first.control("skullcandy_crusher_intensity").value)
        assertEquals(0, first.state.currentModeIndex)
        // 3. Change one control.
        first.control("skullcandy_crusher_intensity", 9)
        assertEquals(9, device.crusherIntensity)
        assertEquals(9, first.control("skullcandy_crusher_intensity").value)
        first.conn.close()

        // 4. A new connection (new session, new socket, new session keys, the trust kept): no new pairing, and the change is there.
        val second = rig(device, trust = trust, prompt = { _, _ -> error("must not pair again") })
        assertEquals(SecureSessionStatus.SECURE, second.state.secureSession.status)
        assertEquals(9, second.control("skullcandy_crusher_intensity").value)
        // Put it back.
        second.control("skullcandy_crusher_intensity", 5)
        assertEquals(5, device.crusherIntensity)
    }
}
