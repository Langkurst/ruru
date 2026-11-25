package io.github.langkurst.ruru.app.eq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The system's own file dialogs (AWT's FileDialog: the native Open and Save windows on Windows, macOS and Linux). */
class DesktopEqFiles : EqFiles {
    override suspend fun pickText(title: String): PickedText? = withContext(Dispatchers.IO) {
        val file = choose(title, FileDialog.LOAD, null) ?: return@withContext null
        runCatching {
            if (file.length() > MAX_BYTES) return@runCatching null
            PickedText(file.name, file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    override suspend fun saveText(title: String, suggestedName: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val file = choose(title, FileDialog.SAVE, suggestedName) ?: return@withContext false
        runCatching { file.writeText(text, Charsets.UTF_8) }.isSuccess
    }

    private fun choose(title: String, mode: Int, name: String?): File? {
        val dialog = FileDialog(null as Frame?, title, mode)
        if (name != null) dialog.file = name
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return File(dialog.directory ?: "", file)
    }

    private companion object {
        /** A preset file is a few hundred bytes; this only stops a wrong pick (a video) from being read. */
        const val MAX_BYTES = 256 * 1024L
    }
}
