package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.link.WriteGuard
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.session.QcUltra2Protocol
import io.github.langkurst.ruru.vendors.bose.BoseSession
import io.github.langkurst.ruru.vendors.bragi.BosMessageType
import io.github.langkurst.ruru.vendors.bragi.BragiConnection
import io.github.langkurst.ruru.vendors.bragi.BragiServices
import io.github.langkurst.ruru.vendors.bragi.BragiSession
import io.github.langkurst.ruru.vendors.bragi.MemoryBragiTrustStore
import io.github.langkurst.ruru.vendors.bragi.VirtualSkullcandy
import io.github.langkurst.ruru.vendors.bragi.VirtualSkullcandyTransport
import io.github.langkurst.ruru.vendors.gaia.GaiaCommand
import io.github.langkurst.ruru.vendors.jbl.JblConnection
import io.github.langkurst.ruru.vendors.jbl.JblSession
import io.github.langkurst.ruru.vendors.jbl.JblWriteGuard
import io.github.langkurst.ruru.vendors.jbl.VirtualJbl
import io.github.langkurst.ruru.vendors.jbl.VirtualJblTransport
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserConnection
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserReads
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserSession
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserWriteGuard
import io.github.langkurst.ruru.vendors.sennheiser.VirtualSennheiser
import io.github.langkurst.ruru.vendors.sennheiser.VirtualSennheiserTransport
import io.github.langkurst.ruru.vendors.sony.SonyConnection
import io.github.langkurst.ruru.vendors.sony.SonyReads
import io.github.langkurst.ruru.vendors.sony.SonySession
import io.github.langkurst.ruru.vendors.sony.SonyWriteGuard
import io.github.langkurst.ruru.vendors.sony.VirtualSony
import io.github.langkurst.ruru.vendors.sony.VirtualSonyTransport
import io.github.langkurst.ruru.vendors.soundcore.AnonymousVirtualSoundcoreTransport
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreConnection
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreModel
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreSession
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreWriteGuard
import io.github.langkurst.ruru.vendors.soundcore.VirtualSoundcore
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Lab is read-only for every maker: with each maker's session open on its simulated headset, whatever the Lab
 * puts on the wire is a read (a GET, or the maker's documented inquiry) that the maker's write guard lets out.
 * Each test starts counting after the session has started, so only the Lab's own frames are judged, and it
 * checks that the Lab did send something (an empty scan would pass for the wrong reason).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabReadOnlyTest {
    private suspend fun scan(session: HeadsetSession): List<String> {
        assertTrue(session.labProfile()!!.reads.isNotEmpty(), "${session.family}: the Lab lists nothing it would read")
        return session.scanLab().also { assertTrue(it.isNotEmpty(), "${session.family}: the scan gave no lines") }
    }

    @Test fun `Bose Lab sends only GETs the write guard allows`() = runTest {
        val transport = VirtualTransport(VirtualQcUltra2())
        val conn = BmapConnection(transport, backgroundScope, minWriteGapMs = 0)
        val session = BoseSession(conn, QcUltra2Protocol())
        session.start(HeadphoneState())
        val before = transport.sentRequests().size
        scan(session)
        val lab = transport.sentRequests().drop(before)
        assertTrue(lab.size > 20, "Bose: the Lab sent only ${lab.size} frames")
        for (p in lab) {
            assertEquals(BmapOperator.GET, p.operator, "Bose ${p.address} is not a GET")
            assertEquals(null, WriteGuard.refusalReason(p), "Bose ${p.address} is refused by the write guard")
        }
    }

    @Test fun `Sony Lab sends only reads the write guard allows`() = runTest {
        val unit = VirtualSony()
        val conn = SonyConnection(VirtualSonyTransport(unit), backgroundScope, openDelayMs = 0, ackTimeoutMs = 50, replyTimeoutMs = 500)
        val session = SonySession(conn, backgroundScope)
        session.start(HeadphoneState())
        unit.received.clear()
        scan(session)
        assertTrue(unit.received.isNotEmpty(), "Sony: nothing was sent")
        for (m in unit.received) {
            assertTrue(SonyReads.isRead(m), "Sony ${m.table} ${m.command} ${m.type} is not a read")
            assertTrue(SonyWriteGuard.isAllowed(m))
        }
    }

    @Test fun `Sennheiser Lab sends only reads the write guard allows`() = runTest {
        val unit = VirtualSennheiser()
        val conn = SennheiserConnection(VirtualSennheiserTransport(unit), backgroundScope)
        val session = SennheiserSession(conn, "MOMENTUM 4", clock = { 0L })
        session.start(HeadphoneState())
        unit.received.clear()
        scan(session)
        assertTrue(unit.received.isNotEmpty(), "Sennheiser: nothing was sent")
        val discovery = SennheiserReads.DISCOVERY.map { it.vendor to it.command }.toSet()
        for (f in unit.received) {
            assertEquals(GaiaCommand.TYPE_COMMAND, GaiaCommand.type(f.command), "Sennheiser ${f.command} is not a command-type frame")
            val known = (f.vendor to f.command) in discovery || f.command in SennheiserWriteGuard.READS
            assertTrue(known, "Sennheiser ${f.vendor}:${f.command} is not on the read list")
        }
    }

    @Test fun `Soundcore Lab sends only reads the write guard allows`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.SLEEP_A30)
        val conn = SoundcoreConnection(AnonymousVirtualSoundcoreTransport(unit), backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope)
        session.start(HeadphoneState())
        unit.received.clear()
        scan(session)
        assertTrue(unit.received.isNotEmpty(), "Soundcore: nothing was sent")
        for (p in unit.received) assertTrue(SoundcoreWriteGuard.isRead(p), "Soundcore command ${p.command} is not a read")
    }

    @Test fun `JBL Lab sends only reads the write guard allows`() = runTest {
        val unit = VirtualJbl()
        val conn = JblConnection(VirtualJblTransport(unit), backgroundScope)
        val session = JblSession(conn, unit.name, clock = { 0L })
        session.start(HeadphoneState())
        unit.received.clear()
        scan(session)
        assertTrue(unit.received.isNotEmpty(), "JBL: nothing was sent")
        for (f in unit.received) assertTrue(JblWriteGuard.isRead(f), "JBL command ${f.command} is not a read")
    }

    @Test fun `Skullcandy Lab sends only requests on the read list`() = runTest {
        val device = VirtualSkullcandy()
        val conn = BragiConnection(VirtualSkullcandyTransport(device), backgroundScope, clock = { testScheduler.currentTime })
        val session = BragiSession(conn, MemoryBragiTrustStore(), { _, _ -> true })
        session.start(HeadphoneState())
        device.received.clear()
        scan(session)
        assertTrue(device.received.isNotEmpty(), "Skullcandy: nothing was sent")
        for (p in device.received) {
            assertEquals(BosMessageType.REQUEST, p.type)
            assertTrue(BragiServices.isRead(BragiServices.Address(p.serviceId, p.methodId)), "Skullcandy ${p.serviceId}.${p.methodId} is not a read")
        }
    }

    @Test fun `every maker that has a session has a Lab test here`() {
        // A maker added to the registry with a session must be covered above: a new family fails this until its Lab test is written.
        val covered = setOf(DeviceFamily.BOSE, DeviceFamily.SONY, DeviceFamily.SENNHEISER, DeviceFamily.SOUNDCORE, DeviceFamily.JBL, DeviceFamily.BRAGI)
        val driven = VendorRegistry.vendors.filter { it !is AdvertVendor }.map { it.family }.toSet()
        assertEquals(covered, driven)
    }
}
