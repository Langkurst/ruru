package io.github.langkurst.ruru.vendors.bragi

/**
 * A few named text values kept between runs: the app's settings store, in the apps. The Skullcandy
 * trust store ([StoredBragiTrustStore]) keeps its keys here, so they live with the person's other
 * settings and never in the headphones' address.
 */
interface BragiKeyStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Keys in memory, for tests. */
class MapBragiKeyStorage : BragiKeyStorage {
    val values = LinkedHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
