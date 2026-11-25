package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.vendors.gaia.GaiaError
import io.github.langkurst.ruru.vendors.gaia.GaiaReply
import io.github.langkurst.ruru.vendors.gaia.hex
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on Sennheiser (GAIA v3; Earshot's SennheiserLab, MIT): discovery (the GAIA API version
 * and both feature lists), then every documented GET of each feature the headset reported
 * ([SennheiserReads.forFeatures]). GAIA numbers commands per feature and the neighbours of the
 * reads include factory reset, firmware update and pairing deletes, so no id is guessed. The reads
 * follow the list the session stored on the connection (the one [SennheiserWriteGuard] checks), and
 * each one is checked against it again before it is sent.
 */
internal object SennheiserLab {
    const val STACK = "Sennheiser GAIA v3"

    fun reads(reported: Map<Int, Int>?): List<SennheiserRead> = SennheiserReads.forFeatures(reported)

    /** One line per read: its address, name and the reply or why there is none. */
    suspend fun scan(conn: SennheiserConnection): List<String> = buildList {
        for (read in reads(conn.features)) {
            val head = "${read.address} ${read.name}:"
            if (!SennheiserReads.isRead(read, conn.features)) {
                add("$head refused (not a read)")
                continue
            }
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            val timeout = if (read.slow) SennheiserConnection.SLOW_TIMEOUT_MS else SennheiserConnection.DEFAULT_TIMEOUT_MS
            try {
                when (val reply = conn.request(read.vendor, read.command, read.payload, timeout)) {
                    is GaiaReply.Ok -> add("$head ${reply.payload.size} bytes" + reply.payload.joinToString("") { " " + hex(it.toInt() and 0xFF, 1) })
                    is GaiaReply.Error -> add("$head ${GaiaError.name(reply.code)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
