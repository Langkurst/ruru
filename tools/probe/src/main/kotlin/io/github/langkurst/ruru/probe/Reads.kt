package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.link.WriteGuard
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.FunctionAddress
import io.github.langkurst.ruru.protocol.bose.AudioControl
import io.github.langkurst.ruru.protocol.bose.AudioSourceQuery
import io.github.langkurst.ruru.protocol.bose.AudioSourceType
import io.github.langkurst.ruru.protocol.bose.AudioStatusQuery
import io.github.langkurst.ruru.protocol.bose.DeviceInfoQuery
import io.github.langkurst.ruru.protocol.bose.Latency
import io.github.langkurst.ruru.protocol.bose.Mac
import io.github.langkurst.ruru.protocol.bose.MicMute
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.protocol.bose.TransportControl
import io.github.langkurst.ruru.protocol.bose.Volume
import io.github.langkurst.ruru.protocol.toHex

/** One read-only request the probe makes, with how its reply is shown. */
class ProbeRead(
    val label: String,
    val address: FunctionAddress,
    val payload: ByteArray = BmapPacket.EMPTY,
    /** The raw reply as shown; addresses are masked here so nothing identifying is printed. */
    val raw: (ByteArray) -> String = { it.toHex() },
    val decode: (ByteArray) -> Any?,
)

/**
 * Every frame the new reads send goes through [get]: GET only, and checked by the same
 * [WriteGuard] the app uses.
 */
object ProbeSafety {
    fun get(address: FunctionAddress, payload: ByteArray = BmapPacket.EMPTY): BmapPacket {
        val packet = BmapPacket(address.block, address.function, BmapOperator.GET, payload)
        WriteGuard.check(packet)
        return packet
    }

    /** Hex with the six address bytes at [from] replaced by `xx`, for replies that carry a Bluetooth address. */
    fun maskAddress(bytes: ByteArray, from: Int): String =
        bytes.mapIndexed { i, b -> if (i in from until from + 6) "xx" else "%02x".format(b.toInt() and 0xff) }.joinToString("")
}

/** The playback, volume and mic reads added in 0.3.0. [5.6] NowPlaying is START-only, so it is not read here. */
object PlaybackReads {
    private fun names(codes: Set<Int>) = codes.sorted().joinToString(", ") { TransportControl.fromCode(it)?.name ?: "code $it" }

    val all: List<ProbeRead> = listOf(
        ProbeRead("Audio source [5.1]", QcUltra2.AUDIO_SOURCE, raw = { r ->
            if (r.size >= 9 && r[2].toInt() == AudioSourceType.BLUETOOTH.code) ProbeSafety.maskAddress(r, 3) else r.toHex()
        }) { p ->
            AudioSourceQuery.parse(p)?.let { s ->
                val offered = s.supportedTypeCodes.sorted().joinToString { AudioSourceType.fromCode(it)?.name ?: "code $it" }
                "active=${s.type ?: "code ${s.typeCode}"} offered=$offered"
            }
        },
        ProbeRead("Transport controls [5.3]", QcUltra2.AUDIO_CONTROL) { names(AudioControl.parseSupported(it)) },
        ProbeRead("Playback status [5.4]", QcUltra2.AUDIO_STATUS) { p ->
            AudioStatusQuery.parse(p)?.let { "${it.status ?: "code ${it.statusCode}"} position=${it.trackPosition ?: "unknown"}" }
        },
        ProbeRead("Volume [5.5]", QcUltra2.VOLUME) { p -> Volume.parse(p)?.let { "${it.current} of ${it.max}" } },
        ProbeRead("Latency [5.7]", QcUltra2.LATENCY) { p -> Latency.parse(p)?.let { "${it.current} (range ${it.min}..${it.max})" } },
        ProbeRead("Mic mute [2.21]", QcUltra2.MIC_MUTE) { MicMute.parse(it) },
    )

    /** [4.5] names one paired device; the request carries its address, which the probe never prints. */
    fun deviceInfoRequest(mac: String): BmapPacket = ProbeSafety.get(QcUltra2.DEVICE_INFO, Mac.toBytes(mac))

    /** Connected flag and name only. */
    fun describeInfo(payload: ByteArray): String =
        DeviceInfoQuery.parse(payload)?.let { "connected=${it.isConnected} name='${it.name ?: "(none)"}'" } ?: "unparsed"
}
