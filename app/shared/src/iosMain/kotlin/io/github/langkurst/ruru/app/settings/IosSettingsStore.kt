package io.github.langkurst.ruru.app.settings

import kotlinx.coroutines.flow.SharedFlow
import platform.Foundation.NSUserDefaults

actual fun createSettingsStore(): SettingsStore = IosSettingsStore(NSUserDefaults.standardUserDefaults)

class IosSettingsStore(private val defaults: NSUserDefaults) : SettingsStore {
    override val changes: SharedFlow<String> get() = SettingsChanges.flow

    private fun has(key: String) = defaults.objectForKey(key) != null

    private fun done(key: String) {
        SettingsChanges.flow.tryEmit(key)
    }

    override fun getString(key: String, default: String?): String? = defaults.stringForKey(key) ?: default
    override fun getInt(key: String, default: Int) = if (has(key)) defaults.integerForKey(key).toInt() else default
    override fun getBoolean(key: String, default: Boolean) = if (has(key)) defaults.boolForKey(key) else default
    override fun getFloat(key: String, default: Float) = if (has(key)) defaults.floatForKey(key) else default
    override fun getStringList(key: String) = SettingsJson.decodeList(getString(key))

    override fun putString(key: String, value: String) {
        defaults.setObject(value, key)
        done(key)
    }

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), key)
        done(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, key)
        done(key)
    }

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, key)
        done(key)
    }

    override fun putStringList(key: String, value: List<String>) = putString(key, SettingsJson.encodeList(value))

    override fun contains(key: String) = has(key)

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
        done(key)
    }
}
