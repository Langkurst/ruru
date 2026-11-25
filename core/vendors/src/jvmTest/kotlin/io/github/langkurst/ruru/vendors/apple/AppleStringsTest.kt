package io.github.langkurst.ruru.vendors.apple

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** strings_apple.xml in each language: the English keys, plain text, a sensible length, and English equal to the code's notes. */
class AppleStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_apple.xml")
        assertTrue(file.isFile, "$folder/strings_apple.xml is missing")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
            assertTrue(e.tagName == "string")
            assertTrue(e.getAttribute("name") !in out, "$folder: ${e.getAttribute("name")} is defined twice")
            out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val english = load("values")

    @Test fun `every language has exactly the English keys, plain text and a sensible length`() {
        assertEquals(AppleStrings.KEYS, english.keys)
        for (folder in languages) {
            val strings = load("values-$folder")
            assertEquals(english.keys.sorted(), strings.keys.sorted(), "values-$folder")
            for ((key, en) in english) {
                val text = strings.getValue(key)
                assertTrue(text.isNotBlank(), "values-$folder: $key is empty")
                assertTrue('\\' !in text && '%' !in text, "values-$folder: $key has a backslash or a %")
                assertTrue(text.length <= maxOf((en.length * 2.2).toInt(), en.length + 8, 28), "values-$folder: $key is too long")
            }
        }
    }

    @Test fun `the English notes match the code`() {
        assertEquals(AppleStateMapping.BATTERY_ONLY_NOTE, english.getValue(AppleStrings.NOTE_BATTERY_ONLY))
        assertEquals(AppleStateMapping.NOT_POSSIBLE_NOTE, english.getValue(AppleStrings.NOTE_NOT_POSSIBLE))
        assertEquals(AppleStrings.NOTE_NOT_POSSIBLE, AppleAdvertVendor.notPossibleKey)
    }
}
