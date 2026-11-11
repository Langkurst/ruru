package io.github.langkurst.ruru.app

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchService
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries

/**
 * One Ruru per person on a desktop. The first copy holds a lock file in [dir]. A later copy asks
 * the first to show its window by leaving a small file beside the lock, and then closes.
 *
 * Files rather than a local network port, so nothing ever listens for connections. The system
 * drops the lock when the process ends, however it ends.
 */
class SingleInstance(private val dir: Path) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var watch: WatchService? = null

    /**
     * True when this copy is the only one running. From then on [onShow] is called, on a
     * background thread, each time another copy is started.
     */
    fun claim(onShow: () -> Unit): Boolean {
        Files.createDirectories(dir)
        val opened = FileChannel.open(dir.resolve(LOCK), CREATE, WRITE)
        val held = try {
            opened.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (held == null) {
            opened.close()
            return false
        }
        channel = opened
        lock = held
        // A request left by an earlier run is not for this copy.
        requests().forEach { it.deleteIfExists() }
        val service = dir.fileSystem.newWatchService()
        dir.register(service, ENTRY_CREATE)
        watch = service
        Thread({ listen(service, onShow) }, "ruru-single-instance").apply { isDaemon = true }.start()
        return true
    }

    /** Asks the copy that holds the lock to show its window. */
    fun askToShow() {
        Files.createDirectories(dir)
        Files.createFile(dir.resolve(REQUEST + System.nanoTime()))
    }

    /** Gives the lock up, so another copy can start. */
    fun release() {
        runCatching { watch?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        watch = null
        lock = null
        channel = null
    }

    private fun listen(service: WatchService, onShow: () -> Unit) {
        try {
            while (true) {
                val key = service.take()
                key.pollEvents()
                val asked = requests().onEach { it.deleteIfExists() }.isNotEmpty()
                if (asked) runCatching(onShow)
                if (!key.reset()) return
            }
        } catch (_: ClosedWatchServiceException) {
            // released
        } catch (_: InterruptedException) {
            // shutting down
        }
    }

    private fun requests(): List<Path> = runCatching { dir.listDirectoryEntries("$REQUEST*") }.getOrDefault(emptyList())

    companion object {
        private const val LOCK = "lock"
        private const val REQUEST = "show-"

        /**
         * A folder only this person's copies use. The temporary folder is already per person on
         * Windows and macOS; on Linux it is shared, so the folder carries their sign-in name.
         */
        fun defaultDir(): Path {
            val who = System.getProperty("user.name").orEmpty().filter { it.isLetterOrDigit() }.ifEmpty { "user" }
            return Path.of(System.getProperty("java.io.tmpdir"), "ruru-$who")
        }
    }
}
