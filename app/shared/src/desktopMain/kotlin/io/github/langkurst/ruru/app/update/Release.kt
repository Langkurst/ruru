package io.github.langkurst.ruru.app.update

/** One file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String)

/** The newest release: [version] is the tag without its leading "v". */
data class Release(val version: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }

    companion object {
        /** Reads the reply of GitHub's "latest release" call. Throws [IllegalArgumentException] if it isn't one. */
        fun parse(json: String): Release {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Not a release")
            val tag = root["tag_name"] as? String ?: throw IllegalArgumentException("The release has no tag")
            val version = tag.removePrefix("v").removePrefix("V")
            require(Versions.parse(version) != null) { "The release tag $tag is not a version" }
            val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { item ->
                val a = item as? Map<*, *> ?: return@mapNotNull null
                val name = a["name"] as? String ?: return@mapNotNull null
                val url = a["browser_download_url"] as? String ?: return@mapNotNull null
                // Only GitHub's own download address is ever fetched.
                if (!url.startsWith("https://github.com/")) return@mapNotNull null
                ReleaseAsset(name, url)
            }
            return Release(version, assets)
        }
    }
}

/** Semantic-version order: 0.10.0 is newer than 0.9.9, and 1.0.0 is newer than 1.0.0-rc1. */
object Versions {
    private class Parsed(val numbers: List<Int>, val pre: String?)

    private fun parse0(text: String): Parsed? {
        val trimmed = text.trim().removePrefix("v").removePrefix("V")
        val core = trimmed.substringBefore('-').substringBefore('+')
        val pre = trimmed.substringAfter('-', "").substringBefore('+').ifEmpty { null }
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.isEmpty()) return null
        return Parsed(numbers, pre)
    }

    /** The version's numbers, or null when [text] isn't a version. */
    fun parse(text: String): List<Int>? = parse0(text)?.numbers

    /** Negative when [a] is older than [b], zero when they match. Text that isn't a version counts as oldest. */
    fun compare(a: String, b: String): Int {
        val x = parse0(a)
        val y = parse0(b)
        if (x == null || y == null) return if (x == null && y == null) 0 else if (x == null) -1 else 1
        for (i in 0 until maxOf(x.numbers.size, y.numbers.size)) {
            val c = x.numbers.getOrElse(i) { 0 }.compareTo(y.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            x.pre == y.pre -> 0
            x.pre == null -> 1 // a release is newer than its own pre-release
            y.pre == null -> -1
            else -> x.pre.compareTo(y.pre)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}

/** Reads the release's SHA256SUMS.txt, as written by `sha256sum`: "<64 hex>  <file name>" per line. */
object Checksums {
    private val line = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""")

    /** The lower-case SHA-256 listed for [fileName], or null when the list has no such line. */
    fun find(sums: String, fileName: String): String? =
        sums.lineSequence()
            .mapNotNull { line.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[2].removePrefix("./") == fileName }
            ?.groupValues?.get(1)?.lowercase()
}
