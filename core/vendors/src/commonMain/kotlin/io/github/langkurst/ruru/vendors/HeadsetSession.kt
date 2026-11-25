package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.ManagedLink
import kotlinx.coroutines.flow.Flow

/**
 * One open control session with a headset, whatever its maker: what the app's controller drives.
 * Like Bose's `HeadphoneProtocol` every call takes the current [HeadphoneState] and returns the
 * state as it should look afterwards; the controller runs them one at a time and keeps the
 * connection fields itself.
 */
interface HeadsetSession : ManagedLink {
    val family: DeviceFamily

    /**
     * What the headset sends unasked (battery, a mode changed with the button), in arrival order.
     * The controller collects it once and hands each one to [onPush], holding it back while a
     * command runs so the command's result can't overwrite it.
     */
    val pushes: Flow<Any>

    /**
     * The headset's own Bluetooth address for the platform's identity check (iPhone), read with
     * nothing but a harmless read, or null when this session can't read it. Called before [start].
     */
    suspend fun readIdentityAddress(): String? = null

    /** Once per connection: subscriptions and anything else done once, then the first full read. */
    suspend fun start(state: HeadphoneState): HeadphoneState

    /** Re-read the battery and nothing else (when the headset doesn't push it). */
    suspend fun pollBattery(state: HeadphoneState): HeadphoneState = state

    /** The state after [push], or null when it changes nothing. Must not suspend or talk to the headset. */
    fun onPush(push: Any, state: HeadphoneState): HeadphoneState?

    suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState

    /** What the Lab may read on this session, or null when it can't scan this headset (yet). */
    suspend fun labProfile(): LabProfile? = null

    /** The Lab's read-only scan: one line per read, GETs only, each through the session's write guard. */
    suspend fun scanLab(): List<String> = throw HeadphoneCommandException(LAB_UNAVAILABLE)

    companion object {
        const val LAB_UNAVAILABLE = "The Lab can't scan these headphones yet"
    }
}

/** What a session's Lab scan covers: a [title] and the [reads] it makes, all of them reads. */
data class LabProfile(val title: String, val reads: List<String>)
