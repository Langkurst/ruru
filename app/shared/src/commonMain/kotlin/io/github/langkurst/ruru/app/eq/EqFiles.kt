package io.github.langkurst.ruru.app.eq

/** A text file the person chose: its name (without the folder) and what it holds. */
class PickedText(val fileName: String, val text: String)

/** Opening and saving the preset files with the system's file dialogs. A system without them gives no [EqFiles] and the page hides the buttons. */
interface EqFiles {
    /** Ask for one text file; null when the person cancels or the file cannot be read (or is far larger than any preset). */
    suspend fun pickText(title: String): PickedText?

    /** Ask where to save [text], suggesting [suggestedName]; false when the person cancels or the file cannot be written. */
    suspend fun saveText(title: String, suggestedName: String, text: String): Boolean
}
