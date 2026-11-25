package io.github.langkurst.ruru.vendors.bragi

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Keys in memory only: a pairing lasts until the app closes. What a build gets until its app
 * installs a [FileBragiTrustStore] (or its own, backed by the system's key store) in [BragiSecurity].
 */
class MemoryBragiTrustStore : BragiTrustStore {
    private val id by lazy { BragiHandshake.generateIdentity() }
    private val servers = ArrayList<ByteArray>()

    @Synchronized override fun identity(): BragiHandshake.EcKeyPair = id

    @Synchronized override fun trustedServer(keyId: ByteArray): ByteArray? =
        servers.firstOrNull { BragiHandshake.identityKeyId(it).contentEquals(keyId) }

    @Synchronized override fun trust(serverPoint: ByteArray) {
        if (servers.none { it.contentEquals(serverPoint) }) servers += serverPoint
    }

    @Synchronized override fun forgetAll() { servers.clear() }
}

/**
 * The keys in one small text file (Base64 lines), for the desktop apps: the identity key pair this
 * app made for itself and the headphones' public identity keys it trusts. The file holds a private
 * key, so it is created readable by its owner only; it never leaves the computer. Servers are found
 * by key id, so no Bluetooth address is stored. A file that can't be read or parsed is treated as
 * empty and replaced by a new identity, which only means pairing again.
 */
class FileBragiTrustStore(private val file: File) : BragiTrustStore {
    private var identity: BragiHandshake.EcKeyPair? = null
    private val servers = ArrayList<ByteArray>()
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile) return
            var priv: ByteArray? = null
            var pub: ByteArray? = null
            for (line in file.readLines()) {
                val key = line.substringBefore('=', "")
                val value = line.substringAfter('=', "")
                if (value.isEmpty()) continue
                val bytes = Base64.getDecoder().decode(value)
                when (key) {
                    PRIVATE -> priv = bytes
                    PUBLIC -> pub = bytes
                    SERVER -> servers += bytes
                }
            }
            if (priv != null && pub != null) identity = BragiHandshake.EcKeyPair.restore(priv, pub)
            servers.removeAll { runCatching { BragiHandshake.decodePublicKey(it) }.isFailure }
            // Servers trusted by an identity that is gone can't authenticate: drop them with it.
            if (identity == null) servers.clear()
        } catch (e: Exception) {
            BragiLog.w("the trust file could not be read (${e.javaClass.simpleName}); starting with a new identity")
            identity = null
            servers.clear()
        }
    }

    @Synchronized override fun identity(): BragiHandshake.EcKeyPair {
        load()
        identity?.let { return it }
        val fresh = BragiHandshake.generateIdentity()
        identity = fresh
        servers.clear()
        save()
        return fresh
    }

    @Synchronized override fun trustedServer(keyId: ByteArray): ByteArray? {
        load()
        return servers.firstOrNull { BragiHandshake.identityKeyId(it).contentEquals(keyId) }
    }

    @Synchronized override fun trust(serverPoint: ByteArray) {
        load()
        identity()
        if (servers.none { it.contentEquals(serverPoint) }) servers += serverPoint
        save()
    }

    @Synchronized override fun forgetAll() {
        load()
        servers.clear()
        if (identity != null) save()
    }

    private fun save() {
        val id = identity ?: return
        val enc = Base64.getEncoder()
        val text = buildString {
            append(PRIVATE).append('=').append(enc.encodeToString(id.privateKeyPkcs8())).append('\n')
            append(PUBLIC).append('=').append(enc.encodeToString(id.publicPoint)).append('\n')
            for (s in servers) append(SERVER).append('=').append(enc.encodeToString(s)).append('\n')
        }
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        temp.writeText(text)
        // Owner only, where the file system has such a thing (Windows keeps the user's profile folder private anyway).
        temp.setReadable(false, false); temp.setReadable(true, true)
        temp.setWritable(false, false); temp.setWritable(true, true)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val PRIVATE = "identity_private"
        const val PUBLIC = "identity_public"
        const val SERVER = "server"
    }
}

/**
 * The two things a secure session needs from the app around it: where the keys live and who
 * confirms the pairing passcode. Both default to the safe choice (keys in memory, pairing declined),
 * so a build that never installs a confirmation screen never pairs anything: it carries on without
 * the secure session, as the official app does when the session fails.
 */
object BragiSecurity {
    @Volatile var trustStore: BragiTrustStore = MemoryBragiTrustStore()

    @Volatile var pairingPrompt: PairingPrompt = PairingPrompt.DECLINE
}
