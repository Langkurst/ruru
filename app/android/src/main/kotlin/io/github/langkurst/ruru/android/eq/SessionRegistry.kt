package io.github.langkurst.ruru.android.eq

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One audio session another app told Android about with the effect-control-session broadcast. */
data class AudioSession(
    val sessionId: Int,
    /** May be null: the extra is optional and a few players leave it out. */
    val packageName: String?,
    val openedAtMillis: Long,
)

/**
 * The audio sessions that music and video apps have announced, newest last. The manifest receiver writes
 * to it and the equaliser reads it. The package name is held in memory only and never logged, so Ruru
 * keeps no record of what was played.
 *
 * A class so tests can make their own; the app and the receiver share [shared], because the receiver can
 * be the very first thing that runs in a cold process.
 */
class SessionRegistry(private val now: () -> Long = System::currentTimeMillis) {
    private val current = MutableStateFlow<List<AudioSession>>(emptyList())
    val sessions: StateFlow<List<AudioSession>> = current

    fun open(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return // 0 is the global mix; an app never announces it
        log("open session=$sessionId")
        current.update { list -> list.filterNot { it.sessionId == sessionId } + AudioSession(sessionId, packageName, now()) }
    }

    fun close(sessionId: Int) {
        log("close session=$sessionId")
        current.update { list -> list.filterNot { it.sessionId == sessionId } }
    }

    fun clear() = current.update { emptyList() }

    /**
     * Drop sessions announced more than [maxAgeMillis] ago: a player the system killed never says it has
     * closed, so the list would only grow. A live player announces its session again on its next start.
     * A session in [attached] carries a working effect and may play for hours after its one announcement,
     * so it may be up to [attachedMaxAgeMillis] old instead.
     */
    fun pruneOlderThan(
        maxAgeMillis: Long,
        nowMillis: Long = now(),
        attached: Set<Int> = emptySet(),
        attachedMaxAgeMillis: Long = maxAgeMillis,
    ) {
        current.update { list ->
            list.filter { s ->
                val limit = if (s.sessionId in attached) maxOf(maxAgeMillis, attachedMaxAgeMillis) else maxAgeMillis
                nowMillis - s.openedAtMillis <= limit
            }
        }
    }

    // Wrapped so this also runs in plain JVM unit tests, where android.util.Log is a stub that throws.
    private fun log(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }

    companion object {
        private const val TAG = "Ruru/Sessions"

        /** The one registry of the app. */
        val shared = SessionRegistry()
    }
}
