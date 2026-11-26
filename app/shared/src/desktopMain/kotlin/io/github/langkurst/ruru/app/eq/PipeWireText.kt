package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.dsp.FilterChainWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A PipeWire version such as 1.0.5. */
data class PipeWireVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PipeWireVersion> {
    override fun compareTo(other: PipeWireVersion): Int =
        compareValuesBy(this, other, PipeWireVersion::major, PipeWireVersion::minor, PipeWireVersion::patch)

    override fun toString() = "$major.$minor.$patch"
}

/** An audio output (a node of media class Audio/Sink) as `pw-dump` lists it. */
data class PipeWireSink(val id: Int, val nodeName: String, val description: String?, val nick: String?)

/**
 * Reads what the PipeWire tools print: `pipewire --version`, `pw-dump` (JSON) and `wpctl inspect`.
 * Pure text in, values out, so it is tested with samples.
 */
object PipeWireText {
    /** The oldest PipeWire whose filter-chain takes `target.object` (0.3.64; older ones only know `node.target`). */
    val MINIMUM = PipeWireVersion(0, 3, 64)

    private val linked = Regex("""Linked with libpipewire\s+(\d+)\.(\d+)\.(\d+)""")
    private val anyVersion = Regex("""(\d+)\.(\d+)\.(\d+)""")
    private val nodeName = Regex("node\\.name\\s*=\\s*\"([^\"]*)\"")

    /** The version `pipewire --version` reports (the library it is linked with), or null when the text has none. */
    fun parseVersion(output: String): PipeWireVersion? {
        val m = linked.find(output) ?: anyVersion.find(output) ?: return null
        val (a, b, c) = m.destructured
        return PipeWireVersion(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null)
    }

    /** Every Audio/Sink node in a `pw-dump` answer. A text that is not the JSON list gives an empty list. */
    fun parseSinks(dump: String): List<PipeWireSink> {
        val root = runCatching { Json.parseToJsonElement(dump) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.string("type")?.endsWith(":Node") != true) return@mapNotNull null
            val props = (obj["info"] as? JsonObject)?.get("props") as? JsonObject ?: return@mapNotNull null
            if (props.string("media.class") != "Audio/Sink") return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val name = props.string("node.name") ?: return@mapNotNull null
            PipeWireSink(id, name, props.string("node.description"), props.string("node.nick"))
        }
    }

    /**
     * The output of the connected headphones: the Audio/Sink whose description (or nick) is the headphones'
     * name, a Bluetooth output before any other. Ruru's own sink is never chosen. Null when none matches.
     */
    fun findHeadphoneSink(sinks: List<PipeWireSink>, headphoneName: String?): PipeWireSink? {
        val wanted = normalise(headphoneName.orEmpty())
        if (wanted.length < 3) return null
        val matches = sinks
            .filter { !it.nodeName.startsWith(FilterChainWriter.SINK_NODE_NAME) }
            .filter { sink ->
                listOfNotNull(sink.description, sink.nick).any { label ->
                    val l = normalise(label)
                    l.length >= 3 && (l.contains(wanted) || wanted.contains(l))
                }
            }
        return matches.firstOrNull { it.nodeName.startsWith("bluez") } ?: matches.firstOrNull()
    }

    /** The sink called [name] (a `node.name`), or null. */
    fun sinkByName(sinks: List<PipeWireSink>, name: String): PipeWireSink? = sinks.firstOrNull { it.nodeName == name }

    /** The `node.name` in `wpctl inspect @DEFAULT_AUDIO_SINK@`, or null. */
    fun parseInspectNodeName(output: String): String? = nodeName.find(output)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
