package io.github.langkurst.ruru.app.tools

import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ruru switches devices; it has no command that unpairs, forgets or clears one. */
class NoUnpairCommandTest {
    @Test
    fun `the only commands that name a device are switching the sound and renaming the headphones`() {
        val names = HeadsetCommand::class.java.declaredClasses.map { it.simpleName }
        assertTrue(names.size > 10, "found the command list: $names")
        val deviceCommands = names.filter { it.contains("Device", ignoreCase = true) || it.contains("Pair", ignoreCase = true) }.toSet()
        assertEquals(setOf("SwitchDevice", "SetDeviceName"), deviceCommands)
        for (name in names) {
            assertTrue(
                listOf("Unpair", "Forget", "Clear", "Remove", "Reset", "Firmware", "Disconnect").none { name.contains(it, ignoreCase = true) } || name == "RemoveMode",
                "$name must not exist",
            )
        }
    }

    @Test
    fun `the controller has no function that removes a device`() {
        val functions = HeadsetController::class.java.declaredMethods.map { it.name.lowercase() }
        for (word in listOf("unpair", "forget", "cleardevice", "removedevice")) {
            assertTrue(functions.none { word in it }, "$word found in $functions")
        }
    }
}
