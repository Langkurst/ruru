package io.github.langkurst.ruru.link

import kotlinx.io.IOException

/** The kinds of [LinkRoute], for [HeadsetPlatform.routes]. */
enum class RouteKind { BOSE_RFCOMM, RFCOMM_SERVICE, GATT, BOSE_LE }

/**
 * Where a headset's control link lives, as its maker's vendor code decides (`:core:vendors`). The
 * platform turns one into a [ByteTransport] in [HeadsetPlatform.open].
 */
sealed class LinkRoute {
    abstract val kind: RouteKind

    /**
     * Bose BMAP over classic RFCOMM, as Ruru 0.1-0.2 opened it: the Serial Port service where the
     * system finds it by SDP, otherwise these [channels] in order, each checked with the harmless
     * `[0.1]` read where the platform probes. Bose headsets only.
     */
    data class BoseRfcomm(val channels: List<Int>) : LinkRoute() {
        override val kind: RouteKind get() = RouteKind.BOSE_RFCOMM
    }

    /**
     * One RFCOMM service found by its SDP [uuid] (lower case), never by a channel number: the same
     * headsets carry firmware-update and accessory services on other channels that must never see
     * a stray byte. The platform reports "service not found" before writing anything.
     */
    data class RfcommService(val uuid: String) : LinkRoute() {
        init {
            require(UUID_TEXT.matches(uuid)) { "not a lower-case UUID: $uuid" }
        }

        override val kind: RouteKind get() = RouteKind.RFCOMM_SERVICE
    }

    /** A vendor's byte pipe over Bluetooth LE GATT ([GattLinkSpec]). */
    data class Gatt(val spec: GattLinkSpec) : LinkRoute() {
        override val kind: RouteKind get() = RouteKind.GATT
    }

    /** Bose BMAP over Bluetooth LE (service FEBE, one header byte per write: [LeTransport]); the iPhone's route. */
    data object BoseLe : LinkRoute() {
        override val kind: RouteKind get() = RouteKind.BOSE_LE
    }

    companion object {
        internal val UUID_TEXT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    }
}

/**
 * Where a vendor's byte pipe lives on a headset's GATT server: one [service], the characteristic
 * Ruru writes to and the ones whose notifications carry the headset's bytes back (from Earshot's
 * GattLinkSpec). UUIDs are lower-case text so the spec is common code. A spec never names a
 * firmware-update service: [isFirmwareService] runs in `init`, so such a spec cannot be built.
 */
data class GattLinkSpec(
    /** For logs and the connection report. */
    val name: String,
    val service: String,
    /** The characteristic Ruru writes its frames to. */
    val write: String,
    /** Characteristics whose notifications (or indications) are the incoming stream, enabled in this order. */
    val notify: List<String>,
    /** Write with response when true, as JBL's app does; without response otherwise. */
    val writeWithResponse: Boolean = true,
    /** The ATT MTU to ask for. The headset may grant less; chunks follow what it grants. */
    val mtu: Int = DEFAULT_MTU,
) {
    init {
        require(notify.isNotEmpty()) { "a GATT link needs at least one notifying characteristic" }
        require(mtu in MIN_MTU..MAX_MTU) { "ATT MTU $mtu is outside $MIN_MTU..$MAX_MTU" }
        for (uuid in listOf(service, write) + notify) {
            require(LinkRoute.UUID_TEXT.matches(uuid)) { "not a lower-case UUID: $uuid" }
        }
        require(!isFirmwareService(service)) { "$service is a firmware-update service" }
    }

    companion object {
        const val MIN_MTU = 23
        const val MAX_MTU = 517
        const val DEFAULT_MTU = 512

        /**
         * GATT services that update firmware, by prefix (Earshot's list from the JBL notes §4): BES
         * OTA and TOTA, the "normal" and BES SDK OTA services and JBL's OTA variants. Recognising
         * one is fine; opening one is never.
         */
        private val FIRMWARE_PREFIXES = listOf(
            "66666666-",
            "86868686-",
            "01000100-0000-1000-8000-",
            "03000300-0000-1000-8000-",
            "05000500-0000-1000-8000-",
            "06000600-0000-1000-8000-",
            "07000700-0000-1000-8000-",
            "65786365-6c70-6f69-6e74-2e636f82",
        )

        fun isFirmwareService(uuid: String): Boolean = uuid.lowercase().let { s -> FIRMWARE_PREFIXES.any { s.startsWith(it) } }
    }
}

/** The platform cannot open [route] (its kind is not in [HeadsetPlatform.routes]). Thrown before any byte is sent. */
class RouteNotOfferedException(platform: String, route: LinkRoute) :
    IOException("$platform cannot open a ${route.kind} route")
