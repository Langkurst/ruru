package io.github.langkurst.ruru.app.settings

import kotlinx.coroutines.flow.SharedFlow

/** Settings held in memory only: for demo mode, snapshots and tests. Nothing reaches the person's real settings. */
class MemorySettingsStore : SettingsStore {
    private val values = HashMap<String, Any>()

    override val changes: SharedFlow<String> get() = SettingsChanges.flow

    override fun getString(key: String, default: String?) = values[key] as? String ?: default
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default

    @Suppress("UNCHECKED_CAST")
    override fun getStringList(key: String) = values[key] as? List<String> ?: emptyList()

    override fun putString(key: String, value: String) { values[key] = value }
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun putStringList(key: String, value: List<String>) { values[key] = value }

    override fun contains(key: String) = key in values
    override fun remove(key: String) { values.remove(key) }
}
