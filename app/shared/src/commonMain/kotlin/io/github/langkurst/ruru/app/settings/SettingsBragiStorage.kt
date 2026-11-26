package io.github.langkurst.ruru.app.settings

import io.github.langkurst.ruru.vendors.bragi.BragiKeyStorage

/** The Skullcandy pairing keys, kept in the app's settings store (never in a settings backup: [BackupKeys] has no `bragi.` key). */
class SettingsBragiStorage(private val store: SettingsStore) : BragiKeyStorage {
    override fun get(key: String): String? = store.getString(key)
    override fun put(key: String, value: String) = store.putString(key, value)
    override fun remove(key: String) = store.remove(key)
}
