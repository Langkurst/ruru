package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.VirtualHeadset
import kotlinx.coroutines.CoroutineScope

// What only some platforms can do: the Bragi secure session needs javax.crypto, which the JVM
// (desktop and Android) has and Kotlin/Native (iPhone) doesn't. See BragiPlatform.jvm.kt and
// BragiPlatform.ios.kt.

/** True where a Bragi session can run. */
internal expect fun bragiSupported(): Boolean

/** A Bragi session over [transport]; throws [UnsupportedOperationException] where [bragiSupported] is false. */
internal expect fun bragiSession(transport: ByteTransport, scope: CoroutineScope): HeadsetSession

/** The demo Skullcandy, or null where there is no session. */
internal expect fun bragiVirtual(): VirtualHeadset?
