package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.CrusherBass
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strings_skullcandy.xml file in each language folder of the app: every language has the English
 * keys, no placeholders or backslashes (the app shows the file's text as it is), nothing much
 * longer than the English, and the English text matches what the Skullcandy code puts in
 * [io.github.langkurst.ruru.core.VendorControl] titles, hints and options, so the screen can swap
 * in the person's language by key.
 */
class BragiStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_skullcandy.xml")
        assertTrue(file.isFile, "$folder/strings_skullcandy.xml is missing")
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

    @Test fun `the English file matches the words the Skullcandy code puts in its controls`() {
        val controls = BragiStateMapping.controls(CrusherBass(active = true, intensity = 5, maxIntensity = 10, mode = 1, modeCount = 3))
        assertEquals(BragiStrings.TITLE.keys, controls.map { it.id }.toSet(), "every control the code can draw has a title key, and only those")
        for (c in controls) {
            val key = "vendor_${c.id}"
            assertEquals(english.getValue(key), c.title, key)
            if (c.subtitle != null) assertEquals(english.getValue("${key}_hint"), c.subtitle, "${key}_hint")
            else assertTrue("${key}_hint" !in english, "${key}_hint has no subtitle in the code")
            for (option in c.options.orEmpty()) assertEquals(option, english.getValue(BragiStrings.OPTION.getValue(option)), "option $option")
        }
        // Every word the code offers as a choice is in the file, and every choice in the file is offered.
        assertEquals(BragiStateMapping.FEELS.toSet(), BragiStrings.OPTION.keys)
    }

    @Test fun `BragiStrings names every key of the English file, and only keys that exist`() {
        val named = BragiStrings.TITLE.values + BragiStrings.HINT.values + BragiStrings.OPTION.values +
            listOf(BragiStrings.NOTE_EXPERIMENTAL, BragiStrings.NOTE_METHOD)
        assertEquals(english.keys.sorted(), named.sorted())
        assertEquals(named.size, named.toSet().size, "no key is named twice")
        for ((id, key) in BragiStrings.TITLE) assertEquals("vendor_$id", key)
        for ((id, key) in BragiStrings.HINT) assertEquals("vendor_${id}_hint", key)
        assertTrue(BragiStrings.TITLE.keys.containsAll(BragiStrings.HINT.keys), "a hint belongs to a control with a title")
    }

    @Test fun `the notes in the code match the English file`() {
        assertEquals(english.getValue("skullcandy_note_experimental"), BragiStateMapping.SUPPORT_NOTE)
        assertEquals(english.getValue("skullcandy_note_method"), BragiStateMapping.METHOD_NOTE)
    }

    @Test fun `every control id and key is prefixed skullcandy`() {
        assertTrue(BragiStrings.TITLE.keys.all { it.startsWith("skullcandy_") })
        assertTrue(english.keys.all { it.startsWith("vendor_skullcandy_") || it.startsWith("skullcandy_") })
    }
}
