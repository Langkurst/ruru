package io.github.langkurst.ruru.vendors.sony

import io.github.langkurst.ruru.core.HeadphoneCommandException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on Sony (MDR v2): the connect inquiries, then the GET inquiries for each function the
 * headset announced in its support lists ([SonyReads.forSupport]). MDR v2 command bytes sit in
 * blocks of ten next to their writes, and table 1 also holds firmware update, the test command,
 * usage logging and factory reset, so no id is guessed. Each read goes through [SonyReads.isRead]
 * and then [SonyConnection]'s write guard.
 */
internal object SonyLab {
    const val STACK = "Sony MDR v2"

    fun reads(s: SonySnapshot): List<SonyRead> = SonyReads.forSupport(s.t1, s.t2, table2 = s.protocol?.table2 == true)

    /** One line per read: its address, name and the reply. The capability info is not printed, it holds the headset's own address. */
    suspend fun scan(conn: SonyConnection, snapshot: SonySnapshot): List<String> = buildList {
        for (read in reads(snapshot)) {
            val message = read.message()
            val head = "${read.address} ${read.name}:"
            if (!SonyReads.isRead(message)) {
                add("$head refused (not a read)")
                continue
            }
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            try {
                val reply = conn.request(message).payload
                val body = if (read.command == T1.CONNECT_GET_CAPABILITY_INFO) "" else " " + reply.joinToString(" ") { "%02x".format(it) }
                add("$head ${reply.size} bytes$body")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
