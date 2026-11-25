package io.github.langkurst.ruru.vendors.bragi

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two places a pairing's keys can live: memory, and one small file. */
class BragiTrustStoreTest {
    private fun tempFile(): File {
        val dir = Files.createTempDirectory("ruru-bragi-trust").toFile()
        dir.deleteOnExit()
        return File(dir, "sub/bragi-trust.txt")
    }

    private val server1 = BragiHandshake.generateIdentity().publicPoint
    private val server2 = BragiHandshake.generateIdentity().publicPoint

    @Test fun `the memory store finds a server by its key id, and forgets`() {
        val s = MemoryBragiTrustStore()
        assertContentEquals(s.identity().publicPoint, s.identity().publicPoint)
        assertNull(s.trustedServer(BragiHandshake.identityKeyId(server1)))
        s.trust(server1)
        s.trust(server1)
        assertContentEquals(server1, s.trustedServer(BragiHandshake.identityKeyId(server1)))
        assertNull(s.trustedServer(BragiHandshake.identityKeyId(server2)))
        s.forgetAll()
        assertNull(s.trustedServer(BragiHandshake.identityKeyId(server1)))
    }

    @Test fun `the file store keeps the identity and the servers across a restart`() {
        val file = tempFile()
        val a = FileBragiTrustStore(file)
        val identity = a.identity().publicPoint
        a.trust(server1)
        a.trust(server2)
        val b = FileBragiTrustStore(file) // a new run of the app
        assertContentEquals(identity, b.identity().publicPoint)
        assertContentEquals(server1, b.trustedServer(BragiHandshake.identityKeyId(server1)))
        assertContentEquals(server2, b.trustedServer(BragiHandshake.identityKeyId(server2)))
        // The restored identity still signs: the private half came back with the public one.
        val ephemeral = ByteArray(65) { 4 }
        val random = ByteArray(16)
        val signature = BragiHandshake.signAuthenticate(b.identity().privateKey, ephemeral, random)
        assertTrue(BragiHandshake.verifyAuthenticate(BragiHandshake.decodePublicKey(identity), ephemeral, random, signature))
        b.forgetAll()
        assertNull(FileBragiTrustStore(file).trustedServer(BragiHandshake.identityKeyId(server1)))
        assertContentEquals(identity, FileBragiTrustStore(file).identity().publicPoint)
    }

    @Test fun `a file that is garbage becomes a new identity and no trusted servers instead of a crash`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("identity_private=!!!not base64\nserver=@@@\n")
        val s = FileBragiTrustStore(file)
        assertNotNull(s.identity())
        assertNull(s.trustedServer(ByteArray(8)))
    }

    @Test fun `a server key that is not on the curve is dropped on load`() {
        val file = tempFile()
        FileBragiTrustStore(file).trust(server1)
        val bogus = ByteArray(65) { 4 }
        file.appendText("server=" + Base64.getEncoder().encodeToString(bogus) + "\n")
        val s = FileBragiTrustStore(file)
        assertNull(s.trustedServer(BragiHandshake.identityKeyId(bogus)))
        assertNotNull(s.trustedServer(BragiHandshake.identityKeyId(server1)))
    }

    @Test fun `the file holds only keys, no Bluetooth address and no name`() {
        val file = tempFile()
        FileBragiTrustStore(file).trust(server1)
        val lines = file.readLines()
        assertEquals(setOf("identity_private", "identity_public", "server"), lines.map { it.substringBefore('=') }.toSet())
        assertFalse(lines.any { Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").containsMatchIn(it) })
    }

    @Test fun `the defaults are the safe ones`() {
        assertTrue(BragiSecurity.trustStore is MemoryBragiTrustStore)
        assertEquals(false, runBlocking { BragiSecurity.pairingPrompt.confirm(123456, 6) })
    }
}
