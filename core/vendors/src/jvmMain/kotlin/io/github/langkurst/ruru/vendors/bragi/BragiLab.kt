package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.HeadphoneCommandException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on Bragi BOS-RPC (Skullcandy Crusher 1080, Method 360 / 540; Earshot's BragiLab, MIT):
 * the read-only methods on [BragiReads], grouped by service. Never a configure / set call, never
 * the pairing handshake, never a method number outside the shipped definitions. A Method earbud is
 * not asked about the Crusher bass or spatial audio, which it cannot have. Each read is checked
 * against [BragiServices.isRead] again before it is sent, and the connection's [BragiWriteGuard]
 * checks it a third time.
 */
internal object BragiLab {
    const val STACK = "Bragi BOS-RPC"
    const val TIMEOUT_MS = 2_000L

    fun reads(model: BragiModel): List<BragiRead> = BragiReads.forFeatures(model.features)

    /** One line per read: its address, name and the reply (its size, and its bytes except where they hold the serial number) or why there is none. */
    suspend fun scan(conn: BragiConnection, model: BragiModel): List<String> = buildList {
        for (read in reads(model)) {
            val a = read.address
            val head = "${a.service}.${a.method} ${read.name}:"
            if (!BragiServices.isRead(a)) {
                add("$head refused (not a read)")
                continue
            }
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            try {
                // Unwatched: a method this firmware lacks must not count towards the half-dead-link watchdog.
                val reply = conn.callUnwatched(a, read.body, timeoutMs = TIMEOUT_MS, streaming = read.streaming)
                val bytes = reply.payload
                // dev_info holds the serial number: a report people paste into bug reports gets its size only.
                val shown = if (a == BragiServices.DEV_INFO) "" else bytes.joinToString("") { " " + (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                add("$head ${bytes.size} bytes$shown")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
