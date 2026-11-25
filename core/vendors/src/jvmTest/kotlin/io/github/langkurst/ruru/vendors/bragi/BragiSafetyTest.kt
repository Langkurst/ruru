package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lists every kind of call a Skullcandy session can put on the wire, on every model Ruru has a
 * demo of (the Crusher in the clear and with the secure session, and both Method earbuds), and
 * checks that none of them is firmware update, equaliser or clock write, or a service nobody
 * documented. A "kind" is `service.method` (the bytes after it are values, which the guard
 * shape-checks). The kinds are frozen per model below, so a new one is a reviewed change.
 *
 * The permitted list is written out again here, as plain numbers, on purpose: it does not read the
 * guard's own, so loosening the guard makes this test fail. The last tests prove that: a frame
 * written straight past the connection is seen by the checker, and the guard refuses all of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BragiSafetyTest {

    companion object {
        /** One of every [HeadsetCommand] that Skullcandy does not run: the `when` has no else, so a new command fails to compile here. */
        fun unsupportedCommands(): List<HeadsetCommand> {
            val all = listOf(
                HeadsetCommand.SetMode(0), HeadsetCommand.SaveMode(5, "x", NoiseSettings()), HeadsetCommand.RemoveMode(5),
                HeadsetCommand.SetFavorites(setOf(0)), HeadsetCommand.SetNoise(NoiseSettings()), HeadsetCommand.SetEq(HeadphoneEq(0, 0, 0)),
                HeadsetCommand.SetSidetone(Sidetone.LOW), HeadsetCommand.SetAutoPlayPause(true), HeadsetCommand.SetAutoAnswer(true),
                HeadsetCommand.SetMultipoint(true), HeadsetCommand.SetAutoOff(5), HeadsetCommand.SetVoicePrompts(true),
                HeadsetCommand.SetShortcut(ButtonAction.BATTERY_LEVEL), HeadsetCommand.SetTouchControls(true),
                HeadsetCommand.SetDeviceName("x"), HeadsetCommand.SetLeAudio(true), HeadsetCommand.SetLatency(100),
                HeadsetCommand.SetVolume(5), HeadsetCommand.Transport(TransportControl.PAUSE),
                HeadsetCommand.SwitchDevice("00:00:00:00:00:00"), HeadsetCommand.SetVendorControl("x", 0), HeadsetCommand.PowerOff,
            )
            return all.filter { runsOnSkullcandy(it) == false }
        }

        /** True where [BragiSession.run] may send something, false where it throws at once. Exhaustive on purpose. */
        private fun runsOnSkullcandy(c: HeadsetCommand): Boolean = when (c) {
            is HeadsetCommand.SetMode, is HeadsetCommand.SetNoise, is HeadsetCommand.SetSidetone, is HeadsetCommand.SetVendorControl -> true
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites, is HeadsetCommand.SetEq,
            is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer, is HeadsetCommand.SetMultipoint,
            is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts, is HeadsetCommand.SetShortcut,
            is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName, is HeadsetCommand.SetLeAudio,
            is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume, is HeadsetCommand.Transport,
            is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> false
        }

        fun kind(p: BosRpcPacket): String = "${p.serviceId}.${p.methodId}"
    }

    /**
     * Every call Ruru may send, as `service.method`, written out here: device 2.1-2.5 (info reads),
     * hearing 4.1 / 4.3 / 4.4 / 4.5 / 4.6 / 4.10 / 4.12 (reads) and 4.2 / 4.11 (mode and sidetone
     * writes), spatial audio 17.1 (read) and 17.2 (write), Crusher 23.1 / 23.2 (reads) and 23.3
     * (write), and the secure-session handshake 25.1-25.7. Nothing else: 2.6 (set the clock), 4.7-4.9
     * (equaliser) and every number the Skullcandy app has for firmware update are left out.
     */
    private val permitted: Set<String> = setOf(
        "2.1", "2.2", "2.3", "2.4", "2.5",
        "4.1", "4.2", "4.3", "4.4", "4.5", "4.6", "4.10", "4.11", "4.12",
        "17.1", "17.2",
        "23.1", "23.2", "23.3",
        "25.1", "25.2", "25.3", "25.4", "25.5", "25.6", "25.7",
    )

    /** Calls that must never leave, with why. Spelled out here, not taken from the guard. */
    private val forbidden: Map<String, String> = mapOf(
        "2.6" to "set the clock", "4.7" to "equaliser configure", "4.8" to "equaliser enable", "4.9" to "equaliser disable",
        "2.0" to "an undocumented device method", "2.7" to "an undocumented device method", "4.0" to "an undocumented hearing method",
        "4.13" to "an undocumented hearing method", "17.0" to "an undocumented spatial method", "17.3" to "an undocumented spatial method",
        "23.4" to "an undocumented Crusher method", "25.0" to "an undocumented handshake method", "25.8" to "an undocumented handshake method",
    )

    /** Any other service number is a service nobody documented: firmware update and the maker's feature toggles live there. */
    private val knownServices = setOf(2, 4, 17, 23, 25)

    private fun isForbidden(p: BosRpcPacket): String? = when {
        p.type != BosMessageType.REQUEST -> "not a request"
        p.serviceId !in knownServices -> "an undocumented service (firmware update and feature toggles live in services like this)"
        kind(p) in forbidden -> forbidden.getValue(kind(p))
        kind(p) !in permitted -> "not on Ruru's list"
        else -> null
    }

    /** Every command and every control value, the Lab and the pairing, so the headphones see every kind the session can send. */
    private suspend fun TestScope.kindsSent(device: VirtualSkullcandy, name: String): List<BosRpcPacket> {
        val transport = VirtualSkullcandyTransport(device)
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val session = BragiSession(conn, MemoryBragiTrustStore(), { _, _ -> true })
        var state: HeadphoneState = session.start(HeadphoneState(connection = io.github.langkurst.ruru.core.ConnectionState(deviceName = name)))
        suspend fun run(c: HeadsetCommand) { state = session.run(c, state) }
        // A command the model refuses throws: that is fine, only what is sent matters.
        suspend fun tryRun(c: HeadsetCommand) { try { run(c) } catch (_: HeadphoneCommandException) { } }
        session.labProfile()
        session.scanLab()
        for (round in 0..1) {
            for (i in 0..3) tryRun(HeadsetCommand.SetMode(i))
            for (on in listOf(false, true)) tryRun(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = on, cancellingLevel = if (on) 10 else 0)))
            for (level in 0..10 step 5) tryRun(HeadsetCommand.SetNoise(NoiseSettings(cancellingLevel = level)))
            for (a in SpatialAudio.entries) tryRun(HeadsetCommand.SetNoise(state.noise!!.copy(spatialAudio = a)))
            for (level in Sidetone.entries) tryRun(HeadsetCommand.SetSidetone(level))
            for (control in state.vendorControls.toList()) {
                val values = when {
                    control.options != null -> control.options!!.indices.toList() + control.options!!.size
                    control.range != null -> listOf(control.range!!.first, control.range!!.last, control.range!!.last + 1)
                    else -> listOf(0, 1)
                }
                for (v in values) tryRun(HeadsetCommand.SetVendorControl(control.id, v))
            }
            tryRun(HeadsetCommand.SetVendorControl("skullcandy_firmware_update", 1))
            session.pollBattery(state)
        }
        for (c in unsupportedCommands()) tryRun(c)
        conn.close()
        return device.received.toList()
    }

    private class Case(val name: String, val make: () -> VirtualSkullcandy, val bluetoothName: String)

    private val cases = listOf(
        Case("crusher", { VirtualSkullcandy() }, VirtualSkullcandy.NAME_CRUSHER),
        Case("crusher-secure", { VirtualSkullcandy(authenticatedAccess = true) }, VirtualSkullcandy.NAME_CRUSHER),
        Case("method360", { VirtualSkullcandy.method360() }, VirtualSkullcandy.NAME_METHOD_360),
        Case("method540", { VirtualSkullcandy.method540() }, VirtualSkullcandy.NAME_METHOD_540),
    )

    @Test fun `every outgoing Skullcandy call kind is on the list and none is firmware, equaliser or clock`() = runTest {
        for (case in cases) {
            val sent = kindsSent(case.make(), case.bluetoothName)
            val kinds = sent.map(::kind).toSortedSet(compareBy({ it.substringBefore('.').toInt() }, { it.substringAfter('.').toInt() }))
            println("Skullcandy ${case.name} call kinds sent: $kinds")
            for (p in sent) {
                assertEquals(null, isForbidden(p), "${case.name}: ${kind(p)} is ${isForbidden(p)}")
                assertTrue(BragiWriteGuard.isAllowed(p.address, p.payload), "${case.name}: ${kind(p)} is not allowed by the guard")
            }
            assertEquals(EXPECTED_KINDS.getValue(case.name), kinds.toList(), "${case.name}: the call kinds the session sends changed: review each new one against the guard")
        }
    }

    @Test fun `the checker really catches what it names, and the guard refuses all of it`() {
        // The check above is only worth something if it fails on the dangerous calls.
        val dangerous = forbidden.keys.map { k -> BosRpcPacket.request(BragiServices.Address(k.substringBefore('.').toInt(), k.substringAfter('.').toInt()), 1, byteArrayOf(0x08, 0x01)) } +
            listOf(1, 3, 5, 8, 10, 12, 16, 18, 20, 21, 22, 24, 26, 30, 99).map { BosRpcPacket.request(BragiServices.Address(it, 1), 1) }
        for (p in dangerous) {
            assertNotNull(isForbidden(p), kind(p))
            assertTrue(!BragiWriteGuard.isAllowed(p.address, p.payload), "the guard refuses ${kind(p)}")
        }
        // And the checker lets the documented kinds through.
        assertEquals(null, isForbidden(BosRpcPacket.request(BragiServices.MODE_CONFIGURE, 1, Hearing.modeConfigure(HearingMode.ANC, activate = true))))
        assertEquals(null, isForbidden(BosRpcPacket.request(BragiServices.CRUSHER_STATE_SET, 1, Crusher.stateSet(intensity = 3))))
    }

    @Test fun `the guard's own list matches the one written out here`() {
        assertEquals(permitted, BragiWriteGuard.SENDABLE.map { "${it.service}.${it.method}" }.toSet())
        assertTrue(BragiWriteGuard.SENDABLE.all { it in BragiServices.ALLOWED }, "the guard is never wider than the protocol's own list")
        assertTrue(BragiServices.ALLOWED.size > BragiWriteGuard.SENDABLE.size, "the equaliser and clock writes are in the protocol layer only")
    }

    @Test fun `a frame written past the connection reaches the device and is caught by this test, which the connection itself refuses`() = runTest {
        val device = VirtualSkullcandy()
        val transport = VirtualSkullcandyTransport(device)
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val session = BragiSession(conn, MemoryBragiTrustStore(), PairingPrompt.DECLINE)
        session.start(HeadphoneState())
        device.received.clear()

        // Through the connection: refused before a byte leaves, the device sees nothing.
        for (k in forbidden.keys) {
            val a = BragiServices.Address(k.substringBefore('.').toInt(), k.substringAfter('.').toInt())
            assertFailsWith<HeadphoneCommandException>(k) { conn.call(a, byteArrayOf(0x08, 0x01)) }
        }
        assertEquals(emptyList(), device.received.mapNotNull { p -> isForbidden(p)?.let { "${kind(p)} ($it)" } })

        // Past it, straight onto the transport: the device receives it, and the checker names it. Were the guard
        // loosened, the main test's frames would be caught the same way.
        transport.write(PppFraming.frame(BosRpcPacket.request(BragiServices.TIME_SET, 9, byteArrayOf(0x08, 0x01)).encode()))
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        val leaked = device.received.mapNotNull { p -> isForbidden(p)?.let { "${kind(p)} ($it)" } }
        assertEquals(listOf("2.6 (set the clock)"), leaked)
        conn.close()
    }

    @Test fun `a body that is not what Ruru builds is refused for the calls it may send`() {
        val a = BragiServices
        // mode_configure: OFF carries no gain; an unknown mode; an extra field.
        assertTrue(!BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, Hearing.modeConfigure(HearingMode.OFF, activate = true, gain = 3)))
        assertTrue(!BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, byteArrayOf(0x08, 0x09, 0x10, 0x01)))
        assertTrue(!BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, byteArrayOf(0x08, 0x02, 0x10, 0x01, 0x28, 0x01)))
        assertTrue(!BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, ByteArray(0)))
        assertTrue(BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, Hearing.modeConfigure(HearingMode.AT, activate = false, gain = 7)))
        // spatial: only the three modes.
        assertTrue(!BragiWriteGuard.isAllowed(a.SPATIAL_MODE_SET, byteArrayOf(0x08, 0x07)))
        assertTrue(!BragiWriteGuard.isAllowed(a.SPATIAL_MODE_SET, ByteArray(0)))
        // sidetone: something to set, and a sane volume.
        assertTrue(!BragiWriteGuard.isAllowed(a.SIDETONE_CONFIGURE, ByteArray(0)))
        assertTrue(!BragiWriteGuard.isAllowed(a.SIDETONE_CONFIGURE, Hearing.sidetoneConfigure(volume = 1000)))
        // crusher: something to set, a sane intensity and feel.
        assertTrue(!BragiWriteGuard.isAllowed(a.CRUSHER_STATE_SET, ByteArray(0)))
        assertTrue(!BragiWriteGuard.isAllowed(a.CRUSHER_STATE_SET, Crusher.stateSet(intensity = 1000)))
        assertTrue(!BragiWriteGuard.isAllowed(a.CRUSHER_STATE_SET, Crusher.stateSet(mode = 99)))
        // not protobuf at all
        assertTrue(!BragiWriteGuard.isAllowed(a.MODE_CONFIGURE, byteArrayOf(0x08)))
        // a read with a long body is not a read Ruru makes.
        assertTrue(!BragiWriteGuard.isAllowed(a.BATT_INFO, ByteArray(200)))
    }

    /** Per case: every `service.method` the session can put on the wire, Lab reads and the pairing included. */
    private val EXPECTED_KINDS: Map<String, List<String>> = mapOf(
        "crusher" to listOf("2.1", "2.2", "2.3", "2.4", "2.5", "4.1", "4.2", "4.3", "4.4", "4.5", "4.6", "4.10", "4.11", "4.12", "17.1", "17.2", "23.1", "23.2", "23.3"),
        "crusher-secure" to listOf("2.1", "2.2", "2.3", "2.4", "2.5", "4.1", "4.2", "4.3", "4.4", "4.5", "4.6", "4.10", "4.11", "4.12", "17.1", "17.2", "23.1", "23.2", "23.3", "25.1", "25.2", "25.3", "25.4", "25.5", "25.6"),
        "method360" to listOf("2.1", "2.2", "2.3", "2.4", "2.5", "4.1", "4.2", "4.3", "4.4", "4.5", "4.6", "4.10", "4.11", "4.12"),
        "method540" to listOf("2.1", "2.2", "2.3", "2.4", "2.5", "4.1", "4.2", "4.3", "4.4", "4.5", "4.6", "4.10", "4.11", "4.12"),
    )
}
