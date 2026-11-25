package io.github.langkurst.ruru.dsp

import kotlin.math.exp

/**
 * Writes the PipeWire config for `pipewire -c <file>`: a `libpipewire-module-filter-chain`
 * that shows up as the sink "Ruru EQ" and plays into the headphones' sink.
 *
 * The chain is a gain node for the preamp (`linear`, Mult = 10^(dB/20)) followed by one
 * `bq_peaking` / `bq_lowshelf` / `bq_highshelf` node per band, each with Freq, Q and Gain.
 * Left and right get their own copy of the chain from `audio.position = [ FL FR ]`.
 * [write]'s `targetNodeName` is the headphones' sink `node.name`; without it PipeWire picks the default output.
 */
object FilterChainWriter {
    const val SINK_NODE_NAME = "ruru_eq"
    const val SINK_DESCRIPTION = "Ruru EQ"
    const val HEADER = "# Written by Ruru. Changes are overwritten."

    private const val LN10 = 2.302585092994046

    fun write(state: ProEqState, targetNodeName: String? = null): String {
        val active = if (state.enabled) state.sanitized() else ProEqState()
        val bands = active.outputBands().filter { it.gainDb != 0.0 }
        val mult = exp(active.desktopPreampDb() * LN10 / 20.0)

        val nodes = ArrayList<String>()
        nodes += "{ type = builtin name = eq_pre label = linear control = { \"Mult\" = ${mult.toFixed(6)} \"Add\" = 0.0 } }"
        val links = ArrayList<String>()
        var previous = "eq_pre"
        bands.forEachIndexed { i, b ->
            val name = "eq_band_${i + 1}"
            nodes += "{ type = builtin name = $name label = ${label(b.type)} control = " +
                "{ \"Freq\" = ${b.frequencyHz.toFixed(1)} \"Q\" = ${b.q.toFixed(2)} \"Gain\" = ${b.gainDb.toFixed(1)} } }"
            links += "{ output = \"$previous:Out\" input = \"$name:In\" }"
            previous = name
        }

        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("context.modules = [\n")
        sb.append("    { name = libpipewire-module-filter-chain\n")
        sb.append("        args = {\n")
        sb.append("            node.description = \"$SINK_DESCRIPTION\"\n")
        sb.append("            media.name = \"$SINK_DESCRIPTION\"\n")
        sb.append("            filter.graph = {\n")
        sb.append("                nodes = [\n")
        nodes.forEach { sb.append("                    ").append(it).append('\n') }
        sb.append("                ]\n")
        if (links.isNotEmpty()) {
            sb.append("                links = [\n")
            links.forEach { sb.append("                    ").append(it).append('\n') }
            sb.append("                ]\n")
        }
        sb.append("            }\n")
        sb.append("            audio.channels = 2\n")
        sb.append("            audio.position = [ FL FR ]\n")
        sb.append("            capture.props = {\n")
        sb.append("                node.name = \"$SINK_NODE_NAME\"\n")
        sb.append("                media.class = Audio/Sink\n")
        sb.append("            }\n")
        sb.append("            playback.props = {\n")
        sb.append("                node.name = \"${SINK_NODE_NAME}_output\"\n")
        sb.append("                node.passive = true\n")
        targetNodeName?.let { quote(it) }
            ?.let { sb.append("                target.object = ").append(it).append('\n') }
        sb.append("            }\n")
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("]\n")
        return sb.toString()
    }

    private fun label(t: FilterType) = when (t) {
        FilterType.PEAK -> "bq_peaking"
        FilterType.LOW_SHELF -> "bq_lowshelf"
        FilterType.HIGH_SHELF -> "bq_highshelf"
    }

    /** A double-quoted config string; control characters are dropped, quotes and backslashes escaped. Null when empty. */
    private fun quote(s: String): String? {
        val clean = s.filter { it >= ' ' }.trim()
        if (clean.isEmpty()) return null
        return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
