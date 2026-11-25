package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.core.HeadphoneCommandException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on Soundcore: only [SoundcoreReads.forModel], the documented reads the model's allow-list
 * lets out. Soundcore reuses command ids across models and an unknown write can unpair the earbuds,
 * power them off or play a loud tone, so nothing is ever guessed. Each read gets one try
 * ([TIMEOUT_MS]): an unknown command is simply not answered, and three tries would only repeat it.
 */
internal object SoundcoreLab {
    const val STACK = "Soundcore"
    const val TIMEOUT_MS = 1_500L

    /** Reads whose reply holds the earbuds' serial (which embeds their address on some models): only the size is shown. */
    private val SIZE_ONLY = setOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO)

    fun reads(model: SoundcoreModel?): List<SoundcoreRead> = SoundcoreReads.forModel(model)

    /** One line per read: its address, name and the reply (the state and device info as sizes only). */
    suspend fun scan(conn: SoundcoreConnection, model: SoundcoreModel?): List<String> = buildList {
        for (read in reads(model)) {
            val head = "${SoundcorePacket.commandName(read.command)} ${read.name}:"
            if (!SoundcoreReads.isRead(model, read.command)) {
                add("$head refused (not a read)")
                continue
            }
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            try {
                val reply = conn.request(read.command, ByteArray(0), timeoutsMs = listOf(TIMEOUT_MS)).body
                val bytes = if (read.command in SIZE_ONLY) "" else " " + reply.joinToString(" ") { hex2(it.toInt()) }
                add("$head ${reply.size} bytes$bytes")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
