package io.github.langkurst.ruru.app.settings

import io.github.langkurst.ruru.app.PrefsRoot
import kotlinx.coroutines.flow.SharedFlow
import java.util.prefs.Preferences

actual fun createSettingsStore(): SettingsStore = DesktopSettingsStore(PrefsRoot.node("settings"))

/**
 * java.util.prefs. With no node (the warm-up run) it reads defaults and writes nothing. A value over
 * the prefs limit (8 KB, so a very long list) is not written.
 */
class DesktopSettingsStore(private val prefs: Preferences?) : SettingsStore {
    override val changes: SharedFlow<String> get() = SettingsChanges.flow

    private fun <T> read(default: T, block: (Preferences) -> T): T = runCatching { prefs?.let(block) }.getOrNull() ?: default

    private fun write(key: String, block: (Preferences) -> Unit) {
        val p = prefs ?: return
        if (runCatching { block(p); p.flush() }.isSuccess) SettingsChanges.flow.tryEmit(key)
    }

    override fun getString(key: String, default: String?) = read(default) { it.get(key, null) } ?: default
    override fun getInt(key: String, default: Int) = read(default) { it.getInt(key, default) }
    override fun getBoolean(key: String, default: Boolean) = read(default) { it.getBoolean(key, default) }
    override fun getFloat(key: String, default: Float) = read(default) { it.getFloat(key, default) }
    override fun getStringList(key: String) = SettingsJson.decodeList(getString(key))

    override fun putString(key: String, value: String) = write(key) { it.put(key, value) }
    override fun putInt(key: String, value: Int) = write(key) { it.putInt(key, value) }
    override fun putBoolean(key: String, value: Boolean) = write(key) { it.putBoolean(key, value) }
    override fun putFloat(key: String, value: Float) = write(key) { it.putFloat(key, value) }
    override fun putStringList(key: String, value: List<String>) = putString(key, SettingsJson.encodeList(value))

    override fun contains(key: String) = read(false) { it.get(key, null) != null }
    override fun remove(key: String) = write(key) { it.remove(key) }
}
