package io.github.langkurst.ruru.android.eq

/**
 * When to forget every announced session after Bluetooth sound went away. A player the system kills never
 * says it has closed, so losing the headphones is the moment to forget them; but a player that keeps
 * playing through a short drop, or a tap on the output switcher, never announces its session again. So the
 * sessions are forgotten only once the loss has lasted longer than [graceMillis]: at the first check after
 * that, or when Bluetooth comes back too late. Not thread-safe; the backend calls it under its mutex.
 */
internal class BluetoothLossGrace(private val graceMillis: Long) {
    private var wasPresent = false
    private var lostAtMillis: Long? = null

    /** Feed the current state; true means "forget every session now". */
    fun shouldForgetSessions(present: Boolean, nowMillis: Long): Boolean {
        if (wasPresent && !present) lostAtMillis = nowMillis
        wasPresent = present
        val lostAt = lostAtMillis ?: return false
        if (nowMillis - lostAt <= graceMillis) {
            if (present) lostAtMillis = null // back in time: keep every session
            return false
        }
        lostAtMillis = null
        return true
    }
}

/**
 * Which announced sessions stay listed: [BluetoothLossGrace] decides when a loss forgets them all, and
 * the age limits drop the rest. A session that has carried the effect keeps the longer
 * [carriedMaxAgeMillis] even while the effect is released: an audiobook or sleep-sound app announces once
 * (say at 23:00) and plays all night, and a one-second drop at 05:30 releases every effect, so "holds an
 * effect right now" would prune it during the grace and the equaliser would stay off until playback
 * restarts. Not thread-safe; the backend calls it under its mutex.
 */
internal class SessionAging(
    private val registry: SessionRegistry,
    graceMillis: Long = BT_LOSS_GRACE_MS,
    private val maxAgeMillis: Long = SESSION_MAX_AGE_MS,
    private val carriedMaxAgeMillis: Long = CARRIED_SESSION_MAX_AGE_MS,
) {
    private val btLoss = BluetoothLossGrace(graceMillis)
    private val carriedEffect = HashSet<Int>()

    /** Before the attach pass. [elapsedMillis] is the monotonic clock the grace runs on, [wallMillis] the one sessions are stamped with. */
    fun prune(btPresent: Boolean, elapsedMillis: Long, wallMillis: Long = System.currentTimeMillis()) {
        if (btLoss.shouldForgetSessions(btPresent, elapsedMillis)) {
            registry.clear()
            carriedEffect.clear()
        }
        registry.pruneOlderThan(maxAgeMillis, wallMillis, attached = carriedEffect.toSet(), attachedMaxAgeMillis = carriedMaxAgeMillis)
    }

    /** After the attach pass: remember what holds an effect now, forget what the registry no longer lists. */
    fun noteAttached(attached: Set<Int>) {
        carriedEffect += attached
        carriedEffect.retainAll(registry.sessions.value.mapTo(HashSet()) { it.sessionId })
    }

    companion object {
        const val BT_LOSS_GRACE_MS = 2 * 60_000L
        const val SESSION_MAX_AGE_MS = 6 * 3_600_000L
        const val CARRIED_SESSION_MAX_AGE_MS = 24 * 3_600_000L
    }
}
