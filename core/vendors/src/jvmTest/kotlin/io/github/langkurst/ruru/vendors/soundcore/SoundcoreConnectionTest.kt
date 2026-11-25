package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.link.ByteTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SoundcoreConnection] when the link drops under a request: the caller must hear "not connected"
 * (a [HeadphoneCommandException]), never a bare cancellation, so the controller backs off and
 * retries instead of ending in CONNECTING. And the rules a request has to pass before a byte leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreConnectionTest {

    /** A transport the test drives: [onWrite] runs for every frame Ruru sends. */
    private class ScriptedTransport : ByteTransport {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = mutableListOf<ByteArray>()
        var onWrite: (ByteArray) -> Unit = {}
        override var isConnected = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) {
            written += bytes
            onWrite(bytes)
        }
        override fun close() {
            isConnected = false
            inbound.close()
        }
    }

    @Test fun `a socket that closes while the answer is awaited fails with not connected`() = runTest {
        val t = ScriptedTransport()
        // The Soundcore app holds the vendor service: the earbuds accept the socket, then close it.
        t.onWrite = { t.inbound.close() }
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val e = assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.REQUEST_STATE) }
        assertEquals(SoundcoreConnection.NOT_CONNECTED, e.message)
        assertFalse(conn.isOpen)
        assertEquals(1, t.written.size, "no retry is written on a closed link")
        assertNull(conn.awaitClosed(), "a remote EOF is not an error")
    }

    @Test fun `an answer still arrives normally`() = runTest {
        val t = ScriptedTransport()
        t.onWrite = { t.inbound.trySend(SoundcorePacket(SoundcoreCommands.REQUEST_STATE, byteArrayOf(1, 2), inbound = true).encode()) }
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        assertEquals(SoundcoreCommands.REQUEST_STATE, reply.command)
        assertEquals(listOf<Byte>(1, 2), reply.body.toList())
        assertTrue(conn.hasFramed)
        conn.close()
        val after = assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.REQUEST_STATE) }
        assertEquals(SoundcoreConnection.NOT_CONNECTED, after.message)
    }

    @Test fun `an unanswered request is tried three times with growing waits, then fails`() = runTest {
        val t = ScriptedTransport()
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val start = testScheduler.currentTime
        assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.REQUEST_STATE) }
        assertEquals(3, t.written.size, "three attempts, as OpenSCQ30 does")
        assertEquals(500L + 1_000L + 1_500L, testScheduler.currentTime - start)
        conn.close()
    }

    @Test fun `a connection that does not know its model sends only the state request, and no write before the unlock`() = runTest {
        val t = ScriptedTransport()
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        assertNull(conn.model)
        assertFalse(conn.writesUnlocked)
        // Not the Sleep A30's writes, not a model's: nothing but the state request.
        for ((command, body) in listOf(0x0687 to byteArrayOf(1), 0x0681 to ByteArray(7), 0x0183 to byteArrayOf(1), 0x0B83 to byteArrayOf(1), 0x0105 to ByteArray(0))) {
            assertFailsWith<HeadphoneCommandException> { conn.request(command, body) }
        }
        assertTrue(t.written.isEmpty(), "nothing was written")

        // Identified but not unlocked: reads only.
        conn.identify(SoundcoreModel.LIBERTY_4_NC, writable = false)
        assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1)) }
        assertTrue(t.written.isEmpty())
        t.onWrite = { f -> SoundcoreFrameBuffer().feed(f).forEach { t.inbound.trySend(SoundcorePacket(it.command, inbound = true).encode()) } }
        conn.identify(SoundcoreModel.LIBERTY_4_NC, writable = true)
        conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(1))
        assertEquals(1, t.written.size)
        // The unlock can be taken back when a later state no longer fits.
        conn.unlockWrites(false)
        assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.SET_TOUCH_TONE, byteArrayOf(0)) }
        assertEquals(1, t.written.size)
        conn.close()
    }

    @Test fun `the demo transport names its model, a bare byte pipe does not`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val named = SoundcoreConnection(VirtualSoundcoreTransport(unit), backgroundScope, minWriteGapMs = 0)
        assertEquals(SoundcoreModel.LIBERTY_4_NC, named.model)
        assertFalse(named.writesUnlocked, "a model that is only named is still locked until its state matched")
        val bare = SoundcoreConnection(AnonymousVirtualSoundcoreTransport(unit), backgroundScope, minWriteGapMs = 0)
        assertNull(bare.model)
        named.close()
        bare.close()
    }
}
