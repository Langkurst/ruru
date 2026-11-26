package io.github.langkurst.ruru.app.update

import io.github.langkurst.ruru.app.WEBSITE_URL
import io.github.langkurst.ruru.app.WebsiteUpdates
import io.github.langkurst.ruru.app.UpdateState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ruru reaches the internet only through the Check for updates button. These checks keep it that way:
 * the network code lives in one file, the apps never call it at start-up, and the Android app still
 * has no internet permission.
 */
class NetworkGuardTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sources(): List<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".git", ".gradle", ".kotlin", "_private", "node_modules", "third_party", "spikes", "tools") }
        .filter { it.isFile && it.extension in setOf("kt", "swift", "java") && "${File.separator}src${File.separator}" in it.path }
        .filterNot { "${File.separator}test${File.separator}" in it.path || "Test${File.separator}" in it.path.substringAfter("src${File.separator}") }
        .toList()

    @Test
    fun `only the updater's HTTP file uses the network`() {
        val pattern = Regex("""java\.net\.http|HttpURLConnection|HttpsURLConnection|java\.net\.Socket|java\.net\.URL\b|openConnection\(|\.openStream\(|URLSession|NSURLSession|okhttp|ktor""")
        val offenders = sources().filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("UpdateHttp.kt"), offenders, "network code must stay in UpdateHttp.kt, which only the Check for updates button reaches")
    }

    @Test
    fun `the desktop app builds the updater but never presses its button`() {
        val main = sources().single { it.name == "Main.kt" && "desktop" in it.path }.readText()
        assertTrue("DesktopUpdates.forThisSystem" in main, "Main.kt should build the updater")
        assertTrue(!Regex("""updates\.check\(|\.check\(\)""").containsMatchIn(main), "nothing may check for updates at start-up")
        // Only the screen's button calls check().
        val callers = sources().filter { Regex("""\.check\(\)|::check\b""").containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("Desk.kt", "RuruScreen.kt", "UpdateCard.kt", "UpdateCheck.kt"), callers)
    }

    @Test
    fun `the Android app still asks for no internet permission`() {
        val manifest = File(root, "app/android/src/main/AndroidManifest.xml").readText()
        assertTrue("android.permission.INTERNET" !in manifest.lines().filterNot { it.trim().startsWith("<!--") }.joinToString("\n"))
    }

    /**
     * The manifest in the source can stay clean while a library adds a permission when the APK is
     * merged, so a built APK is checked too, with the SDK's `aapt2 dump permissions`. It runs when an
     * APK and the SDK are both there (a build of the Android app came first); otherwise there is
     * nothing to check and the test passes on the manifest test above.
     */
    @Test
    fun `a built Android APK declares no internet permission`() {
        val apks = File(root, "app/android/build/outputs/apk").walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        val sdk = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).map(::File).firstOrNull { it.isDirectory }
        val tool = sdk?.let { File(it, "build-tools").listFiles()?.sortedDescending() }.orEmpty().asSequence()
            .flatMap { dir -> sequenceOf("aapt2.exe", "aapt2", "aapt.exe", "aapt").map { File(dir, it) } }
            .firstOrNull { it.isFile }
        if (apks.isEmpty() || tool == null) return
        for (apk in apks) {
            val process = ProcessBuilder(tool.path, "dump", "permissions", apk.path).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), "aapt could not read ${apk.name}: $out")
            assertTrue("uses-permission" in out, "aapt printed no permissions for ${apk.name}: $out")
            assertTrue("android.permission.INTERNET" !in out, "${apk.name} asks for the internet permission:\n$out")
        }
    }

    @Test
    fun `on phones the button opens the website and nothing is opened before it is pressed`() {
        val opened = mutableListOf<String>()
        val updates = WebsiteUpdates("0.1.3") { opened += it; true }
        assertEquals(emptyList(), opened)
        updates.check()
        assertEquals(listOf("$WEBSITE_URL/?have=0.1.3"), opened)
        assertEquals(UpdateState.OpenedWebsite, updates.state.value)
        updates.dismiss()
        assertEquals(UpdateState.Idle, updates.state.value)
        // With no browser to hand the card stays away, as the button did before.
        val none = WebsiteUpdates("0.1.3") { false }
        none.check()
        assertEquals(UpdateState.Idle, none.state.value)
    }
}
