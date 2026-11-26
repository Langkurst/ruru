package io.github.langkurst.ruru.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A pairing question for the person: Skullcandy's Crusher asks to pair with Ruru. [passcode] is the
 * number both sides derive (null when the headphones ask for a plain yes, [digits] 0).
 */
class PairingRequest(val passcode: Int?, val digits: Int) {
    internal val answer = CompletableDeferred<Boolean>()
}

/**
 * The bridge between the Skullcandy session, which needs a yes or no, and the screen that asks it.
 * The platform's start-up code points the session's pairing prompt at [ask]; the screen shows
 * [pending] and answers with [answer]. Nothing pairs unless the person presses Pair. A request nobody
 * answers is declined when the session gives up waiting (the caller's cancellation).
 */
class PairingRequests {
    private val _pending = MutableStateFlow<PairingRequest?>(null)
    val pending: StateFlow<PairingRequest?> = _pending

    /** Shows the question and waits for the answer: true to pair, false for Not now. */
    suspend fun ask(passcode: Int?, digits: Int): Boolean {
        val request = PairingRequest(passcode, digits)
        _pending.value = request
        try {
            return request.answer.await()
        } finally {
            _pending.update { if (it === request) null else it }
        }
    }

    fun answer(request: PairingRequest, pair: Boolean) {
        request.answer.complete(pair)
        _pending.update { if (it === request) null else it }
    }

    companion object {
        /** The one every screen of this process shows. */
        val shared = PairingRequests()
    }
}
