package io.github.langkurst.ruru.vendors.apple

/**
 * Adverts as hex, the full 27 bytes a real proximity-pairing advert has (company id stripped):
 * `07 19 01` + model + status + buds + charge|case + 19 bytes Ruru never reads (lid counter, colour,
 * the encrypted tail). They are built from the documented layout, NOT captured: nothing in this
 * repository was heard from a real pair (Earshot's own tests were synthetic too, and the owner's
 * AirPods are never scanned in tests). Swap in a real capture, address-free, when a tester sends one.
 */
object AppleFixtures {
    private const val TAIL = "00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 01 02 03"

    /** AirPods Pro 2: status 0x2b (low nibble = left), left 80 %, right 90 %, right charging, case 50 %. */
    val PRO2_NOT_FLIPPED = hex("07 19 01 14 20 2b 98 25 $TAIL")

    /** AirPods Pro: status 0x03 (flipped, high nibble = left), left 30 %, right 100 %, left + case charging, case 80 %. */
    val PRO_FLIPPED = hex("07 19 01 0e 20 03 3a 68 $TAIL")

    /** AirPods 2: right bud out of range (15), lid shut (case 15). */
    val AIRPODS2_ONE_BUD = hex("07 19 01 0f 20 20 f7 0f $TAIL")

    /** AirPods Max: one battery, 60 %. */
    val MAX = hex("07 19 01 0a 20 20 f6 0f $TAIL")

    /** Powerbeats Pro, both buds 100 %, case 40 %, nothing charging. */
    val POWERBEATS_PRO = hex("07 19 01 0b 20 20 aa 04 $TAIL")

    /** Apple's "nearby info" message (type 0x10) and an iBeacon (type 0x02): not battery adverts. */
    val NEARBY_INFO = hex("10 05 01 18 44 00 00 00")
    val IBEACON = hex("02 15 00 00 00 00 00 00")

    fun hex(text: String): ByteArray = text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
