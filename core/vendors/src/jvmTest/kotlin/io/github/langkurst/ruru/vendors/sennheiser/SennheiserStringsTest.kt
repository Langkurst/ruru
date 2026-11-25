package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.VendorControl
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strings_sennheiser.xml file in each language folder of the app: every language has the English
 * keys, no placeholders or backslashes (the app shows the file's text as it is), nothing much longer
 * than the English, and the English text matches what the Sennheiser code puts in
 * [VendorControl] titles, hints and options, so the screen can swap in the person's language by key.
 */
class SennheiserStringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")
    private val languages = listOf("de", "es", "fr", "it", "nl", "pt-rBR", "ja", "zh-rCN", "ko", "pl")

    private fun load(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings_sennheiser.xml")
        assertTrue(file.isFile, "$folder/strings_sennheiser.xml is missing")
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

    /** What each kind of Sennheiser headset draws, with every feature reported and every setting read. */
    private fun allControls(): Pair<List<VendorControl>, List<VendorControl>> {
        val everything = SennheiserStatus(
            features = VirtualSennheiser.M4_FEATURES,
            ancOn = true, ancModes = mapOf(1 to 1, 2 to 0, 3 to 0), transparencyLevel = 100, pauseInTransparency = false,
            onHeadDetection = true, smartPause = true, autoAnswer = false, comfortCall = true, sidetone = 2, autoOffSeconds = 900,
            eqConfig = SennheiserEqConfig(5, -60, 60), eqGains = List(5) { 0 }, bassBoost = false,
        )
        return SennheiserStateMapping.controls(everything, SennheiserModel.MOMENTUM_4) to
            SennheiserStateMapping.controls(everything, SennheiserModel.MOMENTUM_TW4)
    }

    @Test fun `the English file matches the words the Sennheiser code puts in its controls`() {
        val (headband, earbuds) = allControls()
        val ids = (headband + earbuds).map { it.id }.toSet()
        assertEquals(SennheiserStrings.TITLE.keys, ids, "every control the mapping draws has a title key, and no other")

        val hintsSeen = mutableSetOf<String>()
        for ((controls, hints) in listOf(headband to SennheiserStrings.HINT, earbuds to (SennheiserStrings.HINT + SennheiserStrings.HINT_EARBUDS))) {
            for (c in controls) {
                assertEquals(english.getValue("vendor_${c.id}"), c.title, c.id)
                if (c.subtitle != null) {
                    val key = hints.getValue(c.id)
                    assertEquals(english.getValue(key), c.subtitle, key)
                    hintsSeen += key
                } else {
                    assertTrue(c.id !in hints, "${c.id} has a hint key but no subtitle in the code")
                }
                // The English choices are the file's, by their fixed keys.
                c.options?.forEach { assertEquals(it, english.getValue(SennheiserStrings.OPTION.getValue(it))) }
            }
        }
        assertEquals(hintsSeen, (SennheiserStrings.HINT.values + SennheiserStrings.HINT_EARBUDS.values).toSet(), "every hint key is used by some model")
        assertEquals(
            listOf("Never", "15 min", "30 min", "1 h"),
            headband.first { it.id == SennheiserStateMapping.AUTO_OFF }.options,
        )
    }

    @Test fun `SennheiserStrings names every key of the English file, and only keys that exist`() {
        val named = SennheiserStrings.TITLE.values + SennheiserStrings.HINT.values + SennheiserStrings.HINT_EARBUDS.values +
            SennheiserStrings.OPTION.values + listOf(SennheiserStrings.NOTE_EXPERIMENTAL, SennheiserStrings.NOTE_NO_FEATURES)
        assertEquals(english.keys.sorted(), named.sorted())
        // The control ids the maps use are the ones the mapping draws, and the key is "vendor_" plus the id.
        for ((id, key) in SennheiserStrings.TITLE) assertEquals("vendor_$id", key)
        for ((id, key) in SennheiserStrings.HINT) assertEquals("vendor_${id}_hint", key)
        for ((id, key) in SennheiserStrings.HINT_EARBUDS) assertEquals("vendor_${id}_earbuds_hint", key)
        assertTrue(SennheiserStrings.TITLE.keys.all { it.startsWith("sennheiser_") }, "ids are prefixed")
    }

    @Test fun `the notes in the code match the English file`() {
        assertEquals(english.getValue(SennheiserStrings.NOTE_EXPERIMENTAL), SennheiserStateMapping.SUPPORT_NOTE)
        assertEquals(english.getValue(SennheiserStrings.NOTE_NO_FEATURES), SennheiserStateMapping.NO_FEATURES_NOTE)
        assertEquals(english.getValue("vendor_sennheiser_smart_pause_hint"), SennheiserStateMapping.SMART_PAUSE_HINT_HEADBAND)
        assertEquals(english.getValue("vendor_sennheiser_smart_pause_earbuds_hint"), SennheiserStateMapping.SMART_PAUSE_HINT_EARBUDS)
        assertEquals(english.getValue("vendor_sennheiser_anti_wind_earbuds_hint"), SennheiserStateMapping.ANTI_WIND_HINT_EARBUDS)
    }

    @Test fun `the mapping draws nothing for a headset that listed no features`() {
        val state = SennheiserStateMapping.apply(HeadphoneState(), SennheiserStatus(), null, now = 0)
        assertTrue(state.vendorControls.isEmpty() && state.modes.isEmpty())
        assertEquals(SennheiserStateMapping.NO_FEATURES_NOTE, state.capabilities.supportNote)
    }
}
