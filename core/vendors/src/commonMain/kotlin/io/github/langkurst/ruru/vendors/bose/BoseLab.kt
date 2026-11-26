package io.github.langkurst.ruru.vendors.bose

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.link.ReplyMode
import io.github.langkurst.ruru.link.WriteGuard
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.FunctionAddress
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.protocol.toHex
import io.github.langkurst.ruru.vendors.LabProfile
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Lab on Bose: one plain GET for each of a fixed list of settings and status reads, the same
 * ones the probe tool makes. The list leaves out everything that names a person's gear: the
 * headphones' own address and serial, the paired devices and their names, the custom name, and
 * blocks 3 (firmware) and 18 (authentication). Each frame goes through [WriteGuard] and then
 * [BmapConnection], which keeps its own guard, so a write cannot leave from here.
 */
internal object BoseLab {
    const val STACK = "Bose BMAP"
    const val TIMEOUT_MS = 1_500L

    /** One read: [label] for the list and where it is. */
    class Read(val label: String, val address: FunctionAddress, val sizeOnly: Boolean = false)

    val READS: List<Read> = listOf(
        Read("Protocol version", QcUltra2.BMAP_VERSION),
        Read("Product id", QcUltra2.PRODUCT_ID_VARIANT),
        Read("Firmware", QcUltra2.FIRMWARE_VERSION),
        Read("Model name", QcUltra2.PRODUCT_NAME),
        Read("LE Audio supported", QcUltra2.LE_AUDIO_SUPPORTED),
        Read("Voice prompts", QcUltra2.VOICE_PROMPTS),
        Read("Auto-off", QcUltra2.STANDBY_TIMER),
        Read("Noise cancelling", QcUltra2.CNC),
        Read("Equaliser", QcUltra2.EQ),
        Read("Buttons", QcUltra2.BUTTONS),
        Read("Multipoint", QcUltra2.MULTIPOINT),
        Read("Self voice", QcUltra2.SIDETONE),
        Read("Auto play/pause", QcUltra2.AUTO_PLAY_PAUSE),
        Read("Auto-answer", QcUltra2.AUTO_ANSWER),
        Read("Touch controls", QcUltra2.DISABLE_CAPTOUCH),
        Read("LE Audio setting", QcUltra2.LE_AUDIO_CONTROL),
        Read("Battery", QcUltra2.BATTERY),
        Read("Charger", QcUltra2.CHARGER_DETECT),
        Read("Operation mode", QcUltra2.OPERATION_MODE),
        Read("Mic mute", QcUltra2.MIC_MUTE),
        Read("Device features", QcUltra2.DM_FEATURES),
        Read("Available to connect", QcUltra2.AVAILABLE_TO_CONNECT),
        // Names the source device by its address: only the size is shown.
        Read("Audio source", QcUltra2.AUDIO_SOURCE, sizeOnly = true),
        Read("Transport controls", QcUltra2.AUDIO_CONTROL),
        Read("Playback status", QcUltra2.AUDIO_STATUS),
        Read("Volume", QcUltra2.VOLUME),
        Read("Latency", QcUltra2.LATENCY),
        Read("Current mode", QcUltra2.CURRENT_MODE),
        Read("Mode capabilities", QcUltra2.MODES_CAPABILITIES),
        Read("Favourite modes", QcUltra2.FAVORITES),
        Read("Live sound settings", QcUltra2.AUDIO_SETTINGS),
    )

    fun profile(): LabProfile = LabProfile(STACK, READS.map { "${it.address}: ${it.label}" })

    /** The GET for [read], checked by the write guard. */
    fun packet(read: Read): BmapPacket = BmapPacket.get(read.address).also { WriteGuard.check(it) }

    suspend fun scan(conn: BmapConnection): List<String> = buildList {
        for (read in READS) {
            val head = "${read.address} ${read.label}:"
            if (!conn.isOpen) {
                add("$head not connected")
                continue
            }
            try {
                val reply = conn.requestUnwatched(packet(read), ReplyMode.SINGLE, TIMEOUT_MS).firstOrNull { it.matches(read.address) }
                when {
                    reply == null -> add("$head no reply")
                    reply.isError -> add("$head refused (${reply.error?.name ?: "error ${reply.errorCode}"})")
                    read.sizeOnly -> add("$head ${reply.payload.size} bytes")
                    else -> add("$head ${reply.payload.size} bytes ${reply.payload.toHex()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                add("$head ${e.message}")
            }
        }
    }
}
