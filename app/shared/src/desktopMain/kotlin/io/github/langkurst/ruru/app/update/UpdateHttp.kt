package io.github.langkurst.ruru.app.update

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong

/** The two network calls the updater makes, so tests can stand in for GitHub. */
interface UpdateHttp {
    /** The body of [url] as text. Throws [IOException] on any failure, including a status other than 200. */
    fun getText(url: String): String

    /**
     * Saves [url] to [to], calling [onProgress] with the bytes so far and the total (or -1 when not
     * known). Returns the SHA-256 of what was saved, as lower-case hex.
     */
    fun download(url: String, to: Path, onProgress: (done: Long, total: Long) -> Unit): String
}

/**
 * GitHub over the JDK's own HTTP client; nothing else is used. The client is built the first time
 * it is needed, so creating this object opens nothing.
 */
class JdkUpdateHttp(private val userAgent: String) : UpdateHttp {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .timeout(TIMEOUT)
        .header("User-Agent", userAgent)
        .header("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
        .GET()
        .build()

    override fun getText(url: String): String {
        val response = send(request(url))
        response.body().use { body ->
            if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for $url")
            val bytes = body.readNBytes(MAX_TEXT_BYTES + 1)
            if (bytes.size > MAX_TEXT_BYTES) throw IOException("The reply from $url is too large")
            return String(bytes, Charsets.UTF_8)
        }
    }

    override fun download(url: String, to: Path, onProgress: (done: Long, total: Long) -> Unit): String {
        val response = send(request(url))
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
        val digest = MessageDigest.getInstance("SHA-256")
        val lastRead = AtomicLong(System.nanoTime())
        val input = response.body()
        // The request timeout covers only the wait for the first reply. If the connection then
        // goes quiet, a read would wait for ever, so a watchdog closes the stream after a stall.
        val watchdog = Timer("ruru-update-watchdog", true)
        watchdog.schedule(object : TimerTask() {
            override fun run() {
                if (System.nanoTime() - lastRead.get() > STALL.toNanos()) runCatching { input.close() }
            }
        }, STALL.toMillis() / 4, STALL.toMillis() / 4)
        try {
            DigestInputStream(input, digest).use { stream ->
                Files.newOutputStream(to, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        lastRead.set(System.nanoTime())
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    if (total >= 0 && done != total) throw IOException("The download stopped early ($done of $total bytes)")
                }
            }
        } finally {
            watchdog.cancel()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun send(request: HttpRequest): HttpResponse<java.io.InputStream> = try {
        client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted", e)
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        val STALL: Duration = Duration.ofSeconds(20)
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    }
}
