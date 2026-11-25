package io.github.langkurst.ruru.app.settings

import io.github.langkurst.ruru.app.PrefsRoot
import kotlinx.coroutines.flow.SharedFlow
import java.util.prefs.Preferences

actual fun createSettingsStore(): SettingsStore = DesktopSettingsStore(PrefsRoot.node("settings"))

/**
 * java.util.prefs. With no node (the warm-up run) it reads defaults and writes nothing. A text over the prefs
 * limit (8 KB: a long list, or many saved equaliser presets) is split over several entries and joined again on reading.
 */
class DesktopSettingsStore(private val prefs: Preferences?) : SettingsStore {
    override val changes: SharedFlow<String> get() = SettingsChanges.flow

    private fun <T> read(default: T, block: (Preferences) -> T): T = runCatching { prefs?.let(block) }.getOrNull() ?: default

    private fun write(key: String, block: (Preferences) -> Unit) {
        val p = prefs ?: return
        if (runCatching { block(p); p.flush() }.isSuccess) SettingsChanges.flow.tryEmit(key)
    }

    override fun getString(key: String, default: String?) = read(default) { joined(it, key) } ?: default
    override fun getInt(key: String, default: Int) = read(default) { it.getInt(key, default) }
    override fun getBoolean(key: String, default: Boolean) = read(default) { it.getBoolean(key, default) }
    override fun getFloat(key: String, default: Float) = read(default) { it.getFloat(key, default) }
    override fun getStringList(key: String) = SettingsJson.decodeList(getString(key))

    override fun putString(key: String, value: String) = write(key) { putText(it, key, value) }
    override fun putInt(key: String, value: Int) = write(key) { it.putInt(key, value) }
    override fun putBoolean(key: String, value: Boolean) = write(key) { it.putBoolean(key, value) }
    override fun putFloat(key: String, value: Float) = write(key) { it.putFloat(key, value) }
    override fun putStringList(key: String, value: List<String>) = putString(key, SettingsJson.encodeList(value))

    override fun contains(key: String) = read(false) { it.get(key, null) != null }
    override fun remove(key: String) = write(key) { dropParts(it, key); it.remove(key) }

    /** The text under [key], with the pieces of a long one put back together. */
    private fun joined(p: Preferences, key: String): String? {
        val head = p.get(key, null) ?: return null
        if (!head.startsWith(PARTS)) return head
        val count = head.removePrefix(PARTS).toIntOrNull() ?: return null
        val sb = StringBuilder()
        for (i in 0 until count) sb.append(p.get(partKey(key, i), null) ?: return null)
        return sb.toString()
    }

    private fun putText(p: Preferences, key: String, value: String) {
        dropParts(p, key)
        if (value.length <= CHUNK) {
            p.put(key, value)
            return
        }
        val pieces = value.chunked(CHUNK)
        pieces.forEachIndexed { i, piece -> p.put(partKey(key, i), piece) }
        p.put(key, PARTS + pieces.size)
    }

    private fun dropParts(p: Preferences, key: String) {
        val head = p.get(key, null) ?: return
        if (!head.startsWith(PARTS)) return
        val count = head.removePrefix(PARTS).toIntOrNull() ?: return
        for (i in 0 until count) p.remove(partKey(key, i))
    }

    private fun partKey(key: String, i: Int) = "$key.part$i"

    private companion object {
        const val CHUNK = 8000
        const val PARTS = "ruru-parts:"
    }
}
