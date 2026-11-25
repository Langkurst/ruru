package io.github.langkurst.ruru.link

/**
 * A sentence for the person, kept as the name of its string resource ([key], as in the app's
 * strings.xml) and the values to put into it ([args]), so the screen can show it in the person's
 * language. Platforms and the controller return this instead of English text; the app resolves it
 * where it is shown.
 */
class Words(val key: String, vararg args: Any) {
    val args: List<Any> = args.toList()

    override fun equals(other: Any?): Boolean = other is Words && other.key == key && other.args == args
    override fun hashCode(): Int = 31 * key.hashCode() + args.hashCode()

    /** For logs and tests, in the form `key(arg, arg)`. */
    override fun toString(): String = if (args.isEmpty()) key else "$key(${args.joinToString()})"
}
