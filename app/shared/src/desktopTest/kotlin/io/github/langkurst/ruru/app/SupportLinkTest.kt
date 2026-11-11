package io.github.langkurst.ruru.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Support link lives in three places; this keeps them on the same page. */
class SupportLinkTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the app, the README and GitHub's Sponsor button point at the same Ko-fi page`() {
        assertTrue(SUPPORT_URL.startsWith("https://ko-fi.com/"), SUPPORT_URL)
        val page = SUPPORT_URL.removePrefix("https://ko-fi.com/")
        assertTrue(SUPPORT_URL in File(root, "README.md").readText(), "README.md does not link $SUPPORT_URL")
        val funding = File(root, ".github/FUNDING.yml").readLines().map { it.trim() }.firstOrNull { it.startsWith("ko_fi:") }
        assertEquals("ko_fi: $page", funding)
    }
}
