package io.github.langkurst.ruru.app.update

/**
 * Just enough JSON to read GitHub's release reply, so the updater needs no library. Objects become
 * maps, arrays lists, numbers Doubles; anything malformed throws [IllegalArgumentException].
 */
internal object MiniJson {
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
