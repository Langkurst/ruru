package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.notYet
import kotlinx.coroutines.CoroutineScope

/**
 * Anker Soundcore on its vendor RFCOMM service, `0cf12d31-fac3-4553-bd80-d6832e7` + five hex digits
 * that follow the model code (Earshot's SoundcoreModel and SoundcoreLinkFactory, MIT; research from
 * soundcorebridge). Opened by UUID only: the same headsets carry a firmware-update service on
 * another channel, and JBL publishes a service on the same base that is not Soundcore at all.
 * Session: milestone C3 (which may replace this table with the full model list).
 */
object SoundcoreVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.SOUNDCORE

    const val SERVICE_UUID_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7"

    /** JBL publishes the same base for its own protocol: such a name is never opened as a Soundcore. */
    private val OTHER_BRAND = Regex("""\bjbl\b|harman""", RegexOption.IGNORE_CASE)

    /** A model Earshot drives: its product [code] and its Bluetooth name rule. */
    private class Model(val code: String, val name: Regex, val extraSuffixes: List<String> = emptyList()) {
        /** The code read as hex with bit 16 set (`A3947` -> `b3947`; Gadgetbridge's rule, confirmed by captures). */
        val suffixes: List<String> get() = listOf((code.take(5).toInt(16) or 0x10000).toString(16).padStart(5, '0')) + extraSuffixes
    }

    private fun named(model: String) = Regex("""soundcore[\s_-]*$model""", RegexOption.IGNORE_CASE)

    /** Earshot's table, in its order (the Sleep A30 is the one Earshot tested on a real pair). */
    private val MODELS = listOf(
        Model("D1301", Regex("""sleep\s*a30""", RegexOption.IGNORE_CASE)),
        Model("A6611", named("""sleep\s*a20""")),
        Model("A3035", named("""space\s*one(?![\s_-]*pro)""")),
        Model("A3062", named("""space\s*one[\s_-]*pro""")),
        Model("A3040", named("""space\s*q\s*45""")),
        Model("D1402", named("""space\s*2\b""")),
        Model("A3947", named("""liberty\s*4[\s_-]*nc""")),
        Model("A3957", named("""liberty\s*5(?![\s_-]*pro)""")),
        Model("A3955", named("""p40i""")),
        Model("A3959", named("""(?:r50i[\s_-]*nc|p30i)"""), extraSuffixes = listOf("b395b")),
        Model("A3958", named("""a30i""")),
        Model("D1202", named("""p31i""")),
        Model("D1202C", named("""r60i"""), extraSuffixes = listOf("1202c")),
        Model("A3936", named("""space\s*a40""")),
        Model("A3952", named("""liberty\s*3[\s_-]*pro""")),
        Model("A3953", named("""liberty\s*4(?![\s_-]*(?:nc|pro))""")),
        Model("A3954", named("""liberty\s*4[\s_-]*pro""")),
        Model("A3968", named("""sport\s*x20""")),
        Model("A3933", named("""life\s*note\s*3(?![\s_-]*s\b)""")),
        Model("A3945", named("""life\s*note\s*3[\s_-]*s\b""")),
        Model("A3939", named("""life\s*p3""")),
        Model("A3944", named("""life\s*p2""")),
        Model("A3948", named("""a20i""")),
        Model("A3949", named("""(?:p20i|p25i|r50i(?![\s_-]*nc))""")),
        Model("A3330", named("""c30i""")),
        Model("A3388", named("""aeroclip""")),
        Model("A3876", named("""v20i""")),
        Model("A3874", named("""aerofit\s*2(?![\s_-]*pro)""")),
        Model("D1101", named("""c50i""")),
        Model("D1203", named("""liberty\s*5[\s_-]*pro(?![\s_-]*max)""")),
        Model("D1204", named("""liberty\s*5[\s_-]*pro[\s_-]*max""")),
        Model("D1206", named("""liberty\s*buds\s*2""")),
        Model("D1205", named("""p42i""")),
        Model("A3875", named("""aerofit\s*2[\s_-]*pro""")),
        Model("A3878", named("""v40i""")),
    )

    override fun detect(name: String?): Boolean =
        name != null && !OTHER_BRAND.containsMatchIn(name) && MODELS.any { it.name.containsMatchIn(name) }

    /**
     * The first cached service UUID a driven model publishes, lower case, unless [name] is another
     * brand's; null when there is none (Ruru then opens nothing).
     */
    fun serviceFor(uuids: List<String>, name: String?): String? {
        if (name != null && OTHER_BRAND.containsMatchIn(name)) return null
        return uuids.map { it.lowercase() }.firstOrNull { u ->
            u.startsWith(SERVICE_UUID_PREFIX) && u.length == SERVICE_UUID_PREFIX.length + 5 &&
                MODELS.any { u.substring(SERVICE_UUID_PREFIX.length) in it.suffixes }
        }
    }

    /** Only by a UUID the system cached: the suffix names the model, and there is no channel fallback. */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? {
        if (RouteKind.RFCOMM_SERVICE !in available) return null
        return serviceFor(candidate.uuids, candidate.name)?.let { LinkRoute.RfcommService(it) }
    }

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
