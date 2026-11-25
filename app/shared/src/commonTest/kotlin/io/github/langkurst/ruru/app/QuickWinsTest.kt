package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.BatteryDetail
import io.github.langkurst.ruru.core.BatteryLevel
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.Playback
import io.github.langkurst.ruru.core.PlaybackState
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.ui.hasNowPlaying
import io.github.langkurst.ruru.ui.minutesLeft
import io.github.langkurst.ruru.ui.playPauseTarget
import io.github.langkurst.ruru.ui.showMicChip
import io.github.langkurst.ruru.ui.transportButtons
import io.github.langkurst.ruru.protocol.bose.PlaybackStatus
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Favourite modes, time left, the mic chip, and now playing with volume: each command reaches the
 * simulated headset, and each feature stays hidden when the headphones don't report it.
 */
class QuickWinsTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        Logging.sink = { _, _, _, _ -> }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class DevicePlatform(val device: VirtualQcUltra2) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        override val routes = setOf(RouteKind.BOSE_RFCOMM)
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
            device.powerOn()
            return VirtualTransport(device)
        }
    }

    private fun connect(profile: VirtualProfile = DemoPlatform.PROFILE): Pair<HeadsetController, VirtualQcUltra2> {
        val device = VirtualQcUltra2(profile = profile)
        return HeadsetController(DevicePlatform(device), scope).also { it.start() } to device
    }

    private suspend fun HeadsetController.await(predicate: (AppState) -> Boolean): AppState =
        withTimeout(15_000) { state.first(predicate) }

    private fun AppState.favourites() = headset.modes.filter { it.isFavorite }.map { it.index }.toSet()

    @Test
    fun `the demo headset shows favourites, time left, the strip and volume, and no mic chip`() = runBlocking {
        val (c, _) = connect()
        val h = c.await { it.phase == Phase.Connected }.headset
        assertEquals(setOf(0, 1), h.modes.filter { it.isFavorite }.map { it.index }.toSet())
        assertEquals(864, minutesLeft(h)) // 72% at the simulated rate: "about 14 h left"
        assertTrue(hasNowPlaying(h))
        assertEquals(22, h.volume)
        assertEquals(31, h.volumeMax)
        assertEquals(TransportControl.entries.toSet(), h.supportedTransportControls)
        assertFalse(showMicChip(h), "the 1st Gen has no mic mute")
        c.stop()
    }

    @Test
    fun `starring and unstarring a mode reaches the headset`() = runBlocking {
        val (c, device) = connect()
        c.await { it.phase == Phase.Connected }
        c.setFavorite(3, true)
        val added = c.await { 3 in it.favourites() }
        assertEquals(setOf(0, 1, 3), added.favourites())
        assertEquals(setOf(0, 1, 3), device.favorites)
        c.setFavorite(0, false)
        c.await { 0 !in it.favourites() }
        assertEquals(setOf(1, 3), device.favorites)
        assertNull(c.state.value.message)
        c.stop()
    }

    @Test
    fun `starring a mode that is already a favourite sends nothing`() = runBlocking {
        val (c, device) = connect()
        c.await { it.phase == Phase.Connected }
        c.setFavorite(1, true)
        c.setFavorite(2, false)
        kotlinx.coroutines.delay(400)
        assertEquals(setOf(0, 1), device.favorites)
        c.stop()
    }

    @Test
    fun `play, pause and next reach the headset and the state follows`() = runBlocking {
        val (c, device) = connect()
        c.await { it.phase == Phase.Connected && it.headset.playback?.state == PlaybackState.PLAYING }
        c.transport(TransportControl.PAUSE)
        c.await { it.headset.playback?.state == PlaybackState.PAUSED }
        assertEquals(PlaybackStatus.PAUSED, device.playback)
        c.transport(TransportControl.PLAY)
        c.await { it.headset.playback?.state == PlaybackState.PLAYING }
        assertEquals(PlaybackStatus.PLAYING, device.playback)
        c.transport(TransportControl.NEXT)
        kotlinx.coroutines.delay(300)
        assertNull(c.state.value.message)
        c.stop()
    }

    @Test
    fun `volume writes are debounced to the last value and read back`() = runBlocking {
        val (c, device) = connect()
        c.await { it.phase == Phase.Connected }
        for (v in listOf(5, 6, 7, 8)) c.setVolume(v)
        val s = c.await { it.headset.volume == 8 }
        assertEquals(8, device.volume)
        assertEquals(31, s.headset.volumeMax)
        // A change on the headphones themselves (the touch strip) is read back from the push.
        device.swipeVolume(+2)
        c.await { it.headset.volume == 10 }
        c.stop()
    }

    @Test
    fun `a volume above the top step is refused with a message`() = runBlocking {
        val (c, device) = connect()
        c.await { it.phase == Phase.Connected }
        c.setVolume(40)
        c.await { it.message != null }
        assertEquals(22, device.volume)
        c.stop()
    }

    @Test
    fun `time left, strip and volume stay hidden when the headphones report none`() = runBlocking {
        val profile = VirtualProfile.QC_ULTRA_1.copy(
            absent = VirtualProfile.QC_ULTRA_1.absent + setOf(QcUltra2.MIC_MUTE, QcUltra2.AUDIO_CONTROL, QcUltra2.AUDIO_STATUS, QcUltra2.VOLUME),
        )
        val (c, _) = connect(profile)
        val h = c.await { it.phase == Phase.Connected }.headset
        assertNull(minutesLeft(h), "a single battery without a time left")
        assertFalse(hasNowPlaying(h))
        assertFalse(transportButtons(h).any)
        assertNull(h.volume)
        assertNull(h.micMuted)
        assertFalse(showMicChip(h))
        c.stop()
    }

    @Test
    fun `the mic chip shows only while the headphones say the mic is muted`() = runBlocking {
        val (c, device) = connect(VirtualProfile.QC_ULTRA_2)
        val h = c.await { it.phase == Phase.Connected }.headset
        assertEquals(true, h.micMuted)
        assertTrue(showMicChip(h))
        device.setMicMuted(false)
        val after = c.await { it.headset.micMuted == false }.headset
        assertFalse(showMicChip(after))
        assertFalse(showMicChip(HeadphoneState()), "no reading, no chip")
        c.stop()
    }

    @Test
    fun `only the transport buttons the headphones list are drawn`() {
        val onlyNext = HeadphoneState(supportedTransportControls = setOf(TransportControl.NEXT))
        val b = transportButtons(onlyNext)
        assertTrue(b.next)
        assertFalse(b.previous)
        assertFalse(b.playPause)
        assertTrue(hasNowPlaying(onlyNext))
        assertFalse(hasNowPlaying(HeadphoneState()))
        assertFalse(hasNowPlaying(HeadphoneState(volume = 5)), "a volume without its top step is not shown")
        assertTrue(hasNowPlaying(HeadphoneState(volume = 5, volumeMax = 31)))
    }

    @Test
    fun `play pause sends pause while playing and play otherwise, only if listed`() {
        val all = TransportControl.entries.toSet()
        assertEquals(TransportControl.PAUSE, playPauseTarget(HeadphoneState(supportedTransportControls = all, playback = Playback(PlaybackState.PLAYING))))
        assertEquals(TransportControl.PLAY, playPauseTarget(HeadphoneState(supportedTransportControls = all, playback = Playback(PlaybackState.PAUSED))))
        assertEquals(TransportControl.PLAY, playPauseTarget(HeadphoneState(supportedTransportControls = all)))
        assertNull(playPauseTarget(HeadphoneState(supportedTransportControls = setOf(TransportControl.PLAY), playback = Playback(PlaybackState.PLAYING))))
        assertNull(playPauseTarget(HeadphoneState(supportedTransportControls = setOf(TransportControl.NEXT))))
    }

    @Test
    fun `time left comes from the whole battery, else the lower bud`() {
        assertEquals(125, minutesLeft(HeadphoneState(battery = BatteryDetail(overall = BatteryLevel(50, 125)))))
        assertEquals(90, minutesLeft(HeadphoneState(battery = BatteryDetail(left = BatteryLevel(40, 90), right = BatteryLevel(50, 120)))))
        assertNull(minutesLeft(HeadphoneState(battery = BatteryDetail(overall = BatteryLevel(50, null)))))
        assertNull(minutesLeft(HeadphoneState()))
    }

    private fun mode(i: Int, favourite: Boolean, name: String = "Mode $i", configured: Boolean = true) =
        ListeningMode(index = i, name = name, isPreset = i < 3, isEditable = i >= 3, isConfigured = configured, isFavorite = favourite)

    @Test
    fun `the tray lists the favourites only when some are set`() {
        val modes = listOf(mode(0, true), mode(1, false), mode(2, true), mode(3, false), mode(4, false, name = "", configured = false))
        assertEquals(listOf(0, 2), quickModes(HeadphoneState(modes = modes)).map { it.index })
        val none = modes.map { it.copy(isFavorite = false) }
        assertEquals(listOf(0, 1, 2, 3), quickModes(HeadphoneState(modes = none)).map { it.index }, "no favourites: every mode, the empty slot left out")
        val off = HeadphoneState(modes = modes, capabilities = HeadphoneCapabilities(favoriteModes = false))
        assertEquals(listOf(0, 1, 2, 3), quickModes(off).map { it.index }, "headphones without favourites ignore any flags")
        assertNotNull(modes)
    }
}
