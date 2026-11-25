package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.VendorControl
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.jbl.JblCommands as C
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The strings_jbl.xml file in each language folder of the app: every language has the English
 * keys, no placeholders or backslashes (the app shows the file's text as it is), nothing much longer
 * than the English, and the English text matches what the JBL code puts in [VendorControl] titles,
 * hints and options and in its notes and refusals, so the screen can swap in the person's language
 * by key.
 */
class JblStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_jbl.xml")
        assertTrue(file.isFile, "$folder/strings_jbl.xml is missing")
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

    /** What the mapping draws for a headset that answered every read: once with a JBL preset, once with a custom curve. */
    private fun controls(eqCategory: Int): List<VendorControl> {
        val status = JblStatus(modes = listOf(1, 2, 3), activeMode = 1, ancLevel = 3, eqCategory = eqCategory, voicePrompts = true)
        return JblStateMapping.controls(status, JblFeature.entries.toSet())
    }

    @Test fun `the English file matches the words the JBL code puts in its controls`() {
        val withPreset = controls(C.EQ_PRESETS[1].category)
        val withCustom = controls(C.EQ_CUSTOM)
        val ids = (withPreset + withCustom).map { it.id }.toSet()
        assertEquals(JblStrings.TITLE.keys, ids, "every control the mapping draws has a title key, and no other")

        val hintsSeen = mutableSetOf<String>()
        for (c in withPreset + withCustom) {
            assertEquals(english.getValue("vendor_${c.id}"), c.title, c.id)
            if (c.subtitle != null) {
                val key = JblStrings.HINT.getValue(c.id)
                assertEquals(english.getValue(key), c.subtitle, key)
                hintsSeen += key
            }
            c.options?.forEach { assertEquals(it, english.getValue(JblStrings.OPTION.getValue(it))) }
        }
        assertEquals(JblStrings.HINT.values.toSet(), hintsSeen, "every hint key is shown by some state")
        assertNull(withPreset.first { it.id == JblStateMapping.EQ_PRESET }.subtitle, "a known preset needs no hint")
        assertEquals(JblStrings.OPTION.keys, C.EQ_PRESETS.map { it.label }.toSet(), "every preset has a word in the file")
    }

    @Test fun `JblStrings names every key of the English file, and only keys that exist`() {
        val named = JblStrings.TITLE.values + JblStrings.HINT.values + JblStrings.OPTION.values +
            listOf(JblStrings.NOTE_EXPERIMENTAL, JblStrings.NOTE_SILENT, JblStrings.MESSAGE_NEWER_PROTOCOL, JblStrings.MESSAGE_AIROHA, JblStrings.MESSAGE_UNKNOWN_MODEL)
        assertEquals(english.keys.sorted(), named.sorted())
        for ((id, key) in JblStrings.TITLE) assertEquals("vendor_$id", key)
        for ((id, key) in JblStrings.HINT) assertEquals("vendor_${id}_hint", key)
        assertTrue(JblStrings.TITLE.keys.all { it.startsWith("jbl_") }, "ids are prefixed")
    }

    @Test fun `the notes in the code match the English file`() {
        assertEquals(english.getValue(JblStrings.NOTE_EXPERIMENTAL), JblStateMapping.SUPPORT_NOTE)
        assertEquals(english.getValue(JblStrings.NOTE_SILENT), JblStateMapping.SILENT_NOTE)
        assertEquals(english.getValue("vendor_jbl_anc_level_hint"), JblStateMapping.ANC_LEVEL_HINT)
        assertEquals(english.getValue("vendor_jbl_eq_preset_hint"), JblStateMapping.EQ_PRESET_HINT)
        assertEquals(english.getValue("vendor_jbl_voice_prompts_hint"), JblStateMapping.VOICE_PROMPTS_HINT)
    }

    @Test fun `every refusal the session can end a connect with is a sentence in the file`() {
        for (pid in listOf(0x2101, 0x20B7)) {
            val e = assertFailsWith<ExplainedConnectException> { JblSession.checkProduct(JblDeviceInfo("JBL", pid, null, null), "JBL Wave Buds 2") }
            assertTrue(e.words.key in english, "${e.words}")
        }
        val e = assertFailsWith<ExplainedConnectException> { JblSession.checkProduct(JblDeviceInfo("JBL", null, null, null), null) }
        assertEquals(Words(JblStrings.MESSAGE_UNKNOWN_MODEL), e.words)
        assertTrue(e.words.key in english)
    }

    @Test fun `the mapping draws nothing for a headset that reported nothing`() {
        val state = JblStateMapping.apply(HeadphoneState(), JblStatus(), null, emptySet(), now = 0)
        assertTrue(state.vendorControls.isEmpty() && state.modes.isEmpty())
        assertEquals(JblStateMapping.SILENT_NOTE, state.capabilities.supportNote)
    }
}
