package io.github.langkurst.ruru.vendors.soundcore

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The strings_soundcore.xml file in each language folder of the app: every language has the English
 * keys, no placeholders or backslashes (the app shows the file's text as it is), nothing much
 * longer than the English, and the English text matches what the Soundcore code puts in
 * [io.github.langkurst.ruru.core.VendorControl] titles, hints and options, so the screen can swap
 * in the person's language by key.
 */
class SoundcoreStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_soundcore.xml")
        assertTrue(file.isFile, "$folder/strings_soundcore.xml is missing")
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

    private fun read(model: SoundcoreModel): SoundcoreState = assertNotNull(SoundcoreStateReader.read(model, VirtualSoundcore(model).stateBody()), model.code)

    @Test fun `the English file matches the words the Soundcore code puts in its controls`() {
        // Every control the code can draw: the Sleep A30's, and the models that between them have every setting.
        val a30 = SoundcoreStateMapping.controls(assertNotNull(D1301State.parse(VirtualSoundcore().stateBody())))
        val models = listOf(SoundcoreModel.LIBERTY_4_NC, SoundcoreModel.LIBERTY_5, SoundcoreModel.A30I, SoundcoreModel.LIBERTY_3_PRO, SoundcoreModel.SPACE_A40)
            .flatMap { SoundcoreModelMapping.controls(read(it)) }
        val controls = a30 + models
        val ids = controls.map { it.id }.toSet()
        assertEquals(SoundcoreStrings.TITLE.keys, ids, "every control the code can draw has a title key, and only those")

        for (c in controls) {
            val key = "vendor_${c.id}"
            assertEquals(english.getValue(key), c.title, key)
            if (c.subtitle != null) assertEquals(english.getValue("${key}_hint"), c.subtitle, "${key}_hint")
            else assertTrue("${key}_hint" !in english, "${key}_hint has no subtitle in the code")
            // A choice's labels: words from the file, the brand "Bluetooth", a number or a duration.
            for (option in c.options.orEmpty()) {
                val optionKey = SoundcoreStrings.OPTION[option]
                if (optionKey != null) assertEquals(option, english.getValue(optionKey), "$optionKey")
                else assertTrue(
                    option == "Bluetooth" || Regex("""\d+""").matches(option) || Regex("""\d+ (min|hours?)|1 hour""").matches(option),
                    "${c.id}: the option \"$option\" is neither in the file nor a number, a duration or a brand",
                )
            }
        }
        // And the labels the code uses for the words in the file are the ones the file has.
        val options = controls.flatMap { it.options.orEmpty() }.toSet()
        for (label in SoundcoreStrings.OPTION.keys) assertTrue(label in options, "no control offers the option \"$label\"")
    }

    @Test fun `SoundcoreStrings names every key of the English file, and only keys that exist`() {
        val named = SoundcoreStrings.TITLE.values + SoundcoreStrings.HINT.values + SoundcoreStrings.OPTION.values +
            listOf(
                SoundcoreStrings.NOTE_EXPERIMENTAL, SoundcoreStrings.NOTE_READ_ONLY, SoundcoreStrings.NOTE_BATTERY_ONLY,
                SoundcoreStrings.NOTE_UNREADABLE, SoundcoreStrings.MESSAGE_UNKNOWN_MODEL,
            )
        assertEquals(english.keys.sorted(), named.sorted())
        assertEquals(named.size, named.toSet().size, "no key is named twice")
        for ((id, key) in SoundcoreStrings.TITLE) assertEquals("vendor_$id", key)
        for ((id, key) in SoundcoreStrings.HINT) assertEquals("vendor_${id}_hint", key)
        assertTrue(SoundcoreStrings.TITLE.keys.containsAll(SoundcoreStrings.HINT.keys), "a hint belongs to a control with a title")
    }

    @Test fun `the notes and the refusal message in the code match the English file`() {
        assertEquals(english.getValue("soundcore_note_experimental"), SoundcoreStateMapping.SUPPORT_NOTE)
        assertEquals(english.getValue("soundcore_note_experimental"), SoundcoreModelMapping.EXPERIMENTAL)
        assertEquals(english.getValue("soundcore_note_read_only"), SoundcoreModelMapping.READ_ONLY_NOTE)
        assertEquals(english.getValue("soundcore_note_battery_only"), SoundcoreModelMapping.BATTERY_ONLY_NOTE)
        assertEquals(english.getValue("soundcore_note_unreadable"), SoundcoreModelMapping.UNREADABLE_NOTE)
        assertTrue("soundcore_unknown_model" in english, "SoundcoreSession refuses an unknown model with Words(\"soundcore_unknown_model\")")
        assertEquals("soundcore_unknown_model", SoundcoreSession.UNKNOWN_MODEL)
    }
}
