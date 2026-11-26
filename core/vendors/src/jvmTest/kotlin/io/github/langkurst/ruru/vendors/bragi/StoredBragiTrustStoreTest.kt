package io.github.langkurst.ruru.vendors.bragi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pairing keys kept in the app's settings: found by key id, kept across a restart, never by address. */
class StoredBragiTrustStoreTest {
    private val server1 = BragiHandshake.generateIdentity().publicPoint
    private val server2 = BragiHandshake.generateIdentity().publicPoint

    @Test fun `keys survive a restart and the identity still signs`() {
        val storage = MapBragiKeyStorage()
        val a = StoredBragiTrustStore(storage)
        val identity = a.identity().publicPoint
        a.trust(server1)
        a.trust(server2)
        a.trust(server1)
        val b = StoredBragiTrustStore(storage) // a new run of the app
        assertContentEquals(identity, b.identity().publicPoint)
        assertContentEquals(server1, b.trustedServer(BragiHandshake.identityKeyId(server1)))
        assertContentEquals(server2, b.trustedServer(BragiHandshake.identityKeyId(server2)))
        val ephemeral = ByteArray(65) { 4 }
        val random = ByteArray(16)
        val signature = BragiHandshake.signAuthenticate(b.identity().privateKey, ephemeral, random)
        assertTrue(BragiHandshake.verifyAuthenticate(BragiHandshake.decodePublicKey(identity), ephemeral, random, signature))
    }

    @Test fun `a server is found only by its own key id`() {
        val s = StoredBragiTrustStore(MapBragiKeyStorage())
        s.trust(server1)
        assertNull(s.trustedServer(BragiHandshake.identityKeyId(server2)))
        assertNull(s.trustedServer(ByteArray(8)))
    }

    @Test fun `forgetting removes every server and keeps the identity`() {
        val storage = MapBragiKeyStorage()
        val s = StoredBragiTrustStore(storage)
        val identity = s.identity().publicPoint
        s.trust(server1)
        s.trust(server2)
        s.forgetAll()
        assertNull(StoredBragiTrustStore(storage).trustedServer(BragiHandshake.identityKeyId(server1)))
        assertNull(StoredBragiTrustStore(storage).trustedServer(BragiHandshake.identityKeyId(server2)))
        assertContentEquals(identity, StoredBragiTrustStore(storage).identity().publicPoint)
        assertTrue(storage.values.keys.none { it.startsWith(StoredBragiTrustStore.SERVER) })
    }

    @Test fun `a garbled identity becomes a new one and drops the servers it had trusted`() {
        val storage = MapBragiKeyStorage()
        val s = StoredBragiTrustStore(storage)
        val old = s.identity().publicPoint
        s.trust(server1)
        storage.put(StoredBragiTrustStore.PRIVATE, "not base64 at all!")
        val after = StoredBragiTrustStore(storage)
        assertFalse(after.identity().publicPoint.contentEquals(old))
        assertNull(after.trustedServer(BragiHandshake.identityKeyId(server1)))
    }

    @Test fun `a stored server whose key does not match its id is ignored`() {
        val storage = MapBragiKeyStorage()
        val s = StoredBragiTrustStore(storage)
        s.trust(server1)
        val id1 = BragiHandshake.identityKeyId(server1).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Someone swapped the stored key for another one under the first one's id.
        storage.put(StoredBragiTrustStore.SERVER + id1, java.util.Base64.getEncoder().encodeToString(server2))
        assertNull(StoredBragiTrustStore(storage).trustedServer(BragiHandshake.identityKeyId(server1)))
    }

    @Test fun `no stored name or value holds a Bluetooth address`() {
        val storage = MapBragiKeyStorage()
        val s = StoredBragiTrustStore(storage)
        s.trust(server1)
        val address = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")
        for ((k, v) in storage.values) assertFalse(address.containsMatchIn(k) || address.containsMatchIn(v), k)
    }
}
