package io.github.langkurst.ruru.vendors.bragi

import java.util.Base64

/**
 * The Skullcandy pairing's keys in a [BragiKeyStorage] (the app's settings store): the identity key pair this
 * app made for itself, and the public identity keys of the headphones it trusts, each found by the key id
 * the headphones announce (the first eight bytes of its hash, in hex), never by Bluetooth address. The
 * settings backup carries an allow-list of keys and none of these. A value that can't be read is treated as
 * missing and replaced by a new identity, which only means pairing again.
 */
class StoredBragiTrustStore(private val storage: BragiKeyStorage) : BragiTrustStore {
    private var identity: BragiHandshake.EcKeyPair? = null

    @Synchronized override fun identity(): BragiHandshake.EcKeyPair {
        identity?.let { return it }
        val restored = runCatching {
            val priv = storage.get(PRIVATE)?.let { dec(it) }
            val pub = storage.get(PUBLIC)?.let { dec(it) }
            if (priv != null && pub != null) BragiHandshake.EcKeyPair.restore(priv, pub) else null
        }.getOrNull()
        if (restored != null) return restored.also { identity = it }
        BragiLog.w("no usable pairing identity stored; making a new one")
        // Servers trusted by an identity that is gone can't authenticate: drop them with it.
        forgetServers()
        val fresh = BragiHandshake.generateIdentity()
        storage.put(PRIVATE, enc(fresh.privateKeyPkcs8()))
        storage.put(PUBLIC, enc(fresh.publicPoint))
        identity = fresh
        return fresh
    }

    @Synchronized override fun trustedServer(keyId: ByteArray): ByteArray? {
        identity()
        val point = runCatching { storage.get(SERVER + hex(keyId))?.let { dec(it) } }.getOrNull() ?: return null
        // A stored value is used only if it really is the key the id names.
        return point.takeIf { runCatching { BragiHandshake.decodePublicKey(it); BragiHandshake.identityKeyId(it).contentEquals(keyId) }.getOrDefault(false) }
    }

    @Synchronized override fun trust(serverPoint: ByteArray) {
        identity()
        val id = hex(BragiHandshake.identityKeyId(serverPoint))
        storage.put(SERVER + id, enc(serverPoint))
        val ids = index()
        if (id !in ids) storage.put(INDEX, (ids + id).joinToString(","))
    }

    @Synchronized override fun forgetAll() = forgetServers()

    private fun forgetServers() {
        index().forEach { storage.remove(SERVER + it) }
        storage.remove(INDEX)
    }

    private fun index(): List<String> = storage.get(INDEX)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private fun enc(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun dec(text: String): ByteArray = Base64.getDecoder().decode(text)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val PRIVATE = "bragi.identity.private"
        const val PUBLIC = "bragi.identity.public"
        const val INDEX = "bragi.servers"
        const val SERVER = "bragi.server."
    }
}
