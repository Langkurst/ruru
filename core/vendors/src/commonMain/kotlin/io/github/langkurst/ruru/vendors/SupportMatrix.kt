package io.github.langkurst.ruru.vendors

/** The systems Ruru runs on, as the support table names them. */
enum class SupportSystem(val label: String) {
    WINDOWS("Windows"), MACOS("macOS"), LINUX("Linux"), ANDROID("Android"), IPHONE("iPhone");

    companion object {
        /** The system for a platform's name ("Windows", "macOS", "Linux", "Android", "iPhone"); null for any other (the demo, a test). */
        fun ofPlatform(name: String): SupportSystem? = entries.firstOrNull { it.label == name }
    }
}

/** How far Ruru's support goes for one maker on one system. */
enum class SupportLevel { TESTED, EXPERIMENTAL, BATTERY_ONLY, NOT_POSSIBLE }

/** Why a maker's headphones can't be reached on a system, in a sentence the app, the README and the site can all use. */
enum class NotPossibleReason(val plain: String) {
    NO_SERVICE_LINK("An iPhone can't open the Bluetooth service these headphones use."),
    NO_ADVERTS("Apple doesn't let a Mac or an iPhone app read AirPods or Beats, so the battery can't be shown here."),
}

/** One cell of the table. [why] is set exactly when [level] is [SupportLevel.NOT_POSSIBLE]. */
data class SupportCell(val level: SupportLevel, val why: NotPossibleReason? = null) {
    init {
        require((level == SupportLevel.NOT_POSSIBLE) == (why != null)) { "a reason goes with Not possible, and only with it" }
    }
}

/** One row: a maker and what Ruru does with it on each system. */
data class SupportRow(val family: DeviceFamily, val brand: String, val cells: Map<SupportSystem, SupportCell>)

/**
 * The one table of what Ruru supports, as data: the About page draws it, and the README and the site
 * can be generated from it ([markdown]). "Tested" belongs to one model on one system and nothing
 * else; every other cell is "Experimental" (built from published research and a virtual headset,
 * not yet tried on real hardware), "Battery only" or "Not possible" with its reason.
 */
object SupportMatrix {
    /** The only model that is Tested, and the only system it is Tested on. */
    const val TESTED_MODEL = "QC Ultra Headphones (1st Gen)"
    val TESTED_SYSTEM = SupportSystem.WINDOWS

    /** What "Experimental" means, in one sentence. */
    const val EXPERIMENTAL_PLAIN = "Built from published research and a virtual headset, not yet tried on real hardware."

    private val experimental = SupportCell(SupportLevel.EXPERIMENTAL)
    private val tested = SupportCell(SupportLevel.TESTED)
    private val battery = SupportCell(SupportLevel.BATTERY_ONLY)
    private fun no(why: NotPossibleReason) = SupportCell(SupportLevel.NOT_POSSIBLE, why)

    /** Makers that Ruru opens by a Bluetooth service (RFCOMM): not possible on an iPhone. */
    private fun serviceMaker(family: DeviceFamily, brand: String) = SupportRow(
        family, brand,
        SupportSystem.entries.associateWith { if (it == SupportSystem.IPHONE) no(NotPossibleReason.NO_SERVICE_LINK) else experimental },
    )

    val rows: List<SupportRow> = listOf(
        SupportRow(DeviceFamily.BOSE, "Bose", SupportSystem.entries.associateWith { if (it == TESTED_SYSTEM) tested else experimental }),
        serviceMaker(DeviceFamily.SONY, "Sony"),
        serviceMaker(DeviceFamily.SENNHEISER, "Sennheiser"),
        serviceMaker(DeviceFamily.SOUNDCORE, "Soundcore"),
        SupportRow(DeviceFamily.JBL, "JBL", SupportSystem.entries.associateWith { experimental }),
        serviceMaker(DeviceFamily.BRAGI, "Skullcandy"),
        SupportRow(
            DeviceFamily.APPLE_ADVERT, "AirPods and Beats",
            SupportSystem.entries.associateWith { if (it == SupportSystem.MACOS || it == SupportSystem.IPHONE) no(NotPossibleReason.NO_ADVERTS) else battery },
        ),
    )

    fun row(family: DeviceFamily): SupportRow? = rows.firstOrNull { it.family == family }

    fun cell(family: DeviceFamily, system: SupportSystem): SupportCell? = row(family)?.cells?.get(system)

    /** The table as Markdown, for the README and the site to be generated from. */
    fun markdown(): String = buildString {
        append("| Maker |")
        SupportSystem.entries.forEach { append(" ${it.label} |") }
        appendLine()
        append("|---|")
        SupportSystem.entries.forEach { _ -> append("---|") }
        appendLine()
        for (row in rows) {
            append("| ${row.brand} |")
            for (system in SupportSystem.entries) append(" ${word(row.cells.getValue(system))} |")
            appendLine()
        }
    }

    /** The one or two words a cell shows. */
    fun word(cell: SupportCell): String = when (cell.level) {
        SupportLevel.TESTED -> "Tested"
        SupportLevel.EXPERIMENTAL -> "Experimental"
        SupportLevel.BATTERY_ONLY -> "Battery only"
        SupportLevel.NOT_POSSIBLE -> "Not possible"
    }
}
