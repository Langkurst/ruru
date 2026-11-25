package io.github.langkurst.ruru.app.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Ruru's small per-person settings: typed reads and writes under dotted keys such as "night.schedule".
 * Desktop keeps them in java.util.prefs (under PrefsRoot), Android in SharedPreferences, iPhone in
 * NSUserDefaults. A read of a missing or wrongly typed value gives the default; nothing here throws.
 */
interface SettingsStore {
    fun getString(key: String, default: String? = null): String?
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getStringList(key: String): List<String>

    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
    fun putStringList(key: String, value: List<String>)

    fun contains(key: String): Boolean
    fun remove(key: String)

    /** Emits the key each time something in this app changes it. */
    val changes: SharedFlow<String>
}

/** The store for this platform. Desktop honours RURU_PREFS_NODE and the warm-up switch (then it holds nothing). */
expect fun createSettingsStore(): SettingsStore

/** The change feed every store in one process shares, so two parts of the app see each other's writes. */
internal object SettingsChanges {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 64)
}
