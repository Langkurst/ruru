package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.Playback
import io.github.langkurst.ruru.core.PlaybackState
import io.github.langkurst.ruru.core.TransportControl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareCheckPlanTest {
    private fun mode(i: Int, fav: Boolean) = ListeningMode(i, "m$i", isPreset = true, isEditable = false, isConfigured = true, isFavorite = fav)

    private fun state(
        favs: Set<Int> = setOf(0, 1, 2),
        current: Int? = 1,
        favouritesReported: Boolean = true,
        volume: Int? = 10,
        max: Int? = 31,
        playing: PlaybackState? = null,
        controls: Set<TransportControl> = emptySet(),
    ) = HeadphoneState(
        modes = (0..4).map { mode(it, it in favs) },
        currentModeIndex = current,
        capabilities = HeadphoneCapabilities(favoriteModes = favouritesReported),
        volume = volume,
        volumeMax = max,
        playback = playing?.let { Playback(it) },
        supportedTransportControls = controls,
        eq = HeadphoneEq(0, 0, 0),
    )

    // ---- step selection

    @Test fun `the default run is mode and bass`() =
        assertEquals(setOf(Step.MODE, Step.BASS), chooseSteps(emptyList()).steps)

    @Test fun `all adds favourites and volume`() =
        assertEquals(Step.entries.toSet(), chooseSteps(listOf("--all")).steps)

    @Test fun `only picks one step`() {
        assertEquals(setOf(Step.FAVOURITES), chooseSteps(listOf("--only=favourites")).steps)
        assertEquals(setOf(Step.VOLUME), chooseSteps(listOf("--only=volume", "--all")).steps)
    }

    @Test fun `an unknown only is an error and runs nothing`() {
        val c = chooseSteps(listOf("--only=firmware"))
        assertNotNull(c.error)
        assertTrue(c.steps.isEmpty())
    }

    // ---- planning

    @Test fun `favourites drop one that is not the current mode`() {
        val p = assertNotNull(planFavourites(state(favs = setOf(1, 2, 3), current = 1)))
        assertEquals(setOf(1, 2, 3), p.original)
        assertEquals(2, p.dropped)
        assertEquals(setOf(1, 3), p.changed)
    }

    @Test fun `favourites are skipped when not reported or fewer than two`() {
        assertNull(planFavourites(state(favouritesReported = false)))
        assertNull(planFavourites(state(favs = setOf(1))))
        assertNull(planFavourites(state(favs = emptySet())))
    }

    @Test fun `volume goes up one, or down one at the top`() {
        assertEquals(10 to 11, planVolume(state(volume = 10)))
        assertEquals(31 to 30, planVolume(state(volume = 31)))
        assertNull(planVolume(state(volume = null)))
        assertNull(planVolume(state(max = null)))
    }

    @Test fun `pause and play are only tested while playing with both supported`() {
        val both = setOf(TransportControl.PAUSE, TransportControl.PLAY)
        assertTrue(canTestPlayback(state(playing = PlaybackState.PLAYING, controls = both)))
        assertFalse(canTestPlayback(state(playing = PlaybackState.PAUSED, controls = both)))
        assertFalse(canTestPlayback(state(playing = null, controls = both)))
        assertFalse(canTestPlayback(state(playing = PlaybackState.PLAYING, controls = setOf(TransportControl.PAUSE))))
    }

    // ---- the pending note

    private val full = PlannedChanges(
        mode = 3, modeChangedTo = 5, eq = HeadphoneEq(1, 2, 3), eqChangedTo = HeadphoneEq(2, 2, 3),
        favourites = setOf(0, 1, 4), favouritesChangedTo = setOf(0, 4), volume = 10, volumeChangedTo = 11, paused = true,
    )

    @Test fun `the note format is stable`() {
        assertEquals("mode=3>5\neq=1,2,3>2,2,3\nfav=0,1,4>0,4\nvol=10>11\nplayback=paused\n", full.text())
    }

    @Test fun `the note survives a write and read and undoes only what is still changed`() {
        val file = File.createTempFile("hwcheck", ".txt")
        try {
            full.write(file)
            val back = assertNotNull(PlannedChanges.read(file))
            val stillChanged = state(favs = setOf(0, 4), current = 5, volume = 11, playing = PlaybackState.PAUSED,
                controls = setOf(TransportControl.PLAY)).copy(eq = HeadphoneEq(2, 2, 3))
            assertEquals(3, back.modeToPutBack(stillChanged))
            assertEquals(HeadphoneEq(1, 2, 3), back.eqToPutBack(stillChanged))
            assertEquals(setOf(0, 1, 4), back.favouritesToPutBack(stillChanged))
            assertEquals(10, back.volumeToPutBack(stillChanged))
            assertTrue(back.playToPutBack(stillChanged))
            // Anything chosen since is left alone.
            val moved = state(favs = setOf(0, 1, 4), current = 2, volume = 20, playing = PlaybackState.PLAYING,
                controls = setOf(TransportControl.PLAY))
            assertNull(back.modeToPutBack(moved))
            assertNull(back.favouritesToPutBack(moved))
            assertNull(back.volumeToPutBack(moved))
            assertFalse(back.playToPutBack(moved))
        } finally {
            file.delete()
        }
    }

    @Test fun `an old note with only mode and eq still reads, and asks for nothing new`() {
        val old = PlannedChanges.parse("mode=1>2\neq=0,0,0>1,0,0\n")
        val now = state(favs = setOf(0, 1), current = 2, volume = 5, playing = PlaybackState.PAUSED, controls = setOf(TransportControl.PLAY))
        assertEquals(1, old.modeToPutBack(now))
        assertNull(old.favouritesToPutBack(now))
        assertNull(old.volumeToPutBack(now))
        assertFalse(old.playToPutBack(now))
    }

    @Test fun `a note with garbled new lines undoes nothing rather than guessing`() {
        val bad = PlannedChanges.parse("fav=a,b>1\nvol=x>y\nplayback=maybe\n")
        val now = state(favs = setOf(1), volume = 5, playing = PlaybackState.PAUSED, controls = setOf(TransportControl.PLAY))
        assertNull(bad.favouritesToPutBack(now))
        assertNull(bad.volumeToPutBack(now))
        assertFalse(bad.playToPutBack(now))
    }
}
