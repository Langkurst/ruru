package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.vendors.sony.SonyVendor
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreVendor

/**
 * Which maker's stack drives a pair of headphones (Earshot's DeviceFamily, MIT; see CREDITS.md).
 * Each family but [GENERIC] has one [Vendor] in [VendorRegistry].
 */
enum class DeviceFamily {
    /** Bose BMAP: QC Ultra, QC Headphones, QC45 and the other Bose models. */
    BOSE,

    /** Bragi BOS-RPC: the Skullcandy Crusher 1080 ANC and Method 360 / 540 ANC ("Sound by Bose"). */
    BRAGI,

    /** Anker Soundcore over its RFCOMM vendor service, by UUID. */
    SOUNDCORE,

    /** Sony MDR v2 over its RFCOMM service by UUID; MDR v1 (WH-1000XM4 and older) stays [GENERIC]. */
    SONY,

    /** Sennheiser's GAIA v3 over its RFCOMM service by UUID. BLE-only and GAIA v1/v2 models stay [GENERIC]. */
    SENNHEISER,

    /** JBL's Harman "AA" protocol over the Harman GATT service: the 2021-24 models, by exact name. */
    JBL,

    /** AirPods and Beats: battery from their Bluetooth LE adverts only. Nothing is ever sent to them. */
    APPLE_ADVERT,

    /**
     * Any other headset. Ruru doesn't speak its control protocol, so it is never opened and never
     * sent a byte: other stacks have been seen to power off on a stray frame (Earshot's research).
     */
    GENERIC,
    ;

    companion object {
        /**
         * By Bluetooth name alone, in Earshot's order: Bragi, Soundcore, Sennheiser (before Bose: a
         * Sennheiser must never get the BMAP channel walk), Sony, JBL, Bose (the QCY rule: "QC" must
         * be followed by a model), then AirPods and Beats. An unknown name is [GENERIC].
         */
        fun forName(name: String?): DeviceFamily =
            VendorRegistry.vendors.firstOrNull { it.detect(name) }?.family ?: GENERIC

        fun fromKey(key: String?): DeviceFamily? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

        /**
         * The one rule everything uses, strongest first (Earshot's order):
         *  1. the person's "Headphone type" [override] for this headset;
         *  2. the family that already answered on this headset ([learned]): a Bose renamed to
         *     "Alex's headphones" stays Bose;
         *  3. the Bluetooth [name];
         *  4. the service [uuids] the system cached: a Soundcore vendor service that a driven model
         *     publishes makes an unknown name a Soundcore, unless the name is another brand's.
         * Then one refusal: a Sony whose cached record shows only the MDR v1 service is [GENERIC].
         */
        fun resolve(
            override: String?,
            learned: DeviceFamily?,
            name: String?,
            uuids: List<String> = emptyList(),
        ): DeviceFamily {
            val family = fromKey(override) ?: learned ?: forName(name).let { byName ->
                if (byName == GENERIC && SoundcoreVendor.serviceFor(uuids, name) != null) SOUNDCORE else byName
            }
            return if (family == SONY && SonyVendor.onlyV1(uuids)) GENERIC else family
        }
    }
}
