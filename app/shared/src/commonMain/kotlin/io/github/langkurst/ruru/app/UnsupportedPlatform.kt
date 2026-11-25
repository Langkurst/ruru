package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind

/** A system Ruru cannot reach headphones on (yet). Demo mode still works. */
class UnsupportedPlatform(override val name: String) : HeadsetPlatform {
    override val supported: Boolean = false
    override val routes: Set<RouteKind> = emptySet()
    override suspend fun headsets(): List<HeadsetCandidate> = emptyList()
    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport =
        throw UnsupportedOperationException("Bluetooth control is not available on $name")
}
