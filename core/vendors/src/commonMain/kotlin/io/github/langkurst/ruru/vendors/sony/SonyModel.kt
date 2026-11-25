package io.github.langkurst.ruru.vendors.sony

/**
 * What kind of Sony product a headset is, from the model name it reports (Earshot's SonyModel,
 * MIT). Recognising a Sony by its Bluetooth name and by service UUID lives in [SonyVendor].
 */
object SonyModel {
    /** Open-ear LinkBuds (the ring and the clip), by the model name the headset reports. */
    private val OPEN_EAR = Regex("""^WF-L(?:900|910|C900)\b|^LinkBuds(?:\s+(?:Open|Clip))?$""", RegexOption.IGNORE_CASE)

    enum class Shape { HEADPHONES, EARBUDS, OPEN_EARBUDS }

    /** From the MODEL_NAME string (e.g. "WH-1000XM5", "WF-1000XM5"), with the series byte as a fallback. */
    fun shapeOf(modelName: String?, series: Int? = null): Shape = when {
        modelName != null && OPEN_EAR.containsMatchIn(modelName.trim()) -> Shape.OPEN_EARBUDS
        modelName != null && modelName.trim().startsWith("WF-", ignoreCase = true) -> Shape.EARBUDS
        modelName != null && modelName.contains("LinkBuds", ignoreCase = true) -> Shape.EARBUDS
        modelName != null -> Shape.HEADPHONES
        series == 0x60 -> Shape.EARBUDS
        else -> Shape.HEADPHONES
    }
}
