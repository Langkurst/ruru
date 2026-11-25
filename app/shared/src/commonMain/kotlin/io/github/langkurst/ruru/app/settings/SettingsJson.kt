package io.github.langkurst.ruru.app.settings

/**
 * Just enough JSON to read GitHub's release reply, so the updater needs no library. Objects become
 * maps, arrays lists, numbers Doubles; anything malformed throws [IllegalArgumentException].
 */
internal object SettingsJson {
    /** Writes maps, lists, strings, numbers, booleans and null as indented JSON, map keys in order. */
    fun write(value: Any?): String = StringBuilder().also { put(it, value, 0) }.toString()

    private fun put(sb: StringBuilder, v: Any?, level: Int) {
        val pad = "  ".repeat(level + 1)
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> sb.append(v.toString())
            is Double -> sb.append(if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                v.entries.forEachIndexed { i, (k, x) ->
                    sb.append(pad)
                    quote(sb, k.toString())
                    sb.append(": ")
                    put(sb, x, level + 1)
                    sb.append(if (i < v.size - 1) ",\n" else "\n")
                }
                sb.append("  ".repeat(level)).append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                v.forEachIndexed { i, x ->
                    sb.append(pad)
                    put(sb, x, level + 1)
                    sb.append(if (i < v.size - 1) ",\n" else "\n")
                }
                sb.append("  ".repeat(level)).append(']')
            }
            else -> throw IllegalArgumentException("Cannot write $v")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    /** A string list kept in one stored string. */
    fun encodeList(list: List<String>): String = write(list)

    fun decodeList(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val v = runCatching { parse(text) }.getOrNull() as? List<*> ?: return emptyList()
        return if (v.all { it is String }) v.map { it as String } else emptyList()
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        require(p.atEnd()) { "Extra text after the JSON value" }
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int = 0): Any? {
            require(depth < 64) { "JSON nested too deeply" }
            skipSpace()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val ch = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (ch == '-' || ch.isDigit()) num() else throw IllegalArgumentException("Unexpected '$ch' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Unexpected text at $i" }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number at $start")
        }

        private fun str(): String {
            i++ // the opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val ch = s[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "Unterminated string" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad \\u escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value(depth + 1))
                skipSpace()
                require(i < s.length) { "Unterminated array" }
                when (s[i++]) {
                    ',' -> Unit
                    ']' -> return out
                    else -> throw IllegalArgumentException("Expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                require(i < s.length && s[i] == '"') { "Expected a key at $i" }
                val key = str()
                skipSpace()
                require(i < s.length && s[i++] == ':') { "Expected : after $key" }
                out[key] = value(depth + 1)
                skipSpace()
                require(i < s.length) { "Unterminated object" }
                when (s[i++]) {
                    ',' -> Unit
                    '}' -> return out
                    else -> throw IllegalArgumentException("Expected , or } at ${i - 1}")
                }
            }
        }
    }
}
