package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.protocol.bose.BoseModel
import io.github.langkurst.ruru.vendors.apple.AppleStateMapping
import io.github.langkurst.ruru.vendors.apple.AppleStrings

/** The key of the sentence that says what a model's support note says; null for a note it doesn't know. */
fun supportNoteKey(note: String?): String? = if (note == AppleStateMapping.BATTERY_ONLY_NOTE) AppleStrings.NOTE_BATTERY_ONLY else boseNoteKey(note)

private fun boseNoteKey(note: String?): String? = when (BoseModel.entries.firstOrNull { it.supportNote == note && note != null }) {
    BoseModel.QC35, BoseModel.QC35_II -> "about_note_older_app"
    BoseModel.QC_EARBUDS_2024 -> "about_note_earbuds_app"
    BoseModel.NC700 -> "about_note_basic_no_modes"
    BoseModel.QC_EARBUDS -> "about_note_basic_earbuds"
    else -> null
}
