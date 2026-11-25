package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.vendors.LabProfile
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on JBL's H-AA (Earshot's JblLab, MIT): the documented reads in [JblReads] (jbl-control's
 * probes of a Wave Buds 2 plus the app's device-info and battery reads). Command ids around them
 * include factory reset (`95`), power off (`97`) and firmware transfer (`F0`), and H-AA has no
 * feature list to narrow the choice, so the Lab never walks ids: each read is checked against
 * [JblWriteGuard.isRead] before it is sent, and the connection checks the guard again on its single
 * write path.
 *
 * H-AA numbers nothing, so a read's address is its command byte and first payload byte (`91 21`)
 * and "answered" means the next frame of the right kind ([JblRead.answers]). A headset without the
 * feature stays silent, which shows as "no answer".
 */
internal object JblLab {
    const val STACK = "JBL H-AA"

    /** Reads (by the command that asks) whose reply holds the headset's name, serial or address: only the size is shown. */
    private val SIZE_ONLY = setOf(JblCommands.DEVICE_INFO_GET, JblCommands.INFO, JblCommands.SERIAL)

    fun address(read: JblRead): String = JblBytes.hex(read.frame.command) + (read.frame.sub?.let { " " + JblBytes.hex(it) } ?: "")

    fun profile(): LabProfile = LabProfile(STACK, JblReads.ALL.map { "${address(it)}: ${it.name}" })

    /** One line per read: its address, name and the reply (device info, TLV and serial as sizes only). */
    suspend fun scan(conn: JblConnection): List<String> = buildList {
        for (read in JblReads.ALL) {
            val head = "${address(read)} ${read.name}:"
            if (!JblWriteGuard.isRead(read.frame)) {
                add("$head refused (not a read)")
                continue
            }
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            try {
                // A model without the feature stays silent: that never counts toward closing the link.
                val reply = conn.request(read.frame, attempts = 1, countsSilence = false, answers = read.answers)
                if (reply == null) {
                    add("$head no answer")
                } else {
                    val bytes = if (read.frame.command in SIZE_ONLY) "" else reply.payload.joinToString("") { " " + JblBytes.hex(it.toInt() and 0xFF) }
                    add("$head ${reply.size} bytes$bytes")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
