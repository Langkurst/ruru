package io.github.langkurst.ruru.app

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    private val dir = Files.createTempDirectory("ruru-single-instance-test")

    @Test
    fun `the first copy claims the lock and a second copy does not`() {
        val first = SingleInstance(dir)
        val second = SingleInstance(dir)
        try {
            assertTrue(first.claim {}, "the first copy should be the only one")
            assertFalse(second.claim {}, "a second copy should be told another is running")
        } finally {
            second.release()
            first.release()
        }
    }

    @Test
    fun `a second copy asking to be shown reaches the first`() {
        val first = SingleInstance(dir)
        val shown = CountDownLatch(1)
        try {
            assertTrue(first.claim { shown.countDown() })
            SingleInstance(dir).askToShow()
            assertTrue(shown.await(15, TimeUnit.SECONDS), "the first copy was not asked to show its window")
        } finally {
            first.release()
        }
    }

    @Test
    fun `every later launch is answered, not only the first`() {
        val first = SingleInstance(dir)
        val shown = CountDownLatch(2)
        try {
            assertTrue(first.claim { shown.countDown() })
            SingleInstance(dir).askToShow()
            Thread.sleep(400)
            SingleInstance(dir).askToShow()
            assertTrue(shown.await(15, TimeUnit.SECONDS), "only ${2 - shown.count} of 2 launches were answered")
        } finally {
            first.release()
        }
    }

    @Test
    fun `once the first copy has gone another can start`() {
        val first = SingleInstance(dir)
        assertTrue(first.claim {})
        first.release()
        val next = SingleInstance(dir)
        try {
            assertTrue(next.claim {}, "the lock should be free after the first copy let it go")
        } finally {
            next.release()
        }
    }

    @Test
    fun `a request left behind before the start is cleared and not acted on`() {
        SingleInstance(dir).askToShow()
        val shown = CountDownLatch(1)
        val first = SingleInstance(dir)
        try {
            assertTrue(first.claim { shown.countDown() })
            assertFalse(shown.await(800, TimeUnit.MILLISECONDS), "a stale request opened the window")
            assertEquals(listOf("lock"), dir.listDirectoryEntries().map { it.name })
        } finally {
            first.release()
        }
    }
}
