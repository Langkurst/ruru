package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.VirtualHeadset
import kotlinx.coroutines.CoroutineScope

// The JVM (desktop and Android): the real session, with the keys and the pairing screen the app
// installed in BragiSecurity.

internal actual fun bragiSupported(): Boolean = true

internal actual fun bragiSession(transport: ByteTransport, scope: CoroutineScope): HeadsetSession =
    BragiSession(BragiConnection(transport, scope))

private val demoCrusher: VirtualSkullcandy by lazy { VirtualSkullcandy() }

/** One shared unit, so the demo's settings survive a reconnect; a new link re-attaches to it. */
internal actual fun bragiVirtual(): VirtualHeadset? = VirtualHeadset(VirtualSkullcandy.NAME_CRUSHER) { VirtualSkullcandyTransport(demoCrusher) }
