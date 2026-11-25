package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.VirtualHeadset
import kotlinx.coroutines.CoroutineScope

// iPhone: no Bragi session. The secure session needs javax.crypto, and an iPhone can't open the
// headphones' RFCOMM service anyway (no route), so BragiVendor.sessionReady is false and the
// controller never opens these headphones: nothing is sent, nothing crashes.

internal actual fun bragiSupported(): Boolean = false

internal actual fun bragiSession(transport: ByteTransport, scope: CoroutineScope): HeadsetSession =
    throw UnsupportedOperationException("Skullcandy headphones can't be controlled from an iPhone")

internal actual fun bragiVirtual(): VirtualHeadset? = null
