package io.github.langkurst.ruru.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.SharedFlow

/** Holds the application context the settings need. RuruApplication calls [init] before anything reads a setting. */
object AndroidSettings {
    @Volatile
    internal var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual fun createSettingsStore(): SettingsStore =
    AndroidSettingsStore(AndroidSettings.context?.getSharedPreferences("ruru_settings", Context.MODE_PRIVATE))

class AndroidSettingsStore(private val prefs: SharedPreferences?) : SettingsStore {
    override val changes: SharedFlow<String> get() = SettingsChanges.flow

    private fun <T> read(default: T, block: (SharedPreferences) -> T): T = runCatching { prefs?.let(block) }.getOrNull() ?: default

    private fun write(key: String, block: (SharedPreferences.Editor) -> Unit) {
        val p = prefs ?: return
        p.edit().also(block).apply()
        SettingsChanges.flow.tryEmit(key)
    }

    override fun getString(key: String, default: String?) = read(default) { it.getString(key, default) } ?: default
    override fun getInt(key: String, default: Int) = read(default) { it.getInt(key, default) }
    override fun getBoolean(key: String, default: Boolean) = read(default) { it.getBoolean(key, default) }
    override fun getFloat(key: String, default: Float) = read(default) { it.getFloat(key, default) }
    override fun getStringList(key: String) = SettingsJson.decodeList(getString(key))

    override fun putString(key: String, value: String) = write(key) { it.putString(key, value) }
    override fun putInt(key: String, value: Int) = write(key) { it.putInt(key, value) }
    override fun putBoolean(key: String, value: Boolean) = write(key) { it.putBoolean(key, value) }
    override fun putFloat(key: String, value: Float) = write(key) { it.putFloat(key, value) }
    override fun putStringList(key: String, value: List<String>) = putString(key, SettingsJson.encodeList(value))

    override fun contains(key: String) = read(false) { it.contains(key) }
    override fun remove(key: String) = write(key) { it.remove(key) }
}
