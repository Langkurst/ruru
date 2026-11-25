package io.github.langkurst.ruru.app.settings

import io.github.langkurst.ruru.app.RURU_VERSION

enum class SettingType { STRING, INT, BOOLEAN, FLOAT, STRING_LIST }

class BackupKey(val key: String, val type: SettingType)

/** The only settings a backup carries. Never add addresses, serial numbers or window bounds. */
object BackupKeys {
    val all: List<BackupKey> = listOf(
        BackupKey("eq.presets", SettingType.STRING_LIST),
        BackupKey("modes.favourites", SettingType.STRING_LIST),
        BackupKey("night.schedule", SettingType.STRING),
        BackupKey("sleep.plan", SettingType.STRING),
    )
}

sealed class BackupImport {
    /** [values] are what was read; [ignored] are keys in the file that this version does not know. */
    class Valid(val values: Map<String, Any>, val ignored: List<String>) : BackupImport()
    class Invalid(val errors: List<String>) : BackupImport()
}

/** Settings as a versioned JSON file: {"ruru": "backup", "version": 1, "app": "...", "settings": {...}}. */
object Backup {
    const val VERSION = 1

    private const val NOT_A_BACKUP = "This is not a Ruru backup file."

    /** The file text for [values]; keys outside [keys] and values of the wrong type are dropped. */
    fun encode(values: Map<String, Any>, keys: List<BackupKey> = BackupKeys.all, app: String = RURU_VERSION): String {
        val out = LinkedHashMap<String, Any?>()
        for (k in keys.sortedBy { it.key }) {
            val v = values[k.key] ?: continue
            if (convert(k.type, v) == null) continue
            out[k.key] = v
        }
        return SettingsJson.write(linkedMapOf("ruru" to "backup", "version" to VERSION, "app" to app, "settings" to out)) + "\n"
    }

    /** Checks the whole file first; anything wrong gives [BackupImport.Invalid] and nothing is applied. */
    fun decode(text: String, keys: List<BackupKey> = BackupKeys.all): BackupImport {
        val parsed = runCatching { SettingsJson.parse(text) }.getOrNull()
        val root = parsed as? Map<*, *> ?: return BackupImport.Invalid(listOf(NOT_A_BACKUP))
        if (root["ruru"] != "backup") return BackupImport.Invalid(listOf(NOT_A_BACKUP))
        val version = root["version"]
        if (version != 1.0) {
            return BackupImport.Invalid(listOf("This backup is from a different version of Ruru (file version ${version ?: "missing"}, expected $VERSION)."))
        }
        val settings = root["settings"] as? Map<*, *> ?: return BackupImport.Invalid(listOf("The backup has no settings."))
        val known = keys.associateBy { it.key }
        val values = LinkedHashMap<String, Any>()
        val errors = ArrayList<String>()
        val ignored = ArrayList<String>()
        for ((rawKey, raw) in settings) {
            val key = rawKey as? String ?: continue
            val spec = known[key]
            if (spec == null) {
                ignored += key
                continue
            }
            val v = convert(spec.type, raw)
            if (v == null) errors += "\"$key\" should be ${spec.type.name.lowercase().replace('_', ' ')}." else values[key] = v
        }
        return if (errors.isEmpty()) BackupImport.Valid(values, ignored) else BackupImport.Invalid(errors)
    }

    /** The current values of the allow-listed keys that are set in [store]. */
    fun read(store: SettingsStore, keys: List<BackupKey> = BackupKeys.all): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (k in keys) {
            if (!store.contains(k.key)) continue
            out[k.key] = when (k.type) {
                SettingType.STRING -> store.getString(k.key) ?: continue
                SettingType.INT -> store.getInt(k.key, 0)
                SettingType.BOOLEAN -> store.getBoolean(k.key, false)
                SettingType.FLOAT -> store.getFloat(k.key, 0f)
                SettingType.STRING_LIST -> store.getStringList(k.key)
            }
        }
        return out
    }

    fun export(store: SettingsStore, keys: List<BackupKey> = BackupKeys.all): String = encode(read(store, keys), keys)

    /** Applies a backup to [store] only when the whole file is valid. */
    fun import(store: SettingsStore, text: String, keys: List<BackupKey> = BackupKeys.all): BackupImport {
        val result = decode(text, keys)
        if (result is BackupImport.Valid) {
            val types = keys.associate { it.key to it.type }
            for ((key, v) in result.values) when (types.getValue(key)) {
                SettingType.STRING -> store.putString(key, v as String)
                SettingType.INT -> store.putInt(key, v as Int)
                SettingType.BOOLEAN -> store.putBoolean(key, v as Boolean)
                SettingType.FLOAT -> store.putFloat(key, v as Float)
                SettingType.STRING_LIST -> {
                    @Suppress("UNCHECKED_CAST")
                    store.putStringList(key, v as List<String>)
                }
            }
        }
        return result
    }

    /** [v] as the Kotlin type for [type] (Int and Float come from JSON numbers), or null when it does not fit. */
    private fun convert(type: SettingType, v: Any?): Any? = when (type) {
        SettingType.STRING -> v as? String
        SettingType.BOOLEAN -> v as? Boolean
        SettingType.INT -> when (v) {
            is Int -> v
            is Double -> if (v == v.toInt().toDouble()) v.toInt() else null
            else -> null
        }
        SettingType.FLOAT -> when (v) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            else -> null
        }
        SettingType.STRING_LIST -> (v as? List<*>)?.takeIf { l -> l.all { it is String } }?.map { it as String }
    }
}
