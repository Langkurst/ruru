package io.github.langkurst.ruru.vendors.sony

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strings_sony.xml file in each language folder of the app: every language has the English keys, no placeholders or
 * backslashes (the app shows the file's text as it is), nothing much longer than the English, and
 * the English text matches what the Sony code puts in [io.github.langkurst.ruru.core.VendorControl]
 * titles, hints and options, so the screen can swap in the person's language by key.
 */
class SonyStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_sony.xml")
        assertTrue(file.isFile, "$folder/strings_sony.xml is missing")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
            assertTrue(e.tagName == "string", "$folder: only <string> is used here")
            assertTrue(e.getAttribute("name") !in out, "$folder: ${e.getAttribute("name")} is defined twice")
            out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val english = load("values")

    @Test fun `every language has exactly the English keys, plain text and a sensible length`() {
        for (folder in languages) {
            val strings = load("values-$folder")
            assertEquals(english.keys.sorted(), strings.keys.sorted(), "values-$folder: the keys differ from English")
            for ((key, en) in english) {
                val text = strings.getValue(key)
                assertTrue(text.isNotBlank(), "values-$folder: $key is empty")
                assertTrue('\\' !in text && '%' !in text, "values-$folder: $key has a backslash or a %: $text")
                val allowed = maxOf((en.length * 2.2).toInt(), en.length + 8, 28)
                assertTrue(text.length <= allowed, "values-$folder: $key is ${text.length} characters, English is ${en.length} (limit $allowed): $text")
            }
        }
        assertTrue(english.values.none { '\\' in it || '%' in it })
    }

    @Test fun `the English file matches the words the Sony code puts in its controls`() {
        // A headset that announces everything the mapping can draw.
        val v2 = SonyProtocolInfo(2, 0x03003018, table1 = true, table2 = true)
        val base = SonySnapshot(protocol = v2).withSupport(setOf(0x6D, 0x50, 0x25, 0xFC, 0xF1, 0xE2, 0xE1), setOf(0x41))
        val s = base.copy(
            ncAsm = SonyNcAsm(0x19, true, ncAsmMode = 1, ambientLevel = 10, autoAmbient = true, autoAmbientSensitivity = 0),
            eqCapability = SonyEqCapability(0x00, 6, 21, listOf(0x00, 0x10)),
            eq = SonyEqSetting(0x00, 0x00, List(6) { 10 }),
            autoOffChoices = listOf(SonyAutoOff.MIN_5, SonyAutoOff.WHEN_REMOVED, SonyAutoOff.OFF),
            autoOff = SonyAutoOff.OFF,
            upscalingType = 0x02, dsee = true, connectionMode = 0, speakToChat = false, pauseWhenTakenOff = true, voiceGuidance = true,
        )
        val controls = SonyStateMapping.controls(s)
        val ids = controls.map { it.id }
        for (id in listOf(
            SonyStateMapping.AMBIENT_LEVEL, SonyStateMapping.FOCUS_ON_VOICE, SonyStateMapping.AUTO_AMBIENT,
            SonyStateMapping.AUTO_AMBIENT_SENSITIVITY, SonyStateMapping.EQ_PRESET, SonyStateMapping.AUTO_POWER_OFF,
            SonyStateMapping.SPEAK_TO_CHAT, SonyStateMapping.PAUSE_WHEN_TAKEN_OFF, SonyStateMapping.DSEE,
            SonyStateMapping.CONNECTION_PRIORITY, SonyStateMapping.VOICE_GUIDANCE,
        )) assertTrue(id in ids, "$id is drawn")

        for (c in controls) {
            val key = "vendor_${c.id}"
            when {
                // Band names are frequencies, the DSEE name is Sony's own, general settings are named by the headset.
                c.id.startsWith(SonyStateMapping.EQ_BAND_PREFIX) || c.id.startsWith(SonyStateMapping.GENERAL_PREFIX) -> Unit
                c.id == SonyStateMapping.DSEE -> assertEquals(english.getValue("${key}_hint"), c.subtitle)
                else -> {
                    assertEquals(english.getValue(key), c.title, key)
                    if (c.subtitle != null) assertEquals(english.getValue("${key}_hint"), c.subtitle, "${key}_hint")
                    else assertTrue("${key}_hint" !in english, "${key}_hint has no subtitle in the code")
                }
            }
        }
        val options = controls.associate { it.id to it.options }
        assertEquals(listOf("Low", "Standard", "High"), options[SonyStateMapping.AUTO_AMBIENT_SENSITIVITY])
        assertEquals(
            listOf("sony_option_low", "sony_option_standard", "sony_option_high").map { english.getValue(it) },
            options[SonyStateMapping.AUTO_AMBIENT_SENSITIVITY],
        )
        assertEquals(
            listOf("sony_option_sound_quality", "sony_option_stable_connection").map { english.getValue(it) },
            options[SonyStateMapping.CONNECTION_PRIORITY],
        )
        assertEquals(english.getValue("sony_option_when_taken_off"), SonyAutoOff.WHEN_REMOVED.label)
        assertEquals(english.getValue("sony_option_when_taken_off"), options[SonyStateMapping.AUTO_POWER_OFF]!![1])
    }

    @Test fun `SonyStrings names every key of the English file, and only keys that exist`() {
        val named = SonyStrings.TITLE.values + SonyStrings.HINT.values + SonyStrings.OPTION.values +
            listOf(SonyStrings.NOTE_EXPERIMENTAL, SonyStrings.NOTE_READ_ONLY, SonyStrings.MESSAGE_V1_ONLY)
        assertEquals(english.keys.sorted(), named.sorted())
        // The control ids the maps use are the ones the mapping draws, and the key is "vendor_" plus the id.
        for ((id, key) in SonyStrings.TITLE) assertEquals("vendor_$id", key)
        for ((id, key) in SonyStrings.HINT) assertEquals("vendor_${id}_hint", key)
    }

    @Test fun `the notes and the refusal message in the code match the English file`() {
        assertEquals(english.getValue("sony_note_experimental"), SonyStateMapping.SUPPORT_NOTE)
        assertEquals(english.getValue("sony_note_read_only"), SonyStateMapping.READ_ONLY_NOTE)
        assertTrue("sony_v1_only" in english, "SonySession refuses MDR v1 with Words(\"sony_v1_only\")")
    }
}
