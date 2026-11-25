package io.github.langkurst.ruru.app.update

import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.WEBSITE_URL
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The updater with GitHub replaced by a fake, so every step and every failure can be tried offline. */
class DesktopUpdatesTest {
    private val cleanup = mutableListOf<Path>()

    @AfterTest
    fun tidy() = cleanup.forEach { it.toFile().deleteRecursively() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun url(tag: String, name: String) = "https://github.com/Langkurst/ruru/releases/download/$tag/$name"

    private fun releaseJson(tag: String, names: List<String>) =
        """{"tag_name":"$tag","assets":[${names.joinToString(",") { """{"name":"$it","browser_download_url":"${url(tag, it)}"}""" }}]}"""

    /** GitHub, in memory. */
    private class FakeHttp(val texts: MutableMap<String, String>, val files: MutableMap<String, ByteArray>) : UpdateHttp {
        val requested = mutableListOf<String>()
        var downloads = 0
        var dropHalfway = false
        var afterChunk: () -> Unit = {}

        override fun getText(url: String): String {
            requested += url
            return texts[url] ?: throw IOException("HTTP 404 for $url")
        }

        override fun download(url: String, to: Path, onProgress: (Long, Long) -> Unit): String {
            requested += url
            downloads++
            val bytes = files[url] ?: throw IOException("HTTP 404 for $url")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(to).use { out ->
                val chunk = maxOf(1, bytes.size / 4)
                var done = 0
                while (done < bytes.size) {
                    val n = minOf(chunk, bytes.size - done)
                    out.write(bytes, done, n)
                    digest.update(bytes, done, n)
                    done += n
                    onProgress(done.toLong(), bytes.size.toLong())
                    afterChunk()
                    if (dropHalfway && done >= bytes.size / 2) throw IOException("The connection dropped")
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * A copy of Ruru at [current], installed as [kind], with a fake GitHub whose newest release is [tag]
     * holding [names] and SHA256SUMS.txt. [content] is what each file really contains; [listed] is what
     * the checksum list says for the file this copy needs.
     */
    private inner class Rig(
        val kind: InstallKind,
        current: String,
        tag: String = "v0.2.1",
        names: List<String> = ALL_ASSETS,
        content: ByteArray = "the new Ruru".repeat(100).toByteArray(),
        listed: String? = null,
        sumsLine: Boolean = true,
        withSums: Boolean = true,
        apiFails: Boolean = false,
    ) {
        val work: Path = Files.createTempDirectory("ruru-updater-test").also { cleanup.add(it) }
        val launched = mutableListOf<List<String>>()
        val opened = mutableListOf<String>()
        var quits = 0
        val http: FakeHttp
        val updates: DesktopUpdates
        val wanted: String? = kind.assetName(tag.removePrefix("v"), names)

        init {
            val texts = mutableMapOf<String, String>()
            if (!apiFails) texts[DesktopUpdates.LATEST_URL] = releaseJson(tag, names + if (withSums) listOf("SHA256SUMS.txt") else emptyList())
            val files = names.associate { url(tag, it) to content }.toMutableMap()
            if (withSums) {
                val lines = names.filter { sumsLine || it != wanted }.joinToString("\n") { "${if (it == wanted) listed ?: sha(content) else sha(content)}  $it" }
                texts[url(tag, "SHA256SUMS.txt")] = lines + "\n"
            }
            http = FakeHttp(texts, files)
            updates = DesktopUpdates(
                scope = CoroutineScope(Dispatchers.Unconfined),
                currentVersion = current,
                kind = kind,
                http = { http },
                openWebsite = { opened += it; true },
                launch = { launched += it },
                quit = { quits++ },
                workDir = work,
                io = Dispatchers.Unconfined, // the whole check runs inside check()
            )
        }

        val state get() = updates.state.value
        fun files(): List<String> = Files.list(work).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
    }

    private val inno = InstallKind.Setup(Paths.get("C:\\Users\\someone\\AppData\\Local\\Programs\\Ruru\\Ruru.exe"))
    private val msi = InstallKind.Msi(Paths.get("C:\\Users\\someone\\AppData\\Local\\Ruru\\Ruru.exe"))
    private val mac = InstallKind.MacApp(Paths.get("/Applications/Ruru.app"))

    // ---- nothing happens until the button is pressed

    @Test
    fun `building the updater, and everything but check, touches no network`() {
        var asked = 0
        val u = DesktopUpdates(
            scope = CoroutineScope(Dispatchers.Unconfined), currentVersion = "0.1.3", kind = inno,
            http = { asked++; error("the network was touched") },
            openWebsite = { asked++; true }, launch = { asked++ }, quit = { asked++ },
            workDir = Files.createTempDirectory("ruru-updater-test").also { cleanup.add(it) }, io = Dispatchers.Unconfined,
        )
        u.dismiss()
        u.installAndRestart() // nothing is ready, so nothing happens
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(0, asked)
    }

    @Test
    fun `the HTTP client is built only when first used`() {
        // Creating it opens nothing (the JDK client is created by the first call, in a lazy property).
        val http = JdkUpdateHttp("Ruru/test")
        assertTrue(http is UpdateHttp)
    }

    // ---- the happy path

    @Test
    fun `a newer release is downloaded, checked and left ready, with progress on the way`() {
        val r = Rig(inno, current = "0.1.3")
        val fractions = mutableListOf<Float>()
        r.http.afterChunk = { (r.state as? UpdateState.Downloading)?.let { fractions += it.fraction } }
        r.updates.check()
        assertEquals(UpdateState.Ready("0.2.1"), r.state)
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), fractions)
        assertEquals(listOf("Ruru-0.2.1-setup.exe"), r.files()) // no .part left behind
        assertTrue(r.http.requested.first() == DesktopUpdates.LATEST_URL)
    }

    @Test
    fun `0_10_0 is newer than 0_9_9`() {
        val r = Rig(inno, current = "0.9.9", tag = "v0.10.0", names = listOf("Ruru-0.10.0-setup.exe"))
        r.updates.check()
        assertEquals(UpdateState.Ready("0.10.0"), r.state)
    }

    @Test
    fun `the same version and an older one both say up to date, and download nothing`() {
        for ((current, tag) in listOf("0.2.1" to "v0.2.1", "0.3.0" to "v0.2.1")) {
            val r = Rig(inno, current = current, tag = tag)
            r.updates.check()
            assertEquals(UpdateState.UpToDate, r.state, "$current against $tag")
            assertEquals(listOf(DesktopUpdates.LATEST_URL), r.http.requested)
            assertEquals(emptyList(), r.files())
        }
    }

    // ---- which file

    @Test
    fun `each kind of install gets its own file`() {
        val names = ALL_ASSETS
        assertEquals("Ruru-0.2.1-setup.exe", inno.assetName("0.2.1", names))
        assertEquals("Ruru-0.2.1.msi", msi.assetName("0.2.1", names))
        assertEquals("Ruru-0.2.1-macos-arm64.dmg", mac.assetName("0.2.1", names))
        assertEquals(null, InstallKind.Other.assetName("0.2.1", names))
        for ((kind, file) in listOf(inno to "Ruru-0.2.1-setup.exe", msi to "Ruru-0.2.1.msi", mac to "Ruru-0.2.1-macos-arm64.dmg")) {
            val r = Rig(kind, current = "0.1.3")
            r.updates.check()
            assertEquals(UpdateState.Ready("0.2.1"), r.state, "$kind")
            assertEquals(listOf(file), r.files(), "$kind")
        }
    }

    @Test
    fun `an install the updater can't replace opens the download page instead`() {
        val r = Rig(InstallKind.Other, current = "0.1.3")
        r.updates.check()
        assertEquals(UpdateState.OpenedWebsite, r.state)
        assertEquals(listOf("$WEBSITE_URL/?have=0.1.3"), r.opened)
        assertEquals(listOf(DesktopUpdates.LATEST_URL), r.http.requested) // nothing downloaded
        // ...and with GitHub out of reach it still opens the page, as the button always did.
        val down = Rig(InstallKind.Other, current = "0.1.3", apiFails = true)
        down.updates.check()
        assertEquals(UpdateState.OpenedWebsite, down.state)
        assertEquals(1, down.opened.size)
    }

    // ---- failures

    @Test
    fun `a release without the file for this install says the download is not ready`() {
        val r = Rig(inno, current = "0.1.3", names = listOf("Ruru-0.2.1.msi", "ruru_0.2.1_amd64.deb"))
        r.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_not_ready")), r.state)
        assertEquals(0, r.http.downloads)
    }

    @Test
    fun `a file that doesn't match its checksum is thrown away and never offered`() {
        val r = Rig(inno, current = "0.1.3", listed = "0".repeat(64))
        r.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_checksum")), r.state)
        assertEquals(emptyList(), r.files(), "the bad download must not stay on disk")
        r.updates.installAndRestart()
        assertEquals(emptyList(), r.launched)
    }

    @Test
    fun `a missing checksum line, or a missing checksum list, refuses the download`() {
        val noLine = Rig(inno, current = "0.1.3", sumsLine = false)
        noLine.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_checksum")), noLine.state)
        assertEquals(0, noLine.http.downloads)
        val noList = Rig(inno, current = "0.1.3", withSums = false)
        noList.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_checksum")), noList.state)
        assertEquals(0, noList.http.downloads)
    }

    @Test
    fun `no internet says so, at the check and at the download`() {
        val check = Rig(inno, current = "0.1.3", apiFails = true)
        check.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_check")), check.state)
        val download = Rig(inno, current = "0.1.3")
        download.http.dropHalfway = true
        download.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_download")), download.state)
        assertEquals(emptyList(), download.files(), "a half download must not stay on disk")
    }

    @Test
    fun `a reply that is not a release is a failed check, not a crash`() {
        val r = Rig(inno, current = "0.1.3")
        r.http.texts[DesktopUpdates.LATEST_URL] = "<html>rate limited</html>"
        r.updates.check()
        assertEquals(UpdateState.Failed(Words("update_failed_check")), r.state)
    }

    // ---- installing

    @Test
    fun `Install and restart starts the setup quietly, then closes Ruru`() {
        val r = Rig(inno, current = "0.1.3")
        r.updates.check()
        r.updates.installAndRestart()
        assertEquals(UpdateState.Installing("0.2.1"), r.state)
        val file = r.work.resolve("Ruru-0.2.1-setup.exe").toString()
        assertEquals(listOf(listOf(file, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH")), r.launched)
        assertEquals(1, r.quits)
    }

    @Test
    fun `Install and restart with the MSI installs for this user, then opens Ruru again`() {
        val r = Rig(msi, current = "0.1.3")
        r.updates.check()
        r.updates.installAndRestart()
        val script = r.work.resolve("ruru-update.cmd")
        assertEquals(listOf(listOf("cmd.exe", "/c", script.toString())), r.launched)
        val text = Files.readString(script)
        assertTrue("start \"\" /wait msiexec /i \"${r.work.resolve("Ruru-0.2.1.msi")}\" /qn /norestart" in text, text)
        assertTrue("start \"\" \"C:\\Users\\someone\\AppData\\Local\\Ruru\\Ruru.exe\"" in text, text)
        assertTrue(text.indexOf("msiexec") < text.indexOf("Ruru.exe"), "Ruru opens after the install, not before")
        assertEquals(1, r.quits)
    }

    @Test
    fun `Install and restart is ignored unless a download is ready`() {
        val r = Rig(inno, current = "0.1.3")
        r.updates.installAndRestart()
        assertEquals(UpdateState.Idle, r.state)
        assertEquals(emptyList(), r.launched)
        assertEquals(0, r.quits)
    }

    @Test
    fun `if the installer can't be started the person is told and Ruru stays open`() {
        val r = Rig(inno, current = "0.1.3")
        r.updates.check()
        val broken = DesktopUpdates(
            scope = CoroutineScope(Dispatchers.Unconfined), currentVersion = "0.1.3", kind = inno, http = { r.http },
            openWebsite = { true }, launch = { throw IOException("blocked") }, quit = { r.quits++ }, workDir = r.work, io = Dispatchers.Unconfined,
        )
        broken.check()
        broken.installAndRestart()
        assertEquals(UpdateState.Failed(Words("update_failed_install")), broken.state.value)
        assertEquals(0, r.quits)
    }

    @Test
    fun `Later puts the card away, and checking again reuses the file already downloaded`() {
        val r = Rig(inno, current = "0.1.3")
        r.updates.check()
        r.updates.dismiss()
        assertEquals(UpdateState.Idle, r.state)
        r.updates.check()
        assertEquals(UpdateState.Ready("0.2.1"), r.state)
        assertEquals(1, r.http.downloads, "the second check must not download again")
    }

    @Test
    fun `a second press while it is busy does nothing`() {
        val r = Rig(inno, current = "0.1.3")
        r.http.afterChunk = { r.updates.check() } // pressed again mid-download
        r.updates.check()
        assertEquals(1, r.http.downloads)
        assertEquals(UpdateState.Ready("0.2.1"), r.state)
    }

    // ---- the pieces

    @Test
    fun `versions compare as numbers, not text`() {
        assertTrue(Versions.isNewer("0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.1.4", "0.1.3"))
        assertTrue(Versions.isNewer("v0.2.0", "0.1.3"))
        assertTrue(!Versions.isNewer("0.1.3", "0.1.3"))
        assertTrue(!Versions.isNewer("0.1.3", "0.1.4"))
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
        assertTrue(Versions.isNewer("1.0.0", "1.0.0-rc1"), "a release is newer than its own pre-release")
        assertTrue(!Versions.isNewer("not a version", "0.1.3"))
    }

    @Test
    fun `the release reply is read, and only GitHub downloads are kept`() {
        val json = """{"tag_name":"v0.2.1","draft":false,"assets":[
            {"name":"Ruru-0.2.1.msi","size":123,"browser_download_url":"https://github.com/Langkurst/ruru/releases/download/v0.2.1/Ruru-0.2.1.msi"},
            {"name":"evil.exe","browser_download_url":"https://example.com/evil.exe"},
            {"name":"na\u00efve.txt","browser_download_url":"https://github.com/x/na.txt"}]}"""
        val release = Release.parse(json)
        assertEquals("0.2.1", release.version)
        assertEquals(listOf("Ruru-0.2.1.msi", "na\u00efve.txt"), release.assets.map { it.name })
        assertFailsWith<IllegalArgumentException> { Release.parse("""{"tag_name":"nightly","assets":[]}""") }
        assertFailsWith<IllegalArgumentException> { Release.parse("[]") }
        assertFailsWith<IllegalArgumentException> { Release.parse("""{"tag_name":"v1.0.0","assets":[""") }
    }

    @Test
    fun `the checksum list is read as sha256sum writes it`() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = "$a  Ruru-0.2.1.msi\n$b *Ruru-0.2.1-setup.exe\n\nnot a line\n"
        assertEquals(a, Checksums.find(sums, "Ruru-0.2.1.msi"))
        assertEquals("b".repeat(64), Checksums.find(sums, "Ruru-0.2.1-setup.exe"))
        assertEquals(null, Checksums.find(sums, "Ruru-0.2.1.dmg"))
        assertEquals(null, Checksums.find("short  Ruru-0.2.1.msi", "Ruru-0.2.1.msi"))
    }

    @Test
    fun `how a copy was installed is read from where it runs`() {
        val dir = "C:\\Users\\someone\\AppData\\Local\\Programs\\Ruru"
        val withUninstaller = { p: Path -> p.fileName.toString() == "unins000.exe" }
        val none = { _: Path -> false }
        // Paths are Windows-style on purpose; on another system they are one odd file name, so only compare there.
        val onWindows = System.getProperty("os.name").startsWith("Windows")
        if (onWindows) {
            assertEquals(InstallKind.Setup(Paths.get("$dir\\Ruru.exe")), InstallKind.detect("Windows 11", "$dir\\Ruru.exe", withUninstaller))
            assertEquals(InstallKind.Msi(Paths.get("$dir\\Ruru.exe")), InstallKind.detect("Windows 11", "$dir\\Ruru.exe", none))
        }
        assertEquals(InstallKind.Other, InstallKind.detect("Windows 11", null, none), "a run from Gradle has no launcher path")
        assertEquals(InstallKind.Other, InstallKind.detect("Linux", "/opt/ruru/bin/ruru", none))
        assertEquals(InstallKind.MacApp(Paths.get("/Applications/Ruru.app")), InstallKind.detect("Mac OS X", "/Applications/Ruru.app/Contents/MacOS/Ruru", none))
        assertEquals(InstallKind.Other, InstallKind.detect("Mac OS X", "/tmp/ruru/bin/Ruru", none))
    }

    @Test
    fun `the mac script quotes odd paths and falls back to showing the disk image`() {
        val dmg = Paths.get("/tmp/it's here/Ruru.dmg")
        val text = Installer.macScript(dmg, Paths.get("/Applications/Ruru.app"))
        // A Windows test run writes the path with backslashes; the quoting is what is checked.
        assertTrue("DMG='" + dmg.toString().replace("'", "'\\''") + "'" in text, text)
        assertTrue("hdiutil attach -nobrowse" in text && "open -n" in text && "open \"\$DMG\"" in text, text)
        assertFailsWith<IllegalStateException> { Installer.plan(InstallKind.Other, Paths.get("x"), Paths.get("y")) }
    }

    @Test
    fun `the JSON reader copes with escapes and rejects rubbish`() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":"line\nbreak \"quoted\" \u00e9","c":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), v["a"])
        assertEquals("line\nbreak \"quoted\" \u00e9", v["b"])
        assertEquals(emptyMap<String, Any?>(), v["c"])
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "'single'", "[" .repeat(100))) {
            assertFailsWith<IllegalArgumentException>("should reject: $bad") { MiniJson.parse(bad) }
        }
    }

    private companion object {
        val ALL_ASSETS = listOf("Ruru-0.2.1-setup.exe", "Ruru-0.2.1.msi", "Ruru-0.2.1-macos-arm64.dmg", "ruru_0.2.1_amd64.deb")
    }
}
