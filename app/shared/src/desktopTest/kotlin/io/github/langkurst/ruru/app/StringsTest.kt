@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.action_cancel
import io.github.langkurst.ruru.ui.res.battery_percent_charging
import io.github.langkurst.ruru.ui.res.blocked_title
import io.github.langkurst.ruru.ui.res.problem_ios_not_paired
import io.github.langkurst.ruru.ui.res.setting_auto_off_hours
import io.github.langkurst.ruru.ui.res.setting_auto_off_minutes
import io.github.langkurst.ruru.ui.res.setting_self_voice_hint
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The safety nets for translations. Every language file must have the same keys as the English one,
 * the same `%1$s` / `%1$d` values in every sentence, no empty text, apostrophes written plainly (the
 * app shows the file's text as it is, so a backslash would show), and no sentence far longer than the
 * English one (it would not fit).
 * The checks run over every language folder, including the ones still waiting for a translation.
 */
class StringsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resources = File(root, "app/shared/src/commonMain/composeResources")

    /** One entry of a strings.xml: a plain string (one item, quantity ""), or a plural (one item per quantity). */
    private class Entry(val items: Map<String, String>)

    /** strings.xml plus the files a maker adds next to it (strings_sony.xml, ...): one set of keys, none defined twice. */
    private fun load(folder: String): Map<String, Entry> {
        val main = File(resources, "$folder/strings.xml")
        assertTrue(main.isFile, "$folder/strings.xml is missing")
        val extra = File(resources, folder).listFiles { f -> f.isFile && f.name.startsWith("strings_") && f.extension == "xml" }!!.sortedBy { it.name }
        val out = LinkedHashMap<String, Entry>()
        for (file in listOf(main) + extra) loadFile(folder, file, out)
        return out
    }

    private fun loadFile(folder: String, file: File, out: MutableMap<String, Entry>) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(file) // not well-formed XML (a bare & or <) fails here
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            assertTrue(name.isNotEmpty(), "$folder: an <${e.tagName}> has no name")
            assertTrue(name !in out, "$folder: $name is defined twice")
            out[name] = when (e.tagName) {
                "string" -> Entry(mapOf("" to e.textContent))
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    Entry((0 until items.length).associate { j -> (items.item(j) as Element).getAttribute("quantity") to items.item(j).textContent })
                }
                else -> error("$folder: <${e.tagName} name=$name> is not supported")
            }
        }
    }

    private val english = load("values")
    private val others = LANGUAGES.keys.associateWith { load("values-$it") }

    @Test
    fun `there is a strings file for each of the ten languages`() {
        assertEquals(10, LANGUAGES.size)
        for (folder in LANGUAGES.keys) assertTrue(File(resources, "values-$folder/strings.xml").isFile, "values-$folder/strings.xml is missing")
    }

    @Test
    fun `every language has exactly the keys of the English file`() {
        for ((folder, strings) in others) {
            assertEquals(english.keys.toList().sorted(), strings.keys.toList().sorted(), "values-$folder: the keys differ from English")
            for ((key, entry) in english) {
                val isPlural = "" !in entry.items
                assertEquals(isPlural, "" !in strings.getValue(key).items, "values-$folder: $key is a string in one file and a plural in the other")
            }
        }
    }

    @Test
    fun `every sentence keeps the same values to fill in as English`() {
        for ((folder, strings) in others) for ((key, entry) in english) {
            val mine = strings.getValue(key)
            if ("" in entry.items) {
                assertEquals(placeholders(entry.items.getValue("")), placeholders(mine.items.getValue("")), "values-$folder: $key changes the %1\$s / %1\$d values")
            } else {
                // A plural may leave the number out of the singular ("one hour" as "an hour"), but never add or change one.
                val allowed = entry.items.values.flatMap { placeholders(it) }.toSet()
                for ((quantity, text) in mine.items) {
                    assertTrue(allowed.containsAll(placeholders(text)), "values-$folder: $key ($quantity) uses a value English doesn't have")
                }
            }
        }
    }

    @Test
    fun `no sentence is empty`() {
        for ((folder, strings) in others + ("" to english)) for ((key, entry) in strings) for ((quantity, text) in entry.items) {
            val where = "values${if (folder.isEmpty()) "" else "-$folder"}: $key $quantity"
            // The list separator is ", " (or the language's own mark): it may be only punctuation, but not nothing.
            if (key == "list_separator") assertTrue(text.isNotEmpty(), "$where is empty") else assertTrue(text.isNotBlank(), "$where is empty")
        }
    }

    @Test
    fun `apostrophes are written as they are, not with a backslash`() {
        for ((folder, strings) in others + ("" to english)) for ((key, entry) in strings) for ((_, text) in entry.items) {
            val where = "values${if (folder.isEmpty()) "" else "-$folder"}: $key"
            // The app shows the text exactly as it is in the file, so a backslash before an apostrophe (or an n)
            // would appear as a backslash. XML itself needs no escape for ' or " in text; & and < are written
            // &amp; and &lt; (the parser in load() rejects a bare one).
            assertTrue(!text.contains(BACKSLASH), "$where has a backslash, which would be shown as it is: $text")
        }
    }

    @Test
    fun `no sentence is much longer than the English one`() {
        for ((folder, strings) in others) for ((key, entry) in english) {
            for ((quantity, en) in entry.items) {
                val mine = strings.getValue(key).items[quantity] ?: continue
                // 2.2 times the English length, with room for short labels ("Auto-off" is "Automatisch ausschalten"):
                // anything up to 28 characters fits a 360dp row, and rows wrap.
                val allowed = maxOf((en.length * 2.2).toInt(), en.length + 8, 28)
                assertTrue(mine.length <= allowed, "values-$folder: $key is ${mine.length} characters, English is ${en.length} (limit $allowed): $mine")
            }
        }
    }

    @Test
    fun `the plural forms are ones the resource format knows, and include other`() {
        val known = setOf("zero", "one", "two", "few", "many", "other")
        for ((folder, strings) in others + ("" to english)) for ((key, entry) in strings) {
            if ("" in entry.items) continue
            assertTrue(known.containsAll(entry.items.keys), "values-$folder: $key has an unknown plural form ${entry.items.keys}")
            assertTrue("other" in entry.items, "values-$folder: $key has no 'other' form")
        }
    }

    @Test
    fun `every message key the code uses exists, with the number of values its sentence takes`() {
        val kotlin = kotlinSources()
        val used = LinkedHashMap<String, MutableSet<Int>>()
        for ((file, text) in kotlin) {
            if (file.name.endsWith("Test.kt")) continue
            var from = 0
            while (true) {
                val at = text.indexOf("Words(\"", from)
                if (at < 0) break
                val keyStart = at + "Words(\"".length
                val keyEnd = text.indexOf('"', keyStart)
                val key = text.substring(keyStart, keyEnd)
                used.getOrPut(key) { mutableSetOf() }.add(argumentCount(text, keyEnd + 1))
                from = keyEnd
            }
        }
        // supportNoteKey() gives the key as a plain literal.
        Regex("""->\s*"(about_note_[a-z_]+)"""").findAll(kotlin.single { it.first.name == "SupportNotes.kt" }.second)
            .forEach { used.getOrPut(it.groupValues[1]) { mutableSetOf() }.add(0) }
        assertTrue(used.size > 40, "found only ${used.size} message keys: has the way Words are written changed?")
        for ((key, counts) in used) {
            val entry = english[key] ?: fail("The code uses the message key $key, which strings.xml does not have")
            val wanted = placeholders(entry.items.values.first()).size
            for (n in counts) assertEquals(wanted, n, "$key is given $n values but its sentence takes $wanted")
        }
    }

    @Test
    fun `every English sentence is used somewhere`() {
        val all = kotlinSources().filterNot { it.first.name.endsWith("Test.kt") }.joinToString("\n") { it.second }
        for (key in english.keys) {
            val used = Regex("""Res\.(string|plurals)\.$key\b""").containsMatchIn(all) || "\"$key\"" in all
            assertTrue(used, "$key is in strings.xml but nothing uses it")
        }
    }

    @Test
    fun `every language folder is a resource qualifier the app resolves`() {
        // The build lists, for each string, the folders it found and the language and region it reads
        // them as. A folder named in a way the resource plugin does not understand would be missing
        // here (or have no qualifier), and its sentences would never be chosen.
        val accessors = File(root, "app/shared/build/generated/compose/resourceGenerator/kotlin/commonMainResourceAccessors")
            .walkTopDown().filter { it.isFile && it.name.startsWith("String") }.joinToString("\n") { it.readText() }
        assertTrue(accessors.isNotBlank(), "the build's list of strings was not found (run the build first)")
        for (folder in LANGUAGES.keys) {
            val line = accessors.lines().firstOrNull { "values-$folder/strings.commonMain.cvr" in it }
                ?: fail("the build did not pick up values-$folder")
            val parts = folder.split("-r")
            assertTrue("LanguageQualifier(\"${parts[0]}\")" in line, "values-$folder is not read as the language ${parts[0]}: $line")
            if (parts.size > 1) assertTrue("RegionQualifier(\"${parts[1]}\")" in line, "values-$folder is not read as the region ${parts[1]}: $line")
        }
    }

    /**
     * The resource environment for a system language, built the way the desktop app builds its own
     * from the JVM's default locale (language, script, region), so the test needs no window or native library.
     */
    private fun environment(tag: String): ResourceEnvironment {
        val l = Locale.forLanguageTag(tag)
        // The constructor is marked internal in Kotlin (it is public in the bytecode); the library's own
        // default needs the window toolkit's native theme code, which a plain test run does not have.
        return ResourceEnvironment::class.java.constructors.single().newInstance(
            LanguageQualifier(l.language), ScriptQualifier(l.script), RegionQualifier(l.country), ThemeQualifier.LIGHT, DensityQualifier.MDPI,
        ) as ResourceEnvironment
    }

    @Test
    fun `English reads exactly as it did before the move to resources`() = runBlocking {
        val en = environment("en")
        assertEquals("Ruru can't see your headphones yet", getString(en, Res.string.blocked_title))
        assertEquals("Battery 80%, charging", getString(en, Res.string.battery_percent_charging, 80))
        assertEquals("How much of your own voice you hear on calls", getString(en, Res.string.setting_self_voice_hint))
        assertEquals("Cancel", getString(en, Res.string.action_cancel))
        assertEquals("5 minutes", getPluralString(en, Res.plurals.setting_auto_off_minutes, 5, 5))
        assertEquals("1 hour", getPluralString(en, Res.plurals.setting_auto_off_hours, 1, 1))
        assertEquals("24 hours", getPluralString(en, Res.plurals.setting_auto_off_hours, 24, 24))
        assertEquals(
            "Bose QC didn't pair with Ruru. Press Try again and choose Pair when iPhone asks. If iPhone doesn't ask, " +
                "choose Forget This Device for Bose QC in Settings > Bluetooth, then connect them again.",
            getString(en, Res.string.problem_ios_not_paired, "Bose QC"),
        )
    }

    @Test
    fun `German and Japanese are chosen by the system language, and read what their files say`() = runBlocking {
        for (tag in listOf("de", "ja")) {
            val env = environment(tag)
            val file = others.getValue(tag)
            val samples = listOf(
                getString(env, Res.string.action_cancel) to fill(file, "action_cancel"),
                getString(env, Res.string.blocked_title) to fill(file, "blocked_title"),
                getString(env, Res.string.battery_percent_charging, 80) to fill(file, "battery_percent_charging", 80),
                getString(env, Res.string.problem_ios_not_paired, "Bose QC") to fill(file, "problem_ios_not_paired", "Bose QC"),
                getPluralString(env, Res.plurals.setting_auto_off_hours, 3, 3) to fillPlural(file, "setting_auto_off_hours", "other", 3),
            )
            for ((shown, expected) in samples) assertEquals(expected, shown, "with the system language $tag")
        }
    }

    @Test
    fun `every language folder is chosen for its own system language`() = runBlocking {
        for ((folder, tag) in LANGUAGES) {
            val expected = fill(others.getValue(folder), "action_cancel")
            assertEquals(expected, getString(environment(tag), Res.string.action_cancel), "with the system language $tag (values-$folder)")
        }
    }

    // ---- helpers ----

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+)\$[sd]""").findAll(text).map { it.groupValues[1] + it.value.last() }.distinct().sorted().toList()

    /** How many values a Words(...) call gives: the commas at the top level of its brackets. */
    private fun argumentCount(text: String, afterKey: Int): Int {
        var depth = 0
        var commas = 0
        var inString = false
        var i = afterKey
        while (i < text.length) {
            val ch = text[i]
            when {
                inString -> if (ch == '\\') i++ else if (ch == '"') inString = false
                ch == '"' -> inString = true
                ch == '(' || ch == '[' || ch == '{' -> depth++
                ch == ')' && depth == 0 -> return commas
                ch == ')' || ch == ']' || ch == '}' -> depth--
                ch == ',' && depth == 0 -> commas++
            }
            i++
        }
        error("unclosed Words( call")
    }

    private fun fill(file: Map<String, Entry>, key: String, vararg args: Any): String =
        args.foldIndexed(file.getValue(key).items.getValue("")) { i, text, arg -> text.replace("%${i + 1}\$s", "$arg").replace("%${i + 1}\$d", "$arg") }

    private fun fillPlural(file: Map<String, Entry>, key: String, quantity: String, vararg args: Any): String =
        args.foldIndexed(file.getValue(key).items.getValue(quantity)) { i, text, arg -> text.replace("%${i + 1}\$d", "$arg") }

    private fun kotlinSources(): List<Pair<File, String>> =
        root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", ".gradle", ".kotlin", "_private", "node_modules", "third_party") }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}" in it.path }
            .map { it to it.readText() }
            .toList()

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        const val BACKSLASH = '\\'

        /** The folder suffix of each language file, and the system language that picks it. */
        val LANGUAGES = linkedMapOf(
            "de" to "de", "fr" to "fr", "es" to "es", "it" to "it", "nl" to "nl",
            "pt-rBR" to "pt-BR", "ja" to "ja", "zh-rCN" to "zh-CN", "ko" to "ko", "pl" to "pl",
        )
    }
}
