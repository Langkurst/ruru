package io.github.langkurst.ruru.app.update

import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.app.WEBSITE_URL
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The desktop's Check for updates. Nothing here touches the network until [check] is called: [http]
 * is only asked for a client then, and the constructor does nothing but remember its arguments.
 *
 * The steps: ask GitHub for the newest release, and if it is newer than [currentVersion], download the
 * file that suits how this copy was installed ([kind]), check its SHA-256 against the release's
 * SHA256SUMS.txt, and wait in [UpdateState.Ready] until the person chooses to install.
 */
class DesktopUpdates(
    private val scope: CoroutineScope,
    private val currentVersion: String,
    private val kind: InstallKind,
    private val http: () -> UpdateHttp,
    private val openWebsite: (String) -> Boolean,
    /** Starts the installer so it carries on after Ruru has gone. */
    private val launch: (List<String>) -> Unit,
    /** Closes Ruru (and lets go of the single-instance claim) once the installer has been started. */
    private val quit: () -> Unit,
    private val workDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : Updates {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private class Pending(val version: String, val file: Path)

    private class Failure(val words: Words) : Exception(words.toString())

    @Volatile
    private var pending: Pending? = null

    override fun check() {
        synchronized(this) {
            // One job at a time. A finished download waits where it is until Install or Later.
            when (_state.value) {
                UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.Ready -> return
                else -> _state.value = UpdateState.Checking
            }
        }
        scope.launch(io) { runCheck() }
    }

    override fun installAndRestart() {
        val ready = pending ?: return
        synchronized(this) {
            if (_state.value !is UpdateState.Ready) return
            _state.value = UpdateState.Installing(ready.version)
        }
        scope.launch(io) {
            try {
                val plan = Installer.plan(kind, ready.file, workDir)
                if (plan.scriptName != null && plan.scriptText != null) {
                    Files.writeString(workDir.resolve(plan.scriptName), plan.scriptText)
                }
                log("Starting the installer: ${plan.command.joinToString(" ")}")
                launch(plan.command)
                quit()
            } catch (e: Exception) {
                log("Could not start the installer: ${e.message}")
                _state.value = UpdateState.Failed(Words("update_failed_install"))
            }
        }
    }

    override fun dismiss() {
        synchronized(this) {
            when (_state.value) {
                UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> Unit
                else -> _state.value = UpdateState.Idle
            }
        }
    }

    private fun runCheck() {
        var step = Words("update_failed_check")
        try {
            val client = http()
            val release = Release.parse(client.getText(LATEST_URL))
            log("Newest release is ${release.version}; this copy is $currentVersion")
            if (!Versions.isNewer(release.version, currentVersion)) {
                _state.value = UpdateState.UpToDate
                return
            }
            val name = kind.assetName(release.version, release.assets.map { it.name })
            if (name == null) {
                showWebsite()
                return
            }
            val asset = release.asset(name) ?: throw Failure(Words("update_failed_not_ready"))
            val sums = release.asset(SUMS) ?: throw Failure(Words("update_failed_checksum"))

            _state.value = UpdateState.Downloading(release.version, 0f)
            step = Words("update_failed_download")
            val expected = Checksums.find(client.getText(sums.url), name) ?: throw Failure(Words("update_failed_checksum"))

            Files.createDirectories(workDir)
            val target = workDir.resolve(name)
            tidy(keep = name)
            if (Files.isRegularFile(target) && sha256(target) == expected) {
                log("$name is already downloaded and matches its checksum")
            } else {
                val part = workDir.resolve("$name.part")
                var shown = -1
                val actual = try {
                    client.download(asset.url, part) { done, total ->
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release.version, percent / 100f)
                        }
                    }
                } catch (e: Exception) {
                    Files.deleteIfExists(part)
                    throw e
                }
                log("Downloaded $name, SHA-256 $actual")
                if (actual != expected) {
                    Files.deleteIfExists(part)
                    log("Checksum mismatch: the release lists $expected")
                    throw Failure(Words("update_failed_checksum"))
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            log("Checksum matches the release's SHA256SUMS.txt")
            pending = Pending(release.version, target)
            _state.value = UpdateState.Ready(release.version)
        } catch (e: Failure) {
            log("Update stopped: ${e.words}")
            _state.value = UpdateState.Failed(e.words)
        } catch (e: Exception) {
            log("Update failed: ${e.javaClass.simpleName}: ${e.message}")
            // With no installer of its own, the button still works the old way.
            if (kind == InstallKind.Other) showWebsite() else _state.value = UpdateState.Failed(step)
        }
    }

    private fun showWebsite() {
        val opened = runCatching { openWebsite("$WEBSITE_URL/?have=$currentVersion") }.getOrDefault(false)
        _state.value = if (opened) UpdateState.OpenedWebsite else UpdateState.Failed()
    }

    /** Old downloads and scripts are no use once a newer file is on the way. */
    private fun tidy(keep: String) {
        runCatching {
            Files.newDirectoryStream(workDir).use { files ->
                files.filter { it.fileName.toString() != keep }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/Langkurst/ruru/releases/latest"
        const val SUMS = "SHA256SUMS.txt"

        /**
         * The updater for this copy of Ruru. `-Druru.fakeVersion=0.1.0` pretends to be an older
         * version, to try an update without waiting for a release.
         */
        fun forThisSystem(scope: CoroutineScope, quit: () -> Unit, log: (String) -> Unit = {}): DesktopUpdates {
            val current = System.getProperty("ruru.fakeVersion")?.takeIf { it.isNotBlank() } ?: RURU_VERSION
            return DesktopUpdates(
                scope = scope,
                currentVersion = current,
                kind = InstallKind.detect(),
                http = { JdkUpdateHttp("Ruru/$RURU_VERSION") },
                openWebsite = ::openInBrowser,
                launch = ::startDetached,
                quit = quit,
                workDir = Paths.get(System.getProperty("java.io.tmpdir"), "ruru-update"),
                log = log,
            )
        }

        private fun openInBrowser(url: String): Boolean = runCatching {
            java.awt.Desktop.getDesktop().browse(URI.create(url))
            true
        }.getOrDefault(false)

        private fun startDetached(command: List<String>) {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }
}
