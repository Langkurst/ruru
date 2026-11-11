package io.github.langkurst.ruru.platform.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Windows settings Ruru follows. */
object WinSystem {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    /** False when "Show animations in Windows" is off (Settings, Accessibility, Visual effects). */
    fun animationsEnabled(): Boolean = runCatching {
        val out = Memory(4).apply { setInt(0, 1) }
        if (User32.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, out, 0)) out.getInt(0) != 0 else true
    }.getOrDefault(true)

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SystemParametersInfoW(action: Int, param: Int, out: Pointer, winIni: Int): Boolean

        companion object {
            val INSTANCE: User32 = Native.load("user32", User32::class.java)
        }
    }
}
