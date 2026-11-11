package io.github.langkurst.ruru.link

/** An open control session the connection manager can watch and close (from Earshot's LinkFactory.kt). */
interface ManagedLink {
    val isOpen: Boolean

    /** Suspends until the session is closed, by us or by the link. Returns the cause (null when local). */
    suspend fun awaitClosed(): Throwable?

    fun close()
}
