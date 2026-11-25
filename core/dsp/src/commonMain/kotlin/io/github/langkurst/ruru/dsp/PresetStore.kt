package io.github.langkurst.ruru.dsp

import kotlinx.serialization.Serializable

/**
 * A Pro EQ preset: a graphic set of ten gains or a parametric list, plus the preamp.
 * Built-in ones come from [BuiltInPresets] and cannot be renamed or deleted.
 */
@Serializable
data class ProEqPreset(
    val id: String,
    val name: String,
    val mode: ProEqMode = ProEqMode.PARAMETRIC,
    val graphicGainsDb: List<Double> = List(ProEq.GRAPHIC_BAND_COUNT) { 0.0 },
    val bands: List<ParametricBand> = emptyList(),
    val preampDb: Double = 0.0,
    val autoPreamp: Boolean = false,
    val isBuiltIn: Boolean = false,
) {
    fun sanitized(): ProEqPreset = copy(
        graphicGainsDb = ProEq.fitGains(graphicGainsDb),
        bands = bands.take(ProEq.MAX_BANDS).map { ProEq.clampBand(it) },
        preampDb = ProEq.clampPreamp(preampDb),
    )

    /** The state that plays this preset, keeping the on/off and limiter choices of [current]. */
    fun applyTo(current: ProEqState): ProEqState = current.copy(
        mode = mode,
        graphicGainsDb = graphicGainsDb,
        bands = bands,
        preampDb = preampDb,
        autoPreamp = autoPreamp,
        activePresetId = id,
    ).sanitized()

    companion object {
        fun fromEqPreset(p: EqPreset): ProEqPreset = ProEqPreset(
            id = p.id,
            name = p.name,
            mode = ProEqMode.PARAMETRIC,
            bands = p.bands,
            preampDb = p.preampDb,
            autoPreamp = false,
            isBuiltIn = p.isBuiltIn,
        ).sanitized()

        fun fromState(id: String, name: String, s: ProEqState): ProEqPreset = ProEqPreset(
            id = id,
            name = name,
            mode = s.mode,
            graphicGainsDb = s.graphicGainsDb,
            bands = s.bands,
            preampDb = s.preampDb,
            autoPreamp = s.autoPreamp,
        ).sanitized()

        val builtIn: List<ProEqPreset> by lazy { BuiltInPresets.all.map { fromEqPreset(it) } }
    }
}

/**
 * Built-in presets plus the user's saved ones, and which preset each headset uses.
 * Immutable: every change returns a new store. [encode] gives one string for the settings
 * store; [decode] never throws.
 *
 * Headsets are keyed by their NAME (trimmed, lower case), never by Bluetooth address.
 */
@Serializable
data class PresetStore(
    val saved: List<ProEqPreset> = emptyList(),
    val perHeadset: Map<String, String> = emptyMap(),
) {
    fun all(): List<ProEqPreset> = ProEqPreset.builtIn + saved

    fun byId(id: String?): ProEqPreset? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    /** Save [state] as a new preset called [name]; returns the store and the new preset. */
    fun saveNew(name: String, state: ProEqState): Pair<PresetStore, ProEqPreset> {
        val preset = ProEqPreset.fromState(nextId(), cleanName(name) ?: DEFAULT_NAME, state)
        return copy(saved = saved + preset) to preset
    }

    /** Overwrite a saved preset with [state]. Built-ins and unknown ids are left alone. */
    fun update(id: String, state: ProEqState): PresetStore {
        val old = saved.firstOrNull { it.id == id } ?: return this
        val next = ProEqPreset.fromState(old.id, old.name, state)
        return copy(saved = saved.map { if (it.id == id) next else it })
    }

    /** Rename a saved preset. Built-ins, unknown ids and blank names change nothing. */
    fun rename(id: String, name: String): PresetStore {
        val clean = cleanName(name) ?: return this
        if (saved.none { it.id == id }) return this
        return copy(saved = saved.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Delete a saved preset and forget any headset that used it. Built-ins cannot be deleted. */
    fun delete(id: String): PresetStore {
        if (saved.none { it.id == id }) return this
        return copy(saved = saved.filterNot { it.id == id }, perHeadset = perHeadset.filterValues { it != id })
    }

    /** Import AutoEq text as a new saved preset. Throws [AutoEqParser.ParseException] on a bad file. */
    fun importAutoEq(text: String, name: String): Pair<PresetStore, ProEqPreset> {
        val parsed = AutoEqParser.parse(text, id = nextId(), name = cleanName(name) ?: DEFAULT_NAME)
        val preset = ProEqPreset.fromEqPreset(parsed).copy(isBuiltIn = false)
        return copy(saved = saved + preset) to preset
    }

    fun presetIdFor(headphoneName: String): String? =
        headsetKey(headphoneName)?.let { perHeadset[it] }?.takeIf { byId(it) != null }

    /** Remember [presetId] for this headset; null forgets it. Unknown ids are ignored. */
    fun withPresetFor(headphoneName: String, presetId: String?): PresetStore {
        val key = headsetKey(headphoneName) ?: return this
        return when {
            presetId == null -> copy(perHeadset = perHeadset - key)
            byId(presetId) == null -> this
            else -> copy(perHeadset = perHeadset + (key to presetId))
        }
    }

    fun encode(): String = PresetCodec.json.encodeToString(serializer(), cleaned())

    private fun cleaned(): PresetStore = copy(saved = saved.map { it.sanitized().copy(isBuiltIn = false) })

    private fun nextId(): String {
        val used = saved.map { it.id }.toSet()
        var n = 1
        while ("$USER_PREFIX$n" in used) n++
        return "$USER_PREFIX$n"
    }

    companion object {
        const val USER_PREFIX = "user-"
        const val DEFAULT_NAME = "My preset"
        const val MAX_NAME_LENGTH = 40

        private val spaces = Regex("\\s+")

        fun headsetKey(name: String): String? =
            name.trim().replace(spaces, " ").lowercase().takeIf { it.isNotEmpty() }

        fun cleanName(name: String): String? =
            name.trim().replace(spaces, " ").take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

        /** Never throws: unreadable text gives an empty store. Ids that clash with a built-in are dropped. */
        fun decode(text: String?): PresetStore {
            val raw = text?.let {
                runCatching { PresetCodec.json.decodeFromString(serializer(), it) }.getOrNull()
            } ?: return PresetStore()
            val builtInIds = ProEqPreset.builtIn.map { it.id }.toSet()
            val seen = HashSet<String>()
            val saved = raw.saved
                .filter { it.id !in builtInIds && seen.add(it.id) }
                .map { it.sanitized().copy(isBuiltIn = false, name = cleanName(it.name) ?: DEFAULT_NAME) }
            val ids = saved.map { it.id }.toSet() + builtInIds
            return PresetStore(saved, raw.perHeadset.filterValues { it in ids })
        }
    }
}
